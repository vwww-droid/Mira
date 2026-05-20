"""Mira MCP stdio server for external AI clients.

这个服务端通过 MCP 暴露 Mira Relay 的设备发现和远程 PTY 能力。
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import shlex
import socket
import ssl
import struct
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass, field
from typing import Any, Callable

PROTOCOL_VERSION = "2025-06-18"
SERVER_NAME = "mira-mcp"
SERVER_VERSION = "0.1.0"
MAX_BUFFER_BYTES = 1024 * 1024


class ToolError(Exception):
    """工具调用错误, 会作为 MCP tool result 返回。"""


class WebSocketError(Exception):
    """WebSocket 客户端错误。"""


class RelayHttpClient:
    def __init__(self, relay_url: str) -> None:
        self.relay_url = relay_url.rstrip("/")

    def request(self, path: str, body: dict[str, Any] | None = None, timeout: float = 10.0) -> dict[str, Any]:
        data = None if body is None else json.dumps(body).encode("utf-8")
        headers = {"Content-Type": "application/json"} if body is not None else {}
        request = urllib.request.Request(
            self.relay_url + path,
            data=data,
            method="POST" if body is not None else "GET",
            headers=headers,
        )
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                payload = response.read()
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise ToolError(f"relay HTTP {exc.code}: {detail}") from exc
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            raise ToolError(f"relay request failed: {exc}") from exc
        if not payload:
            return {}
        try:
            value = json.loads(payload.decode("utf-8"))
        except json.JSONDecodeError as exc:
            raise ToolError(f"relay returned invalid JSON: {payload[:200]!r}") from exc
        if not isinstance(value, dict):
            raise ToolError("relay returned non-object JSON")
        return value

    def websocket_target(self, path: str) -> tuple[str, int, str, bool]:
        parsed = urllib.parse.urlparse(self.relay_url)
        if parsed.scheme not in {"http", "https", "ws", "wss"}:
            raise ToolError("Mira MCP supports http://, https://, ws:// or wss:// relay URLs")
        host = parsed.hostname
        if not host:
            raise ToolError("relay URL has no host")
        tls = parsed.scheme in {"https", "wss"}
        port = parsed.port or (443 if tls else 80)
        return host, port, path, tls


class BrowserWebSocket:
    def __init__(self, relay: RelayHttpClient) -> None:
        self.relay = relay
        self.socket: socket.socket | None = None
        self.lock = threading.Lock()

    def connect(self) -> None:
        host, port, path, tls = self.relay.websocket_target("/ws/browser")
        sock = socket.create_connection((host, port), timeout=8.0)
        if tls:
            sock = ssl.create_default_context().wrap_socket(sock, server_hostname=host)
        sock.settimeout(10.0)
        key = base64.b64encode(os.urandom(16)).decode("ascii")
        request = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {host}:{port}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n"
        )
        sock.sendall(request.encode("ascii"))
        header = self._read_http_header(sock)
        if b"101 Switching Protocols" not in header.split(b"\r\n", 1)[0]:
            raise WebSocketError(header.decode("iso-8859-1", errors="replace"))
        sock.settimeout(None)
        self.socket = sock

    def send_json(self, message: dict[str, Any]) -> None:
        self.send_frame(json.dumps(message, ensure_ascii=False).encode("utf-8"), opcode=0x1)

    def send_frame(self, payload: bytes, opcode: int = 0x1) -> None:
        if self.socket is None:
            raise WebSocketError("websocket is not connected")
        first = 0x80 | opcode
        length = len(payload)
        if length < 126:
            header = bytes([first, 0x80 | length])
        elif length <= 0xFFFF:
            header = bytes([first, 0x80 | 126]) + struct.pack("!H", length)
        else:
            header = bytes([first, 0x80 | 127]) + struct.pack("!Q", length)
        mask = os.urandom(4)
        masked = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
        with self.lock:
            self.socket.sendall(header + mask + masked)

    def read_frame(self) -> tuple[int, bytes]:
        if self.socket is None:
            raise WebSocketError("websocket is not connected")
        first, second = self._recv_exact(2)
        opcode = first & 0x0F
        masked = bool(second & 0x80)
        length = second & 0x7F
        if length == 126:
            length = struct.unpack("!H", self._recv_exact(2))[0]
        elif length == 127:
            length = struct.unpack("!Q", self._recv_exact(8))[0]
        mask = self._recv_exact(4) if masked else b""
        payload = self._recv_exact(length) if length else b""
        if masked:
            payload = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
        return opcode, payload

    def close(self) -> None:
        sock = self.socket
        self.socket = None
        if sock is not None:
            try:
                sock.close()
            except OSError:
                pass

    def _recv_exact(self, length: int) -> bytes:
        if self.socket is None:
            raise WebSocketError("websocket is not connected")
        data = bytearray()
        while len(data) < length:
            chunk = self.socket.recv(length - len(data))
            if not chunk:
                raise WebSocketError("websocket closed")
            data.extend(chunk)
        return bytes(data)

    @staticmethod
    def _read_http_header(sock: socket.socket) -> bytes:
        buffer = bytearray()
        while b"\r\n\r\n" not in buffer:
            chunk = sock.recv(1)
            if not chunk:
                raise WebSocketError("websocket handshake closed")
            buffer.extend(chunk)
            if len(buffer) > 65536:
                raise WebSocketError("websocket header too large")
        return bytes(buffer)


@dataclass
class TerminalSession:
    session_id: str
    install_id: str
    ws: BrowserWebSocket
    relay: RelayHttpClient
    buffer: bytearray = field(default_factory=bytearray)
    lock: threading.Condition = field(default_factory=threading.Condition)
    active: bool = True
    status: str = "opening"
    read_thread: threading.Thread | None = None

    def start_reader(self) -> None:
        self.read_thread = threading.Thread(target=self._reader_loop, name=f"MiraMcpReader-{self.session_id[:8]}", daemon=True)
        self.read_thread.start()

    def attach(self) -> None:
        self.ws.send_json({"type": "browser.attach", "protocol": 1, "installId": self.install_id, "sessionId": self.session_id})

    def send_input(self, text: str) -> None:
        payload = base64.b64encode(text.encode("utf-8")).decode("ascii")
        self.ws.send_json({"type": "terminal.input", "sessionId": self.session_id, "dataBase64": payload})

    def resize(self, cols: int, rows: int) -> None:
        self.ws.send_json({"type": "terminal.resize", "sessionId": self.session_id, "cols": cols, "rows": rows})

    def snapshot(self) -> str:
        with self.lock:
            return bytes(self.buffer).decode("utf-8", errors="replace")

    def wait_for_text(self, pattern: str, timeout: float, idle_grace: float = 2.5, max_grace: float = 15.0) -> str:
        base_timeout = max(timeout, 0.1)
        idle_grace = max(idle_grace, 0.25)
        max_grace = max(max_grace, idle_grace)
        started_at = time.monotonic()
        deadline = started_at + base_timeout
        hard_deadline = started_at + base_timeout + max_grace
        last_size = -1
        last_progress_at = started_at
        with self.lock:
            while True:
                text = bytes(self.buffer).decode("utf-8", errors="replace")
                if pattern in text:
                    return text
                current_size = len(self.buffer)
                now = time.monotonic()
                if current_size != last_size:
                    last_size = current_size
                    last_progress_at = now
                    deadline = min(hard_deadline, max(deadline, now + idle_grace))
                remaining = deadline - now
                if remaining <= 0:
                    if now < hard_deadline and (now - last_progress_at) < idle_grace:
                        deadline = min(hard_deadline, now + idle_grace)
                        remaining = deadline - now
                    else:
                        raise ToolError(
                            f"timeout waiting for marker: {pattern}; "
                            f"baseTimeout={base_timeout:.2f}s idleGrace={idle_grace:.2f}s maxGrace={max_grace:.2f}s"
                        )
                self.lock.wait(min(remaining, 0.25))

    def wait_until_active(self, timeout: float) -> None:
        deadline = time.monotonic() + timeout
        with self.lock:
            while True:
                if self.status == "active" and self.active:
                    return
                if self.status in {"session closed", "device disconnected"}:
                    raise ToolError(f"session is not active: {self.status}")
                if self.status.startswith("reader stopped:"):
                    raise ToolError(self.status)
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise ToolError(f"timeout waiting for session active, current status={self.status}")
                self.lock.wait(min(remaining, 0.25))

    def close(self) -> None:
        self.active = False
        try:
            self.relay.request("/api/close", {"sessionId": self.session_id}, timeout=5.0)
        except ToolError:
            pass
        self.ws.close()
        with self.lock:
            self.lock.notify_all()

    def _append_output(self, chunk: bytes) -> None:
        with self.lock:
            self.buffer.extend(chunk)
            if len(self.buffer) > MAX_BUFFER_BYTES:
                del self.buffer[: len(self.buffer) - MAX_BUFFER_BYTES]
            self.lock.notify_all()

    def _set_status(self, status: str) -> None:
        with self.lock:
            self.status = status
            if status in {"session closed", "device disconnected"}:
                self.active = False
            self.lock.notify_all()

    def _reader_loop(self) -> None:
        try:
            while self.active:
                opcode, payload = self.ws.read_frame()
                if opcode == 0x8:
                    self._set_status("session closed")
                    return
                if opcode == 0x9:
                    self.ws.send_frame(payload, opcode=0xA)
                    continue
                if opcode != 0x1:
                    continue
                try:
                    message = json.loads(payload.decode("utf-8"))
                except (UnicodeDecodeError, json.JSONDecodeError):
                    continue
                message_type = message.get("type")
                if message_type == "terminal.output":
                    data = base64.b64decode(str(message.get("dataBase64") or ""))
                    self._append_output(data)
                elif message_type == "session.status":
                    self._set_status(str(message.get("state") or "unknown"))
                elif message_type == "session.close":
                    self._set_status("session closed")
                    return
                elif message_type == "error":
                    self._append_output(("\n[Mira relay error] " + str(message.get("error")) + "\n").encode("utf-8"))
        except Exception as exc:  # noqa: BLE001
            self._set_status(f"reader stopped: {exc}")


class MiraMcpServer:
    def __init__(self, relay_url: str, broadcast_target: str) -> None:
        self.relay = RelayHttpClient(relay_url)
        self.broadcast_target = broadcast_target
        self.sessions: dict[str, TerminalSession] = {}
        self.tools: dict[str, Callable[[dict[str, Any]], dict[str, Any]]] = {
            "mira_discover_devices": self.tool_discover_devices,
            "mira_list_devices": self.tool_list_devices,
            "mira_get_device": self.tool_get_device,
            "mira_get_screen": self.tool_get_screen,
            "mira_send_screen_input": self.tool_send_screen_input,
            "mira_open_terminal": self.tool_open_terminal,
            "mira_run_command": self.tool_run_command,
            "mira_collect_snapshot": self.tool_collect_snapshot,
            "mira_send_input": self.tool_send_input,
            "mira_read_output": self.tool_read_output,
            "mira_close_terminal": self.tool_close_terminal,
            "mira_frida_status": self.tool_frida_status,
            "mira_frida_list_processes": self.tool_frida_list_processes,
            "mira_frida_run_script": self.tool_frida_run_script,
        }

    def handle(self, message: dict[str, Any]) -> dict[str, Any] | None:
        method = message.get("method")
        request_id = message.get("id")
        try:
            if method == "initialize":
                return self.response(request_id, self.initialize_result(message.get("params") or {}))
            if method == "notifications/initialized":
                return None
            if method == "ping":
                return self.response(request_id, {})
            if method == "tools/list":
                return self.response(request_id, {"tools": self.tool_definitions()})
            if method == "tools/call":
                return self.response(request_id, self.call_tool(message.get("params") or {}))
            if method == "resources/list":
                return self.response(request_id, {"resources": self.resource_list()})
            if method == "resources/read":
                return self.response(request_id, self.read_resource(message.get("params") or {}))
            if method == "prompts/list":
                return self.response(request_id, {"prompts": self.prompt_list()})
            if method == "prompts/get":
                return self.response(request_id, self.get_prompt(message.get("params") or {}))
            return self.error(request_id, -32601, f"method not found: {method}")
        except Exception as exc:  # noqa: BLE001
            return self.error(request_id, -32603, str(exc))

    def initialize_result(self, params: dict[str, Any]) -> dict[str, Any]:
        requested = str(params.get("protocolVersion") or PROTOCOL_VERSION)
        return {
            "protocolVersion": requested if requested else PROTOCOL_VERSION,
            "capabilities": {"tools": {"listChanged": False}, "resources": {"listChanged": False}, "prompts": {"listChanged": False}},
            "serverInfo": {"name": SERVER_NAME, "title": "Mira Remote Device Workbench", "version": SERVER_VERSION},
            "instructions": (
                "Use Mira tools to inspect connected Android or iOS devices, read outline and screen state, open an on-demand PTY session, "
                "run short diagnostic commands, and use the built-in Frida runtime exposed by the Mira app. Prefer mira_get_device or "
                "mira_list_devices to select a device, mira_run_command for non-interactive shell analysis, mira_get_screen plus "
                "mira_send_screen_input for app UI exploration, and mira_frida_status or mira_frida_run_script for runtime instrumentation. "
                "Important iOS guidance: the iSH-based PTY and Frida path is slower than Android because syscalls go through a translation layer, "
                "so prefer one long-lived session for a workflow, avoid frequent open-close-open terminal churn, and batch status/list/run/RPC steps in the same session when possible. "
                "The server already reuses an active session for the same installId when sessionId is omitted. "
                "When the user asks for Magisk risk review, provide only the environment context: Magisk phone, third-party app shell, real PTY, and BusyBox availability."
            ),
        }

    def call_tool(self, params: dict[str, Any]) -> dict[str, Any]:
        name = str(params.get("name") or "")
        arguments = params.get("arguments") or {}
        if not isinstance(arguments, dict):
            return self.tool_result({"error": "arguments must be an object"}, is_error=True)
        tool = self.tools.get(name)
        if tool is None:
            return self.tool_result({"error": f"unknown tool: {name}"}, is_error=True)
        try:
            data = tool(arguments)
            return self.tool_result(data)
        except ToolError as exc:
            return self.tool_result({"error": str(exc)}, is_error=True)

    def tool_definitions(self) -> list[dict[str, Any]]:
        return [
            {
                "name": "mira_discover_devices",
                "title": "Discover Mira Android devices",
                "description": "Legacy LAN scan helper. In public relay mode, prefer mira_list_devices for devices connected through /ws/control.",
                "inputSchema": {
                    "type": "object",
                    "properties": {"broadcastTarget": {"type": "string", "description": "Broadcast or device IP target. Defaults to configured target."}},
                },
            },
            {
                "name": "mira_list_devices",
                "title": "List known Mira devices",
                "description": "List devices currently known by Mira Relay, including phones connected through /ws/control.",
                "inputSchema": {
                    "type": "object",
                    "properties": {"refresh": {"type": "boolean"}, "broadcastTarget": {"type": "string"}, "onlyOnline": {"type": "boolean", "default": False}},
                },
            },
            {
                "name": "mira_get_device",
                "title": "Get one Mira device detail",
                "description": "Return a single device record with platform, address, outline, metrics, and current state.",
                "inputSchema": {
                    "type": "object",
                    "properties": {"installId": {"type": "string", "description": "Target installId. If omitted and exactly one online device exists, it is selected."}},
                },
            },
            {
                "name": "mira_get_screen",
                "title": "Get latest Mira screen frame",
                "description": "Read the latest uploaded app screen frame metadata, optionally including JPEG dataBase64.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "installId": {"type": "string", "description": "Target installId. If omitted and exactly one online device exists, it is selected."},
                        "includeFrameData": {"type": "boolean", "default": False},
                    },
                },
            },
            {
                "name": "mira_send_screen_input",
                "title": "Send screen input to device",
                "description": "Queue a tap, text, paste, key, copy, selectall, or clear action to the Mira app screen channel.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "installId": {"type": "string", "description": "Target installId. If omitted and exactly one online device exists, it is selected."},
                        "kind": {"type": "string", "enum": ["tap", "text", "paste", "key", "copy", "selectall", "clear"]},
                        "x": {"type": "number"},
                        "y": {"type": "number"},
                        "text": {"type": "string"},
                        "key": {"type": "string"},
                        "requestId": {"type": "string"},
                        "clientId": {"type": "string"},
                    },
                    "required": ["kind"],
                },
            },
            {
                "name": "mira_open_terminal",
                "title": "Open Android PTY session",
                "description": "Open an on-demand terminal session on a selected device and attach as a browser client. On iOS, prefer keeping the session alive for the whole workflow instead of frequent reopen cycles.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "installId": {"type": "string", "description": "Device installId. If omitted and one device exists, it is selected."},
                        "cols": {"type": "integer", "default": 120},
                        "rows": {"type": "integer", "default": 36},
                        "reuseExisting": {"type": "boolean", "default": False, "description": "Attach to an already-active relay session instead of force-closing and reopening it."},
                    },
                },
            },
            {
                "name": "mira_run_command",
                "title": "Run command in Android PTY",
                "description": "Run a command in a persistent device terminal session and wait for a marker with exit status. On iOS, command startup is slower because it runs through iSH syscall translation.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "command": {"type": "string"},
                        "sessionId": {"type": "string"},
                        "installId": {"type": "string"},
                        "timeoutSeconds": {"type": "number", "default": 10},
                        "cols": {"type": "integer", "default": 120},
                        "rows": {"type": "integer", "default": 36},
                        "closeAfter": {"type": "boolean", "default": False},
                        "reuseExisting": {
                            "type": "boolean",
                            "default": True,
                            "description": "Attach to an already-active relay session instead of closing UI-owned sessions. Defaults to true so MCP output remains visible in the console.",
                        },
                    },
                    "required": ["command"],
                },
            },
            {
                "name": "mira_collect_snapshot",
                "title": "Collect Android terminal snapshot",
                "description": "Run the recommended first-pass Android analysis commands and return a terminal transcript.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "sessionId": {"type": "string"},
                        "installId": {"type": "string"},
                        "timeoutSeconds": {"type": "number", "default": 15},
                        "closeAfter": {"type": "boolean", "default": False},
                    },
                },
            },
            {
                "name": "mira_send_input",
                "title": "Send raw terminal input",
                "description": "Send raw interactive input to an existing PTY session.",
                "inputSchema": {"type": "object", "properties": {"sessionId": {"type": "string"}, "input": {"type": "string"}}, "required": ["sessionId", "input"]},
            },
            {
                "name": "mira_read_output",
                "title": "Read terminal transcript",
                "description": "Read recent buffered terminal output for a session.",
                "inputSchema": {"type": "object", "properties": {"sessionId": {"type": "string"}, "tailBytes": {"type": "integer", "default": 20000}}, "required": ["sessionId"]},
            },
            {
                "name": "mira_close_terminal",
                "title": "Close Android PTY session",
                "description": "Close a Mira terminal session and clean the device-side PTY/toolbox state. Can also request Relay to close a known sessionId from a previous MCP process.",
                "inputSchema": {"type": "object", "properties": {"sessionId": {"type": "string"}}, "required": ["sessionId"]},
            },
            {
                "name": "mira_frida_status",
                "title": "Read Mira Frida runtime status",
                "description": "Verify the built-in Frida runtime is reachable from the Mira sandbox and return target summary JSON. On iOS, allow higher latency and prefer reusing the same session for follow-up Frida calls.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "sessionId": {"type": "string"},
                        "installId": {"type": "string"},
                        "timeoutSeconds": {"type": "number", "default": 8},
                        "cols": {"type": "integer", "default": 120},
                        "rows": {"type": "integer", "default": 36},
                        "closeAfter": {"type": "boolean", "default": False},
                    },
                },
            },
            {
                "name": "mira_frida_list_processes",
                "title": "List Frida-visible processes",
                "description": "Enumerate processes visible from the Mira built-in Frida remote device and return a trimmed process list. On iOS, this is optimized for the slower iSH-backed Python runtime.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "sessionId": {"type": "string"},
                        "installId": {"type": "string"},
                        "timeoutSeconds": {"type": "number", "default": 8},
                        "limit": {"type": "integer", "default": 32},
                        "cols": {"type": "integer", "default": 120},
                        "rows": {"type": "integer", "default": 36},
                        "closeAfter": {"type": "boolean", "default": False},
                    },
                },
            },
            {
                "name": "mira_frida_run_script",
                "title": "Run Frida JavaScript inside Mira",
                "description": "Attach to the built-in Frida Gadget target, load a JavaScript snippet, collect send() messages, and optionally call an exported RPC method. On iOS, prefer running multiple Frida operations in one reused session.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "script": {"type": "string", "description": "Frida JavaScript source code. Use send(...) or rpc.exports for structured output."},
                        "sessionId": {"type": "string"},
                        "installId": {"type": "string"},
                        "target": {"type": "string", "default": "Gadget"},
                        "rpcMethod": {"type": "string", "description": "Optional rpc.exports method name to invoke after script.load()."},
                        "rpcArgs": {"type": "array", "description": "Optional positional arguments for rpcMethod."},
                        "waitSeconds": {"type": "number", "default": 0.5, "description": "When rpcMethod is omitted, how long to wait after script.load() before collecting messages."},
                        "timeoutSeconds": {"type": "number", "default": 12},
                        "cols": {"type": "integer", "default": 120},
                        "rows": {"type": "integer", "default": 36},
                        "closeAfter": {"type": "boolean", "default": False},
                    },
                    "required": ["script"],
                },
            },
        ]

    def tool_discover_devices(self, arguments: dict[str, Any]) -> dict[str, Any]:
        target = str(arguments.get("broadcastTarget") or self.broadcast_target)
        data = self.relay.request("/api/discover", {"broadcastTarget": target}, timeout=10.0)
        return {"relayUrl": self.relay.relay_url, "broadcastTarget": target, "devices": data.get("devices", [])}

    def tool_list_devices(self, arguments: dict[str, Any]) -> dict[str, Any]:
        if bool(arguments.get("refresh")):
            self.tool_discover_devices(arguments)
        devices = self.list_devices()
        if bool(arguments.get("onlyOnline")):
            devices = self.online_devices(devices)
        return {"relayUrl": self.relay.relay_url, "devices": devices}

    def tool_get_device(self, arguments: dict[str, Any]) -> dict[str, Any]:
        devices = self.list_devices()
        install_id = self.resolve_install_id(arguments.get("installId"), devices=devices)
        device = self.find_device(devices, install_id)
        return {"relayUrl": self.relay.relay_url, "device": device}

    def tool_get_screen(self, arguments: dict[str, Any]) -> dict[str, Any]:
        install_id = self.resolve_install_id(arguments.get("installId"))
        include_frame_data = bool(arguments.get("includeFrameData"))
        query = urllib.parse.urlencode({"installId": install_id, "metadataOnly": "0" if include_frame_data else "1"})
        frame = self.relay.request(f"/api/screen/latest?{query}", timeout=10.0)
        if not include_frame_data and "dataBase64" in frame:
            frame["dataBase64"] = ""
        return {"relayUrl": self.relay.relay_url, "installId": install_id, "frame": frame, "includeFrameData": include_frame_data}

    def tool_send_screen_input(self, arguments: dict[str, Any]) -> dict[str, Any]:
        install_id = self.resolve_install_id(arguments.get("installId"))
        body = {"installId": install_id, "kind": str(arguments.get("kind") or "").strip().lower()}
        for key in ("x", "y", "text", "key", "requestId", "clientId"):
            if key in arguments and arguments.get(key) is not None:
                body[key] = arguments.get(key)
        result = self.relay.request("/api/screen/input", body, timeout=10.0)
        result["installId"] = install_id
        return result

    def tool_open_terminal(self, arguments: dict[str, Any]) -> dict[str, Any]:
        install_id = self.resolve_install_id(arguments.get("installId"))
        cols = int(arguments.get("cols") or 120)
        rows = int(arguments.get("rows") or 36)
        reused_session = False
        reopened_existing = False
        reuse_existing = bool(arguments.get("reuseExisting"))
        try:
            opened = self.relay.request("/api/open", {"installId": install_id, "cols": cols, "rows": rows}, timeout=10.0)
            session_id = str(opened.get("sessionId") or "")
            if not session_id:
                raise ToolError("relay did not return sessionId")
        except ToolError as exc:
            detail = str(exc)
            match = re.search(r'"sessionId"\s*:\s*"([^"]+)"', detail)
            if "relay HTTP 409" not in detail or match is None:
                raise
            conflicted_session_id = match.group(1)
            if reuse_existing:
                session_id = conflicted_session_id
                reused_session = True
            else:
                self.relay.request("/api/close", {"sessionId": conflicted_session_id}, timeout=5.0)
                time.sleep(0.2)
                reopened_existing = True
                opened = self.relay.request("/api/open", {"installId": install_id, "cols": cols, "rows": rows}, timeout=10.0)
                session_id = str(opened.get("sessionId") or "")
                if not session_id:
                    raise ToolError("relay did not return sessionId after reopening conflicted session")
        ws = BrowserWebSocket(self.relay)
        try:
            ws.connect()
            session = TerminalSession(session_id=session_id, install_id=install_id, ws=ws, relay=self.relay)
            session.start_reader()
            session.attach()
            session.resize(cols, rows)
        except Exception:
            ws.close()
            raise
        self.sessions[session_id] = session
        time.sleep(0.2)
        return {
            "sessionId": session_id,
            "installId": install_id,
            "status": session.status,
            "cols": cols,
            "rows": rows,
            "reusedSession": reused_session,
            "reopenedExisting": reopened_existing,
        }

    def tool_run_command(self, arguments: dict[str, Any]) -> dict[str, Any]:
        command = str(arguments.get("command") or "")
        if not command.strip():
            raise ToolError("command is required")
        timeout = float(arguments.get("timeoutSeconds") or 10.0)
        session, opened, reused_shared = self.ensure_command_session(arguments)
        result = self.execute_command(session, command, timeout)
        result["openedSession"] = opened
        result["reusedSharedSession"] = reused_shared
        if bool(arguments.get("closeAfter")):
            if reused_shared:
                result["closed"] = False
                result["closeSkippedReason"] = "reused shared relay session"
            else:
                self.tool_close_terminal({"sessionId": session.session_id})
                result["closed"] = True
        return result

    def tool_collect_snapshot(self, arguments: dict[str, Any]) -> dict[str, Any]:
        devices = self.list_devices()
        install_id = self.resolve_install_id(arguments.get("installId"), devices=devices)
        device = self.find_device(devices, install_id)
        platform = str(device.get("platform") or "").strip().lower()
        base_commands = [
            "echo '[mira] basic'",
            "/system/bin/id 2>/dev/null || id",
            "/system/bin/uname -a 2>/dev/null || uname -a",
            "pwd",
            "printf 'PREFIX=%s\\n' \"$PREFIX\"",
            "printf 'PATH=%s\\n' \"$PATH\"",
            "printf 'MIRA_BUSYBOX_ABI=%s\\n' \"$MIRA_BUSYBOX_ABI\"",
            "printf 'MIRA_BUSYBOX=%s\\n' \"$MIRA_BUSYBOX\"",
            "printf 'MIRA_TOOLBOX_MANIFEST=%s\\n' \"$MIRA_TOOLBOX_MANIFEST\"",
            "command -v busybox",
            "busybox echo busybox-ok",
            "busybox --list 2>/dev/null | (/system/bin/head -80 2>/dev/null || head -80)",
        ]
        if platform == "ios":
            platform_commands = [
                "echo '[mira] ios'",
                "uname -m",
                "echo '[mira] mounts'",
                "(mount 2>/dev/null || cat /proc/mounts 2>/dev/null) | head -80",
                "echo '[mira] storage'",
                "df -h",
                "echo '[mira] process'",
                "(ps 2>/dev/null || busybox ps 2>/dev/null) | head -80",
                "echo '[mira] meminfo'",
                "(cat /proc/meminfo 2>/dev/null || free 2>/dev/null || vm_stat 2>/dev/null) | head -30",
            ]
        else:
            platform_commands = [
                "echo '[mira] android'",
                "/system/bin/getprop ro.product.model 2>/dev/null || getprop ro.product.model",
                "/system/bin/getprop ro.build.version.sdk 2>/dev/null || getprop ro.build.version.sdk",
                "/system/bin/getprop ro.product.cpu.abilist 2>/dev/null || getprop ro.product.cpu.abilist",
                "echo '[mira] mounts'",
                "(/system/bin/cat /proc/self/mountinfo 2>/dev/null || cat /proc/self/mountinfo) | (/system/bin/head -80 2>/dev/null || head -80)",
                "echo '[mira] storage'",
                "/system/bin/df -h 2>/dev/null || df -h",
                "echo '[mira] process'",
                "(/system/bin/ps -A 2>/dev/null || /system/bin/ps 2>/dev/null || ps) | (/system/bin/head -80 2>/dev/null || head -80)",
                "echo '[mira] meminfo'",
                "(/system/bin/cat /proc/meminfo 2>/dev/null || cat /proc/meminfo) | (/system/bin/head -30 2>/dev/null || head -30)",
            ]
        command = "\n".join(base_commands + platform_commands)
        call_args = dict(arguments)
        call_args["installId"] = install_id
        call_args["command"] = command
        call_args["timeoutSeconds"] = float(arguments.get("timeoutSeconds") or 15.0)
        result = self.tool_run_command(call_args)
        if bool(arguments.get("closeAfter")):
            self.tool_close_terminal({"sessionId": result["sessionId"]})
            result["closed"] = True
        return result

    def tool_send_input(self, arguments: dict[str, Any]) -> dict[str, Any]:
        session = self.get_session(str(arguments.get("sessionId") or ""))
        raw = str(arguments.get("input") or "")
        session.send_input(raw)
        return {"sessionId": session.session_id, "bytesSent": len(raw.encode("utf-8")), "status": session.status}

    def tool_read_output(self, arguments: dict[str, Any]) -> dict[str, Any]:
        session = self.get_session(str(arguments.get("sessionId") or ""))
        tail_bytes = int(arguments.get("tailBytes") or 20000)
        snapshot = session.snapshot()
        if tail_bytes > 0:
            encoded = snapshot.encode("utf-8", errors="replace")[-tail_bytes:]
            snapshot = encoded.decode("utf-8", errors="replace")
        return {"sessionId": session.session_id, "status": session.status, "output": snapshot}

    def tool_close_terminal(self, arguments: dict[str, Any]) -> dict[str, Any]:
        session_id = str(arguments.get("sessionId") or "")
        session = self.sessions.pop(session_id, None)
        if session is None:
            if not session_id:
                raise ToolError("sessionId is required")
            self.relay.request("/api/close", {"sessionId": session_id}, timeout=5.0)
            return {"sessionId": session_id, "closed": True, "localSession": False}
        session.close()
        return {"sessionId": session_id, "closed": True, "localSession": True}

    def tool_frida_status(self, arguments: dict[str, Any]) -> dict[str, Any]:
        session, opened = self.ensure_session(arguments)
        command = "frida --status"
        device = self.device_for_session(session)
        platform = self.device_platform(device)
        timeout = float(arguments.get("timeoutSeconds") or (20.0 if platform == "ios" else 8.0))
        result = self.execute_json_command(session, command, timeout, "mira_frida_status")
        payload = {
            "sessionId": session.session_id,
            "openedSession": opened,
            "status": session.status,
            "frida": result,
        }
        if bool(arguments.get("closeAfter")):
            self.tool_close_terminal({"sessionId": session.session_id})
            payload["closed"] = True
        return payload

    def tool_frida_list_processes(self, arguments: dict[str, Any]) -> dict[str, Any]:
        limit = int(arguments.get("limit") or 32)
        if limit < 1:
            raise ToolError("limit must be >= 1")
        limit = min(limit, 256)
        session, opened = self.ensure_session(arguments)
        device = self.device_for_session(session)
        platform = self.device_platform(device)
        timeout = float(arguments.get("timeoutSeconds") or (20.0 if platform == "ios" else 8.0))
        if platform == "ios":
            command = self.wrap_ios_python_command(self.build_ios_frida_list_python_source(limit))
        else:
            python_source = self.build_frida_python_source(
                {
                    "mode": "list_processes",
                    "host": "127.0.0.1:27042",
                    "limit": limit,
                }
            )
            command = self.wrap_python_command(python_source)
        result = self.execute_json_command(
            session,
            command,
            timeout,
            "mira_frida_list_processes",
        )
        payload = {
            "sessionId": session.session_id,
            "openedSession": opened,
            "status": session.status,
            "frida": result,
        }
        if bool(arguments.get("closeAfter")):
            self.tool_close_terminal({"sessionId": session.session_id})
            payload["closed"] = True
        return payload

    def tool_frida_run_script(self, arguments: dict[str, Any]) -> dict[str, Any]:
        script = str(arguments.get("script") or "")
        if not script.strip():
            raise ToolError("script is required")
        rpc_args = arguments.get("rpcArgs") or []
        if not isinstance(rpc_args, list):
            raise ToolError("rpcArgs must be an array when provided")
        wait_seconds = float(arguments.get("waitSeconds") or 0.5)
        if wait_seconds < 0:
            raise ToolError("waitSeconds must be >= 0")
        session, opened = self.ensure_session(arguments)
        device = self.device_for_session(session)
        platform = self.device_platform(device)
        timeout = float(arguments.get("timeoutSeconds") or (30.0 if platform == "ios" else 12.0))
        if platform == "ios":
            python_source = self.build_ios_frida_run_script_python_source(
                script=script,
                target=str(arguments.get("target") or "Gadget"),
                rpc_method=str(arguments.get("rpcMethod") or ""),
                rpc_args=rpc_args,
                wait_seconds=wait_seconds,
            )
            command = self.wrap_ios_python_command(python_source)
        else:
            python_source = self.build_frida_python_source(
                {
                    "mode": "run_script",
                    "host": "127.0.0.1:27042",
                    "target": str(arguments.get("target") or "Gadget"),
                    "scriptBase64": base64.b64encode(script.encode("utf-8")).decode("ascii"),
                    "rpcMethod": str(arguments.get("rpcMethod") or ""),
                    "rpcArgs": rpc_args,
                    "waitSeconds": wait_seconds,
                }
            )
            command = self.wrap_python_command(python_source)
        result = self.execute_json_command(
            session,
            command,
            timeout,
            "mira_frida_run_script",
        )
        payload = {
            "sessionId": session.session_id,
            "openedSession": opened,
            "status": session.status,
            "frida": result,
        }
        if bool(arguments.get("closeAfter")):
            self.tool_close_terminal({"sessionId": session.session_id})
            payload["closed"] = True
        return payload

    def list_devices(self) -> list[dict[str, Any]]:
        data = self.relay.request("/api/devices", timeout=10.0)
        devices = data.get("devices", [])
        if not isinstance(devices, list):
            raise ToolError("relay returned invalid devices payload")
        return [device for device in devices if isinstance(device, dict)]

    @staticmethod
    def online_devices(devices: list[dict[str, Any]]) -> list[dict[str, Any]]:
        online: list[dict[str, Any]] = []
        for device in devices:
            state = str(device.get("state") or "").strip().lower()
            if state and state not in {"offline", "disconnected"}:
                online.append(device)
        return online

    @staticmethod
    def find_device(devices: list[dict[str, Any]], install_id: str) -> dict[str, Any]:
        for device in devices:
            if str(device.get("installId") or "") == install_id:
                return device
        raise ToolError(f"unknown installId: {install_id}")

    def device_for_session(self, session: TerminalSession) -> dict[str, Any] | None:
        try:
            return self.find_device(self.list_devices(), session.install_id)
        except ToolError:
            return None

    @staticmethod
    def device_platform(device: dict[str, Any] | None) -> str:
        if not device:
            return ""
        return str(device.get("platform") or "").strip().lower()

    def ensure_session(self, arguments: dict[str, Any]) -> tuple[TerminalSession, bool]:
        session, opened, _ = self.ensure_command_session(arguments, default_reuse_existing=False)
        return session, opened

    def ensure_command_session(
        self,
        arguments: dict[str, Any],
        default_reuse_existing: bool = True,
    ) -> tuple[TerminalSession, bool, bool]:
        session_id = str(arguments.get("sessionId") or "")
        opened = False
        reused_shared = False
        if not session_id:
            install_id = str(arguments.get("installId") or "")
            if not install_id:
                try:
                    install_id = self.resolve_install_id(arguments.get("installId"))
                except ToolError:
                    install_id = ""
            if install_id:
                for existing in self.sessions.values():
                    if existing.install_id == install_id and existing.active:
                        return existing, False, False
            open_args = dict(arguments)
            if "reuseExisting" not in open_args:
                open_args["reuseExisting"] = default_reuse_existing
            opened_data = self.tool_open_terminal(open_args)
            session_id = str(opened_data["sessionId"])
            opened = True
            reused_shared = bool(opened_data.get("reusedSession"))
        return self.get_session(session_id), opened, reused_shared

    def execute_command(self, session: TerminalSession, command: str, timeout: float) -> dict[str, Any]:
        session.wait_until_active(max(5.0, min(timeout, 20.0)))
        marker = f"__MIRA_MCP_DONE_{uuid.uuid4().hex}__"
        before = len(session.buffer)
        session.send_input(command.rstrip("\n") + "\nprintf '\\n%s:%s\\n' " + shlex.quote(marker) + " \"$?\"\n")
        transcript = session.wait_for_text(marker + ":", timeout)
        new_text = transcript[before:]
        match = re.search(re.escape(marker) + r":(\d+)", new_text)
        exit_code = int(match.group(1)) if match else None
        cleaned = re.sub(r"\r?\n?" + re.escape(marker) + r":\d+\r?\n?", "\n", new_text)
        return {"sessionId": session.session_id, "exitCode": exit_code, "output": cleaned, "status": session.status}

    def execute_json_command(self, session: TerminalSession, command: str, timeout: float, label: str) -> dict[str, Any]:
        begin = f"__MIRA_JSON_BEGIN_{uuid.uuid4().hex}__"
        end = f"__MIRA_JSON_END_{uuid.uuid4().hex}__"
        wrapped = "\n".join(
            [
                f"printf '%s\\n' {shlex.quote(begin)}",
                command,
                f"printf '%s\\n' {shlex.quote(end)}",
            ]
        )
        result = self.execute_command(session, wrapped, timeout)
        block = self.extract_marked_block(result["output"], begin, end, label)
        try:
            payload = self.parse_json_from_block(block)
        except json.JSONDecodeError as exc:
            raise ToolError(f"{label} returned invalid JSON: {exc}") from exc
        if not isinstance(payload, dict):
            raise ToolError(f"{label} returned non-object JSON")
        if result.get("exitCode") not in {0, None}:
            error = str(payload.get("error") or result["output"].strip() or f"{label} failed")
            raise ToolError(error)
        if payload.get("ok") is False:
            raise ToolError(str(payload.get("error") or f"{label} failed"))
        return payload

    @staticmethod
    def extract_marked_block(output: str, begin: str, end: str, label: str) -> str:
        pattern = re.compile(
            rf"(?:^|\r?\n){re.escape(begin)}\r?\n(.*?)(?:\r?\n){re.escape(end)}(?:\r?\n|$)",
            re.S,
        )
        match = pattern.search(output)
        if not match:
            raise ToolError(f"{label} did not return expected markers")
        return match.group(1).strip()

    @staticmethod
    def parse_json_from_block(block: str) -> Any:
        try:
            return json.loads(block)
        except json.JSONDecodeError:
            pass
        for line in reversed([line.strip() for line in block.splitlines() if line.strip()]):
            try:
                return json.loads(line)
            except json.JSONDecodeError:
                continue
        raise json.JSONDecodeError("Expecting value", block, 0)

    @staticmethod
    def wrap_python_command(source: str) -> str:
        marker = f"MIRA_PY_{uuid.uuid4().hex}"
        return f"python3 - <<'{marker}'\n{source.rstrip()}\n{marker}"

    @staticmethod
    def wrap_ios_python_command(source: str) -> str:
        return (
            'frida-setup >/dev/null 2>&1; '
            'export PYTHONPATH="/opt/mira/frida-python/site-packages${PYTHONPATH:+:$PYTHONPATH}"; '
            + "python3 -c "
            + shlex.quote(source.rstrip())
        )

    @staticmethod
    def build_ios_frida_list_python_source(limit: int) -> str:
        return f"""
