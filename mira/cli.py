"""Mira command line client.

This client talks to Mira Relay directly through HTTP and WebSocket.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import select
import shlex
import shutil
import socket
import ssl
import struct
import sys
import termios
import threading
import time
import tty
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass, field
from typing import Any

MAX_BUFFER_BYTES = 1024 * 1024


class CliError(Exception):
    """Mira CLI error."""


class RelayHttpClient:
    def __init__(self, relay_url: str) -> None:
        self.relay_url = relay_url.rstrip("/")

    def request(self, path: str, body: dict[str, Any] | None = None, timeout: float = 10.0) -> dict[str, Any]:
        payload = None if body is None else json.dumps(body).encode("utf-8")
        headers = {"Content-Type": "application/json"} if body is not None else {}
        request = urllib.request.Request(
            self.relay_url + path,
            data=payload,
            method="POST" if body is not None else "GET",
            headers=headers,
        )
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                raw = response.read()
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise CliError(f"relay HTTP {exc.code}: {detail}") from exc
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            raise CliError(f"relay request failed: {exc}") from exc
        if not raw:
            return {}
        try:
            value = json.loads(raw.decode("utf-8"))
        except json.JSONDecodeError as exc:
            raise CliError(f"relay returned invalid JSON: {raw[:200]!r}") from exc
        if not isinstance(value, dict):
            raise CliError("relay returned non-object JSON")
        return value

    def websocket_target(self, path: str) -> tuple[str, int, str, bool]:
        parsed = urllib.parse.urlparse(self.relay_url)
        if parsed.scheme not in {"http", "https", "ws", "wss"}:
            raise CliError("relay URL must start with http://, https://, ws:// or wss://")
        host = parsed.hostname
        if not host:
            raise CliError("relay URL has no host")
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
            raise CliError(header.decode("iso-8859-1", errors="replace"))
        sock.settimeout(None)
        self.socket = sock

    def send_json(self, message: dict[str, Any]) -> None:
        self.send_frame(json.dumps(message, ensure_ascii=False).encode("utf-8"), opcode=0x1)

    def send_frame(self, payload: bytes, opcode: int = 0x1) -> None:
        if self.socket is None:
            raise CliError("websocket is not connected")
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
            raise CliError("websocket is not connected")
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
            raise CliError("websocket is not connected")
        data = bytearray()
        while len(data) < length:
            chunk = self.socket.recv(length - len(data))
            if not chunk:
                raise CliError("websocket closed")
            data.extend(chunk)
        return bytes(data)

    @staticmethod
    def _read_http_header(sock: socket.socket) -> bytes:
        buffer = bytearray()
        while b"\r\n\r\n" not in buffer:
            chunk = sock.recv(1)
            if not chunk:
                raise CliError("websocket handshake closed")
            buffer.extend(chunk)
            if len(buffer) > 65536:
                raise CliError("websocket header too large")
        return bytes(buffer)


@dataclass
class TerminalSession:
    relay: RelayHttpClient
    install_id: str
    session_id: str
    ws: BrowserWebSocket
    buffer: bytearray = field(default_factory=bytearray)
    lock: threading.Condition = field(default_factory=threading.Condition)
    active: bool = True
    status: str = "opening"
    read_thread: threading.Thread | None = None

    def start_reader(self, echo: bool = False) -> None:
        self.read_thread = threading.Thread(target=self._reader_loop, args=(echo,), name=f"MiraCliReader-{self.session_id[:8]}", daemon=True)
        self.read_thread.start()

    def attach(self, cols: int, rows: int) -> None:
        self.ws.send_json({"type": "browser.attach", "protocol": 1, "installId": self.install_id, "sessionId": self.session_id})
        self.resize(cols, rows)

    def resize(self, cols: int, rows: int) -> None:
        self.ws.send_json({"type": "terminal.resize", "sessionId": self.session_id, "cols": cols, "rows": rows})

    def send_bytes(self, data: bytes) -> None:
        encoded = base64.b64encode(data).decode("ascii")
        self.ws.send_json({"type": "terminal.input", "sessionId": self.session_id, "dataBase64": encoded})

    def send_text(self, text: str) -> None:
        self.send_bytes(text.encode("utf-8"))

    def wait_for_text(self, pattern: str, timeout: float) -> str:
        deadline = time.monotonic() + timeout
        with self.lock:
            while True:
                text = bytes(self.buffer).decode("utf-8", errors="replace")
                if pattern in text:
                    return text
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise CliError(f"timeout waiting for marker: {pattern}")
                self.lock.wait(min(remaining, 0.25))

    def close(self) -> None:
        self.active = False
        try:
            self.relay.request("/api/close", {"sessionId": self.session_id}, timeout=5.0)
        except CliError:
            pass
        self.ws.close()
        with self.lock:
            self.lock.notify_all()

    def _append_output(self, chunk: bytes, echo: bool) -> None:
        if echo:
            sys.stdout.buffer.write(chunk)
            sys.stdout.buffer.flush()
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

    def _reader_loop(self, echo: bool) -> None:
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
                    self._append_output(base64.b64decode(str(message.get("dataBase64") or "")), echo)
                elif message_type == "session.status":
                    self._set_status(str(message.get("state") or "unknown"))
                elif message_type == "session.close":
                    self._set_status("session closed")
                    return
                elif message_type == "error":
                    self._append_output(("\n[Mira relay error] " + str(message.get("error") or "unknown") + "\n").encode("utf-8"), echo)
        except Exception as exc:  # noqa: BLE001
            self._set_status(f"reader stopped: {exc}")


def terminal_size() -> tuple[int, int]:
    size = shutil.get_terminal_size((120, 36))
    return size.columns, size.lines


def resolve_install_id(relay: RelayHttpClient, install_id: str | None) -> str:
    if install_id:
        return install_id
    devices = relay.request("/api/devices", timeout=10.0).get("devices", [])
    if len(devices) != 1:
        raise CliError(f"installId required, device count={len(devices)}")
    value = str(devices[0].get("installId") or "")
    if not value:
        raise CliError("device has no installId")
    return value


def open_session(relay: RelayHttpClient, install_id: str | None, cols: int, rows: int) -> TerminalSession:
    resolved = resolve_install_id(relay, install_id)
    opened = relay.request("/api/open", {"installId": resolved, "cols": cols, "rows": rows}, timeout=10.0)
    session_id = str(opened.get("sessionId") or "")
    if not session_id:
        raise CliError("relay did not return sessionId")
    ws = BrowserWebSocket(relay)
    try:
        ws.connect()
        session = TerminalSession(relay=relay, install_id=resolved, session_id=session_id, ws=ws)
        session.attach(cols, rows)
        return session
    except Exception:
        ws.close()
        raise


def command_devices(relay: RelayHttpClient, as_json: bool) -> int:
    devices = relay.request("/api/devices", timeout=10.0).get("devices", [])
    if as_json:
        print(json.dumps({"devices": devices}, ensure_ascii=False, indent=2))
        return 0
    for device in devices:
        install_id = str(device.get("installId") or "")
        name = str(device.get("deviceName") or device.get("model") or "Mira Device")
        state = str(device.get("state") or "unknown")
        arch = str(device.get("arch") or "unknown")
        address = str(device.get("address") or "unknown")
        print(f"{install_id[:8]}\t{state}\t{name}\t{arch}\t{address}")
    return 0


def command_run(relay: RelayHttpClient, args: argparse.Namespace) -> int:
    session = open_session(relay, args.install_id, args.cols, args.rows)
    session.start_reader(echo=False)
    marker = f"__MIRA_CLI_DONE_{uuid.uuid4().hex}__"
    before = len(session.buffer)
    session.send_text(args.command.rstrip("\n") + "\nprintf '\\n%s:%s\\n' " + shlex.quote(marker) + " \"$?\"\n")
    transcript = session.wait_for_text(marker + ":", args.timeout)
    new_text = transcript[before:]
    match = re.search(re.escape(marker) + r":(\d+)", new_text)
    exit_code = int(match.group(1)) if match else 0
    cleaned = re.sub(r"\r?\n?" + re.escape(marker) + r":\d+\r?\n?", "\n", new_text)
    if cleaned:
        sys.stdout.write(cleaned)
        if not cleaned.endswith("\n"):
            sys.stdout.write("\n")
    session.close()
    return exit_code


def command_shell(relay: RelayHttpClient, args: argparse.Namespace) -> int:
    cols, rows = terminal_size()
    session = open_session(relay, args.install_id, cols, rows)
    session.start_reader(echo=True)
    time.sleep(0.15)

    stdin_fd = sys.stdin.fileno()
    old_attrs = termios.tcgetattr(stdin_fd) if sys.stdin.isatty() else None
    if old_attrs is not None:
        tty.setraw(stdin_fd)
    try:
        while session.active:
            ready, _, _ = select.select([stdin_fd], [], [], 0.1)
            if not ready:
                continue
            data = os.read(stdin_fd, 4096)
            if not data or data == b"\x1d":
                break
            session.send_bytes(data)
    finally:
        if old_attrs is not None:
            termios.tcsetattr(stdin_fd, termios.TCSADRAIN, old_attrs)
        session.close()
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Mira terminal command line")
    parser.add_argument("--relay", default=os.environ.get("MIRA_RELAY_URL", "http://127.0.0.1:8765"), help="Mira Relay URL")
    subparsers = parser.add_subparsers(dest="command_name")

    devices = subparsers.add_parser("devices", help="List connected devices")
    devices.add_argument("--relay", default=argparse.SUPPRESS, help="Mira Relay URL")
    devices.add_argument("--json", action="store_true")

    run = subparsers.add_parser("run", help="Run one command in a remote PTY")
    run.add_argument("--relay", default=argparse.SUPPRESS, help="Mira Relay URL")
    run.add_argument("command")
    run.add_argument("--install-id")
    run.add_argument("--timeout", type=float, default=10.0)
    run.add_argument("--cols", type=int, default=120)
    run.add_argument("--rows", type=int, default=36)

    shell = subparsers.add_parser("shell", help="Open an interactive remote PTY")
    shell.add_argument("--relay", default=argparse.SUPPRESS, help="Mira Relay URL")
    shell.add_argument("--install-id")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.command_name is None:
        args.command_name = "shell"
        args.install_id = None
    try:
        relay = RelayHttpClient(args.relay)
        if args.command_name == "devices":
            return command_devices(relay, bool(args.json))
        if args.command_name == "run":
            return command_run(relay, args)
        if args.command_name == "shell":
            return command_shell(relay, args)
    except (CliError, OSError, KeyboardInterrupt) as exc:
        if isinstance(exc, KeyboardInterrupt):
            sys.stderr.write("\n")
        else:
            sys.stderr.write(f"mira-cli: {exc}\n")
        return 1
    parser.print_help()
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
