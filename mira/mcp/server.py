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

    def websocket_target(self, path: str) -> tuple[str, int, str]:
        parsed = urllib.parse.urlparse(self.relay_url)
        if parsed.scheme not in {"http", "ws"}:
            raise ToolError("Mira MCP MVP only supports http:// or ws:// relay URLs")
        host = parsed.hostname
        if not host:
            raise ToolError("relay URL has no host")
        port = parsed.port or (80 if parsed.scheme in {"http", "ws"} else 443)
        return host, port, path


class BrowserWebSocket:
    def __init__(self, relay: RelayHttpClient) -> None:
        self.relay = relay
        self.socket: socket.socket | None = None
        self.lock = threading.Lock()

    def connect(self) -> None:
        host, port, path = self.relay.websocket_target("/ws/browser")
        sock = socket.create_connection((host, port), timeout=8.0)
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

    def wait_for_text(self, pattern: str, timeout: float) -> str:
        deadline = time.monotonic() + timeout
        with self.lock:
            while True:
                text = bytes(self.buffer).decode("utf-8", errors="replace")
                if pattern in text:
                    return text
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise ToolError(f"timeout waiting for marker: {pattern}")
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
            "mira_open_terminal": self.tool_open_terminal,
            "mira_run_command": self.tool_run_command,
            "mira_collect_snapshot": self.tool_collect_snapshot,
            "mira_send_input": self.tool_send_input,
            "mira_read_output": self.tool_read_output,
            "mira_close_terminal": self.tool_close_terminal,
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
            "serverInfo": {"name": SERVER_NAME, "title": "Mira Remote Android Terminal", "version": SERVER_VERSION},
            "instructions": (
                "Use Mira tools to discover Android devices, open an on-demand PTY session, run short diagnostic commands, "
                "read terminal output, and close the session. Prefer mira_run_command for non-interactive analysis. "
                "Start with mira_discover_devices, then inspect id, uname, getprop, mountinfo, processes, network state, and toolbox metadata. "
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
                "description": "Scan LAN through Mira Relay and return devices that are running Discovery Service.",
                "inputSchema": {
                    "type": "object",
                    "properties": {"broadcastTarget": {"type": "string", "description": "Broadcast or device IP target. Defaults to configured target."}},
                },
            },
            {
                "name": "mira_list_devices",
                "title": "List known Mira devices",
                "description": "List devices already known by Mira Relay. Set refresh=true to scan first.",
                "inputSchema": {
                    "type": "object",
                    "properties": {"refresh": {"type": "boolean"}, "broadcastTarget": {"type": "string"}},
                },
            },
            {
                "name": "mira_open_terminal",
                "title": "Open Android PTY session",
                "description": "Open an on-demand terminal session on a selected device and attach as a browser client.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "installId": {"type": "string", "description": "Device installId. If omitted and one device exists, it is selected."},
                        "cols": {"type": "integer", "default": 120},
                        "rows": {"type": "integer", "default": 36},
                    },
                },
            },
            {
                "name": "mira_run_command",
                "title": "Run command in Android PTY",
                "description": "Run a command in a persistent Android terminal session and wait for a marker with exit status.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "command": {"type": "string"},
                        "sessionId": {"type": "string"},
                        "installId": {"type": "string"},
                        "timeoutSeconds": {"type": "number", "default": 10},
                        "cols": {"type": "integer", "default": 120},
                        "rows": {"type": "integer", "default": 36},
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
        ]

    def tool_discover_devices(self, arguments: dict[str, Any]) -> dict[str, Any]:
        target = str(arguments.get("broadcastTarget") or self.broadcast_target)
        data = self.relay.request("/api/discover", {"broadcastTarget": target}, timeout=10.0)
        return {"relayUrl": self.relay.relay_url, "broadcastTarget": target, "devices": data.get("devices", [])}

    def tool_list_devices(self, arguments: dict[str, Any]) -> dict[str, Any]:
        if bool(arguments.get("refresh")):
            return self.tool_discover_devices(arguments)
        data = self.relay.request("/api/devices", timeout=10.0)
        return {"relayUrl": self.relay.relay_url, "devices": data.get("devices", [])}

    def tool_open_terminal(self, arguments: dict[str, Any]) -> dict[str, Any]:
        install_id = self.resolve_install_id(arguments.get("installId"))
        cols = int(arguments.get("cols") or 120)
        rows = int(arguments.get("rows") or 36)
        opened = self.relay.request("/api/open", {"installId": install_id, "cols": cols, "rows": rows}, timeout=10.0)
        session_id = str(opened.get("sessionId") or "")
        if not session_id:
            raise ToolError("relay did not return sessionId")
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
        return {"sessionId": session_id, "installId": install_id, "status": session.status, "cols": cols, "rows": rows}

    def tool_run_command(self, arguments: dict[str, Any]) -> dict[str, Any]:
        command = str(arguments.get("command") or "")
        if not command.strip():
            raise ToolError("command is required")
        timeout = float(arguments.get("timeoutSeconds") or 10.0)
        session_id = str(arguments.get("sessionId") or "")
        opened = False
        if not session_id:
            opened_data = self.tool_open_terminal(arguments)
            session_id = str(opened_data["sessionId"])
            opened = True
        session = self.get_session(session_id)
        marker = f"__MIRA_MCP_DONE_{uuid.uuid4().hex}__"
        before = len(session.buffer)
        session.send_input(command.rstrip("\n") + "\nprintf '\\n%s:%s\\n' " + shlex.quote(marker) + " \"$?\"\n")
        transcript = session.wait_for_text(marker + ":", timeout)
        new_text = transcript[before:]
        match = re.search(re.escape(marker) + r":(\d+)", new_text)
        exit_code = int(match.group(1)) if match else None
        cleaned = re.sub(r"\r?\n?" + re.escape(marker) + r":\d+\r?\n?", "\n", new_text)
        return {"sessionId": session_id, "openedSession": opened, "exitCode": exit_code, "output": cleaned, "status": session.status}

    def tool_collect_snapshot(self, arguments: dict[str, Any]) -> dict[str, Any]:
        command = "\n".join(
            [
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
        )
        call_args = dict(arguments)
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

    def resolve_install_id(self, value: Any) -> str:
        install_id = str(value or "")
        if install_id:
            return install_id
        devices = self.relay.request("/api/devices", timeout=10.0).get("devices", [])
        if not devices:
            devices = self.tool_discover_devices({}).get("devices", [])
        if len(devices) != 1:
            raise ToolError(f"installId required, known device count={len(devices)}")
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
            {"uri": "mira://sessions", "name": "Mira active MCP sessions", "mimeType": "application/json"},
            {"uri": "mira://relay", "name": "Mira relay configuration", "mimeType": "application/json"},
        ]

    def read_resource(self, params: dict[str, Any]) -> dict[str, Any]:
        uri = str(params.get("uri") or "")
        if uri == "mira://analysis-guide":
            return {"contents": [{"uri": uri, "mimeType": "text/markdown", "text": ANALYSIS_GUIDE}]}
        if uri == "mira://magisk-app-shell-context":
            return {"contents": [{"uri": uri, "mimeType": "text/markdown", "text": MAGISK_CONTEXT}]}
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
        ]

    def get_prompt(self, params: dict[str, Any]) -> dict[str, Any]:
        name = str(params.get("name") or "")
        if name not in {"mira_android_triage", "mira_magisk_risk_review"}:
            raise ToolError(f"unknown prompt: {name}")
        arguments = params.get("arguments") or {}
        install_id = str(arguments.get("installId") or "") if isinstance(arguments, dict) else ""
        suffix = f" Target installId: {install_id}." if install_id else " If exactly one device is known, select it automatically."
        prompt_text = ANALYSIS_PROMPT if name == "mira_android_triage" else RISK_PROMPT
        return {
            "description": "Mira Android terminal triage workflow" if name == "mira_android_triage" else "Mira Magisk app-shell risk review workflow",
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

1. 先使用 `mira_discover_devices` 获取设备, 再使用 `mira_open_terminal` 或 `mira_run_command` 建立真实 PTY 会话。
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


ANALYSIS_PROMPT = """请通过 Mira MCP 工具对 Android 设备做一次最小终端态势分析。

步骤:
1. 调用 `mira_discover_devices` 或 `mira_list_devices` 找到目标设备。
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