import frida
import json
import traceback

def to_jsonable(value):
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, dict):
        return {{str(k): to_jsonable(v) for k, v in value.items()}}
    if isinstance(value, (list, tuple)):
        return [to_jsonable(item) for item in value]
    return str(value)

payload = {{"ok": False}}

try:
    device = frida.get_device_manager().add_remote_device("127.0.0.1:27042")
    processes = device.enumerate_processes()
    payload = {{
        "ok": True,
        "fridaVersion": getattr(frida, "__version__", ""),
        "processCount": len(processes),
        "processes": [
            {{
                "pid": getattr(proc, "pid", None),
                "name": getattr(proc, "name", None),
                "parameters": to_jsonable(getattr(proc, "parameters", {{}})),
            }}
            for proc in processes[:{limit}]
        ],
    }}
except Exception as exc:
    payload = {{
        "ok": False,
        "error": str(exc),
        "traceback": traceback.format_exc().splitlines(),
    }}

print(json.dumps(payload, ensure_ascii=False))
raise SystemExit(0 if payload.get("ok") else 1)
""".strip()

    @staticmethod
    def build_ios_frida_run_script_python_source(
        script: str,
        target: str,
        rpc_method: str,
        rpc_args: list[Any],
        wait_seconds: float,
    ) -> str:
        script_base64 = base64.b64encode(script.encode("utf-8")).decode("ascii")
        rpc_args_base64 = base64.b64encode(json.dumps(rpc_args, ensure_ascii=False).encode("utf-8")).decode("ascii")
        return f"""
import base64
import frida
import json
import time
import traceback

def to_jsonable(value):
    if isinstance(value, (bytes, bytearray)):
        return {{"type": "bytes", "dataBase64": base64.b64encode(value).decode("ascii")}}
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, dict):
        return {{str(k): to_jsonable(v) for k, v in value.items()}}
    if isinstance(value, (list, tuple)):
        return [to_jsonable(item) for item in value]
    return str(value)

payload = {{"ok": False}}

try:
    device = frida.get_device_manager().add_remote_device("127.0.0.1:27042")
    messages = []
    session = device.attach({target!r})
    script = session.create_script(base64.b64decode({script_base64!r}).decode("utf-8"))

    def on_message(message, data):
        entry = to_jsonable(message)
        if data is not None:
            entry["dataBase64"] = base64.b64encode(data).decode("ascii")
        messages.append(entry)

    script.on("message", on_message)
    script.load()
    rpc_method = {rpc_method!r}
    rpc_result = None
    if rpc_method:
        rpc_args = json.loads(base64.b64decode({rpc_args_base64!r}).decode("utf-8"))
        rpc_result = getattr(script.exports, rpc_method)(*rpc_args)
    else:
        deadline = time.monotonic() + max({wait_seconds!r}, 0.0)
        while time.monotonic() < deadline:
            pass
    payload = {{
        "ok": True,
        "fridaVersion": getattr(frida, "__version__", ""),
        "target": {target!r},
        "rpcMethod": rpc_method,
        "rpcResult": to_jsonable(rpc_result),
        "messageCount": len(messages),
        "messages": messages,
    }}
except Exception as exc:
    payload = {{
        "ok": False,
        "error": str(exc),
        "traceback": traceback.format_exc().splitlines(),
    }}

print(json.dumps(payload, ensure_ascii=False))
raise SystemExit(0 if payload.get("ok") else 1)
""".strip()

    @staticmethod
    def build_frida_python_source(spec: dict[str, Any]) -> str:
        encoded = base64.b64encode(json.dumps(spec, ensure_ascii=False).encode("utf-8")).decode("ascii")
        return f"""
import base64
import frida
import json
import traceback
import time

spec = json.loads(base64.b64decode({encoded!r}).decode("utf-8"))

def to_jsonable(value):
    if isinstance(value, bytes):
        return {{"type": "bytes", "dataBase64": base64.b64encode(value).decode("ascii")}}
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, dict):
        return {{str(k): to_jsonable(v) for k, v in value.items()}}
    if isinstance(value, (list, tuple)):
        return [to_jsonable(item) for item in value]
    return str(value)

payload = {{"ok": False}}

try:
    device = frida.get_device_manager().add_remote_device(spec.get("host") or "127.0.0.1:27042")
    mode = spec.get("mode")
    if mode == "list_processes":
        processes = device.enumerate_processes()
        limit = max(1, min(int(spec.get("limit") or 32), 256))
        payload = {{
            "ok": True,
            "fridaVersion": getattr(frida, "__version__", ""),
            "processCount": len(processes),
            "processes": [
                {{
                    "pid": getattr(proc, "pid", None),
                    "name": getattr(proc, "name", None),
                    "parameters": to_jsonable(getattr(proc, "parameters", {{}})),
                }}
                for proc in processes[:limit]
            ],
        }}
    elif mode == "run_script":
        target = spec.get("target") or "Gadget"
        wait_seconds = float(spec.get("waitSeconds") or 0.5)
        rpc_method = spec.get("rpcMethod") or ""
        rpc_args = spec.get("rpcArgs") or []
        script_source = base64.b64decode(spec.get("scriptBase64") or "").decode("utf-8")
        messages = []
        session = device.attach(target)
        script = session.create_script(script_source)

        def on_message(message, data):
            entry = to_jsonable(message)
            if data is not None:
                entry["dataBase64"] = base64.b64encode(data).decode("ascii")
            messages.append(entry)

        script.on("message", on_message)
        script.load()
        rpc_result = None
        if rpc_method:
            rpc_result = getattr(script.exports_sync, rpc_method)(*rpc_args)
        else:
            time.sleep(wait_seconds)
        script.unload()
        session.detach()
        payload = {{
            "ok": True,
            "fridaVersion": getattr(frida, "__version__", ""),
            "target": target,
            "rpcMethod": rpc_method,
            "rpcResult": to_jsonable(rpc_result),
            "messageCount": len(messages),
            "messages": messages,
        }}
    else:
        processes = device.enumerate_processes()
        first = processes[0] if processes else None
        payload = {{
            "ok": True,
            "fridaVersion": getattr(frida, "__version__", ""),
            "connected": True,
            "processCount": len(processes),
            "pid": getattr(first, "pid", None),
            "target": getattr(first, "name", None),
        }}
except Exception as exc:
    payload = {{
        "ok": False,
        "error": str(exc),
        "traceback": traceback.format_exc().splitlines(),
    }}

print(json.dumps(payload, ensure_ascii=False))
raise SystemExit(0 if payload.get("ok") else 1)
""".strip()

    def resolve_install_id(self, value: Any, devices: list[dict[str, Any]] | None = None) -> str:
        install_id = str(value or "")
        if install_id:
            return install_id
        if devices is None:
            devices = self.list_devices()
        online_devices = self.online_devices(devices)
        if len(online_devices) == 1:
            return str(online_devices[0].get("installId") or "")
        if len(devices) != 1:
            raise ToolError(f"installId required, online device count={len(online_devices)}, known device count={len(devices)}")
        return str(devices[0].get("installId") or "")

    def get_session(self, session_id: str) -> TerminalSession:
        session = self.sessions.get(session_id)
        if session is None:
            raise ToolError(f"unknown session: {session_id}")
        return session

    def resource_list(self) -> list[dict[str, Any]]:
        return [
            {"uri": "mira://analysis-guide", "name": "Mira Android analysis guide", "mimeType": "text/markdown"},
            {"uri": "mira://magisk-app-shell-context", "name": "Mira Magisk app-shell context", "mimeType": "text/markdown"},
            {"uri": "mira://frida-guide", "name": "Mira built-in Frida guide", "mimeType": "text/markdown"},
            {"uri": "mira://ios-frida-guide", "name": "Mira iOS Frida guide", "mimeType": "text/markdown"},
            {"uri": "mira://sessions", "name": "Mira active MCP sessions", "mimeType": "application/json"},
            {"uri": "mira://relay", "name": "Mira relay configuration", "mimeType": "application/json"},
        ]

    def read_resource(self, params: dict[str, Any]) -> dict[str, Any]:
        uri = str(params.get("uri") or "")
        if uri == "mira://analysis-guide":
            return {"contents": [{"uri": uri, "mimeType": "text/markdown", "text": ANALYSIS_GUIDE}]}
        if uri == "mira://magisk-app-shell-context":
            return {"contents": [{"uri": uri, "mimeType": "text/markdown", "text": MAGISK_CONTEXT}]}
        if uri == "mira://frida-guide":
            return {"contents": [{"uri": uri, "mimeType": "text/markdown", "text": FRIDA_GUIDE}]}
        if uri == "mira://ios-frida-guide":
            return {"contents": [{"uri": uri, "mimeType": "text/markdown", "text": IOS_FRIDA_GUIDE}]}
        if uri == "mira://sessions":
            data = {key: {"installId": value.install_id, "status": value.status, "active": value.active} for key, value in self.sessions.items()}
            return {"contents": [{"uri": uri, "mimeType": "application/json", "text": json.dumps(data, ensure_ascii=False, indent=2)}]}
        if uri == "mira://relay":
            data = {"relayUrl": self.relay.relay_url, "broadcastTarget": self.broadcast_target}
            return {"contents": [{"uri": uri, "mimeType": "application/json", "text": json.dumps(data, ensure_ascii=False, indent=2)}]}
        raise ToolError(f"unknown resource: {uri}")

    def prompt_list(self) -> list[dict[str, Any]]:
        return [
            {
                "name": "mira_android_triage",
                "title": "Mira Android terminal triage",
                "description": "Guide an AI client to collect basic Android sandbox, toolbox, mount, process and network context through Mira terminal tools.",
                "arguments": [{"name": "installId", "description": "Optional target installId", "required": False}],
            },
            {
                "name": "mira_magisk_risk_review",
                "title": "Mira Magisk app-shell risk review",
                "description": "Tell an AI client the device is a Magisk phone reached through a third-party app shell with BusyBox available.",
                "arguments": [{"name": "installId", "description": "Optional target installId", "required": False}],
            },
            {
                "name": "mira_frida_triage",
                "title": "Mira built-in Frida triage",
                "description": "Guide an AI client to verify Frida connectivity, enumerate the Gadget target, and run small Frida scripts through Mira MCP. On iOS, keep one session alive and batch the workflow.",
                "arguments": [{"name": "installId", "description": "Optional target installId", "required": False}],
            },
        ]

    def get_prompt(self, params: dict[str, Any]) -> dict[str, Any]:
        name = str(params.get("name") or "")
        if name not in {"mira_android_triage", "mira_magisk_risk_review", "mira_frida_triage"}:
            raise ToolError(f"unknown prompt: {name}")
        arguments = params.get("arguments") or {}
        install_id = str(arguments.get("installId") or "") if isinstance(arguments, dict) else ""
        suffix = f" Target installId: {install_id}." if install_id else " If exactly one device is known, select it automatically."
        prompt_text = ANALYSIS_PROMPT if name == "mira_android_triage" else RISK_PROMPT if name == "mira_magisk_risk_review" else FRIDA_PROMPT
        return {
            "description": (
                "Mira Android terminal triage workflow"
                if name == "mira_android_triage"
                else "Mira Magisk app-shell risk review workflow"
                if name == "mira_magisk_risk_review"
                else "Mira built-in Frida triage workflow"
            ),
            "messages": [
                {
                    "role": "user",
                    "content": {
                        "type": "text",
                        "text": prompt_text + suffix,
                    },
                }
            ],
        }

    @staticmethod
    def tool_result(data: dict[str, Any], is_error: bool = False) -> dict[str, Any]:
        return {
            "content": [{"type": "text", "text": json.dumps(data, ensure_ascii=False, indent=2)}],
            "structuredContent": data,
            "isError": is_error,
        }

    @staticmethod
    def response(request_id: Any, result: dict[str, Any]) -> dict[str, Any]:
        return {"jsonrpc": "2.0", "id": request_id, "result": result}

    @staticmethod
    def error(request_id: Any, code: int, message: str) -> dict[str, Any]:
        return {"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}}


ANALYSIS_GUIDE = """# Mira Android 分析指南

1. 先使用 `mira_list_devices` 获取已连接设备, 再使用 `mira_open_terminal` 或 `mira_run_command` 建立真实 PTY 会话。
2. 基础上下文命令建议: `/system/bin/id`, `/system/bin/uname -a`, `pwd`, `echo $PREFIX`, `echo $MIRA_BUSYBOX_ABI`, `command -v busybox`。
3. 文件系统和挂载建议: `/system/bin/cat /proc/self/mountinfo | /system/bin/head -80`, `mount`, `/system/bin/df -h`。
4. 进程和资源建议: `/system/bin/ps -A`, `top -b -n 1 | head -40`, `/system/bin/cat /proc/meminfo | /system/bin/head -20`。
5. Android 属性建议: `getprop ro.product.model`, `getprop ro.build.version.sdk`, `getprop ro.product.cpu.abilist`。
6. 输出分析时区分系统 shell 能力, Mira toolbox 能力和后续未实现的 apt 能力。Android 系统命令优先使用 `/system/bin/...`, 避免临时 toolbox applet 遮蔽系统命令。
7. 分析结束后调用 `mira_close_terminal` 清理 PTY 和临时 toolbox 会话目录。
"""

MAGISK_CONTEXT = """# Mira Magisk 第三方 app shell 环境上下文

1. 目标设备是一台已安装 Magisk 的 Android 手机。
2. 你进入的是 Mira 这个第三方 app sandbox 内的真实 PTY shell, 不是 adb shell, 也不是 root shell。
3. 这个 shell 默认按第三方 app 的 UID, SELinux context 和 Android 权限模型运行。
4. Mira 会在会话里放入 BusyBox, 通常可以直接执行 `busybox`, 也可以优先使用 `/system/bin/...` 调用系统命令。
5. 当前 `$PATH` 里可能包含 Mira 临时 toolbox 目录, 因此遇到命令差异时要区分系统命令和 BusyBox applet。
6. 你可以自己选择要采集的证据和分析路径, 目标是发现风险点并给出建议。
7. 除非用户明确要求, 默认不要修改设备状态, 不要写系统文件, 不要申请 root 授权。
8. 分析结束后关闭 Mira PTY 会话。
"""

FRIDA_GUIDE = """# Mira 内置 Frida 指南

1. 先用 `mira_frida_status` 验证 Mira App 内置的 Frida runtime 是否可达。
2. `mira_frida_list_processes` 会返回 Frida remote device 视角里的进程列表。当前 Android 沙盒场景通常至少能看到 `Gadget`。
3. `mira_frida_run_script` 会 attach 到默认目标 `Gadget`, 加载一段 Frida JavaScript, 收集 `send(...)` 消息, 并可选调用 `rpc.exports` 方法。
4. 如果只是做轻量探针, 建议脚本直接 `send({...})` 输出结构化信息。例如 `send({ pid: Process.id, arch: Process.arch })`。
5. 如果要拿返回值, 可以在脚本里定义 `rpc.exports = { ping() { return "pong"; } }`, 再通过 `rpcMethod` 和 `rpcArgs` 调用。
6. 二进制 message data 会被编码成 `dataBase64`, 非 JSON 类型结果会被尽量转换为 JSON 兼容结构或字符串。
7. iOS 设备上的 PTY 和 Frida 调用会经过 iSH syscall translation(系统调用翻译), 整体延迟明显高于 Android。
8. iOS 分析时优先保持一个 PTY session(会话) 持续存活, 在同一个 session 里连续执行 `status -> list -> run -> rpc`, 不要频繁 open-close-open。
9. 当前 server 已经会在 `sessionId` 省略且同一 `installId` 已有活动 session 时自动复用它。
10. Frida 相关工具底层仍通过 Mira PTY 中的 Python runtime 与 `frida` 模块交互, 所以分析完成后如无持续交互需求, 再关闭 PTY 会话。
"""

IOS_FRIDA_GUIDE = """# Mira iOS Frida 指南

1. iOS 侧的 MCP PTY 和 Frida 运行链路建立在 iSH compatibility layer(兼容层) 上, syscall(系统调用) 会经过翻译, 所以冷启动和脚本执行都比 Android 慢。
2. 默认策略应该是先开一个 PTY session, 然后在同一个 session 里连续执行 `mira_frida_status`, `mira_frida_list_processes`, `mira_frida_run_script`。
3. 除非分析结束, 否则不要在每一步后都立即关闭 PTY session, 因为频繁 reopen(重开) 比复用 session 更脆弱。
4. 当前 iOS 已实测可稳定返回大枚举结果, 例如 `ObjC.enumerateLoadedClassesSync()` 可返回数百个 image(映像) 和数万条 class(类) 记录。
5. 当前 iOS 已实测可稳定返回较大的 RPC 结果, 例如 256 KiB 级别字符串返回。
6. 如果需要做多步 Frida 工作流, 优先把 `closeAfter` 设为 `false`, 最后再显式调用 `mira_close_terminal`。
7. 如果必须逐步调用多个 Frida tool, 也优先复用同一个 MCP server 生命周期, 让 server 自动复用已有 session。
"""


ANALYSIS_PROMPT = """请通过 Mira MCP 工具对 Android 设备做一次最小终端态势分析。

步骤:
1. 调用 `mira_list_devices` 找到已经连接到 Relay 的目标设备。
2. 调用 `mira_run_command` 执行 `/system/bin/id; /system/bin/uname -a; pwd; echo $PREFIX; echo $MIRA_BUSYBOX_ABI; command -v busybox`。
3. 优先调用 `mira_collect_snapshot` 获取第一轮证据。
4. 如需补充, 继续采集 `getprop ro.product.model; getprop ro.build.version.sdk; getprop ro.product.cpu.abilist`。
5. 采集 `/system/bin/cat /proc/self/mountinfo | /system/bin/head -80` 和 `/system/bin/df -h`。
6. 采集 `/system/bin/ps -A | /system/bin/head -60` 和 `/system/bin/cat /proc/meminfo | /system/bin/head -20`。
7. 汇总设备身份, shell 环境, toolbox 状态, mount 关键点和后续建议。
8. 调用 `mira_close_terminal` 关闭会话。
"""

RISK_PROMPT = """环境信息:

这是一台已安装 Magisk 的 Android 手机。

你通过 Mira MCP 工具进入的是第三方 app 权限里的真实 PTY shell。
它不是 adb shell, 也不是 root shell。

这个 shell 可以使用 Mira 放入会话环境的 BusyBox, 一般可直接执行 `busybox`。
你也可以使用 Mira MCP 提供的终端工具自己探索。

请你自己决定分析路径, 自己发现风险点, 然后告诉用户风险是什么, 证据是什么, 以及用户接下来可以怎么处理。
完成后关闭 Mira PTY 会话。
"""

FRIDA_PROMPT = """请通过 Mira MCP 工具验证并使用 Mira Android App 内置的 Frida runtime。

步骤:
1. 调用 `mira_list_devices` 或 `mira_get_device` 选择目标设备。
2. 调用 `mira_frida_status` 确认 Frida runtime 可达。
3. 调用 `mira_frida_list_processes` 查看当前 Frida 视角下可见进程。
4. 调用 `mira_frida_run_script` 运行一段最小脚本, 例如 `send({ pid: Process.id, arch: Process.arch, platform: Process.platform })`。
5. 如果需要, 再运行带 `rpc.exports` 的脚本并通过 `rpcMethod` 调用导出函数。
6. 如果目标是 iOS, 优先复用同一个 PTY session 完成整个工作流, 不要频繁 open-close-open。
7. 总结 Frida 是否可用, 当前默认目标是谁, 收到了哪些消息, 以及后续可继续做什么分析。
8. 结束后关闭 PTY 会话。
"""


def run_stdio(server: MiraMcpServer) -> None:
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except json.JSONDecodeError as exc:
            response = MiraMcpServer.error(None, -32700, f"parse error: {exc}")
        else:
            response = server.handle(message)
        if response is not None:
            sys.stdout.write(json.dumps(response, ensure_ascii=False, separators=(",", ":")) + "\n")
            sys.stdout.flush()


def main() -> None:
    parser = argparse.ArgumentParser(description="Run Mira MCP stdio server")
    parser.add_argument("--relay", default=os.environ.get("MIRA_RELAY_URL", "http://127.0.0.1:8765"), help="Mira Relay base URL")
    parser.add_argument("--broadcast-target", default=os.environ.get("MIRA_BROADCAST_TARGET", "255.255.255.255"), help="Default LAN discovery target")
    args = parser.parse_args()
    run_stdio(MiraMcpServer(relay_url=args.relay, broadcast_target=args.broadcast_target))


if __name__ == "__main__":
    main()
