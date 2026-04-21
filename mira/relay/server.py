"""Mira 按需远程终端 relay 服务端。"""

from __future__ import annotations

import argparse
import asyncio
import base64
import json
import mimetypes
import posixpath
import socket
import time
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
from urllib.parse import unquote, urlparse

from mira.bridge.websocket import (
    WebSocketClosed,
    handshake_response,
    is_upgrade_request,
    read_frame,
    send_frame,
)

ROOT_DIR = Path(__file__).resolve().parents[2]
CONSOLE_OUT_DIR = ROOT_DIR / "apps" / "console" / "out"
RING_LIMIT = 1024 * 1024
PROTOCOL_VERSION = 1


@dataclass(eq=False)
class BrowserClient:
    writer: asyncio.StreamWriter
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)


@dataclass
class DeviceRecord:
    install_id: str
    data: dict[str, Any]
    address: str
    last_seen: float
    control_writer: asyncio.StreamWriter | None = None
    control_lock: asyncio.Lock = field(default_factory=asyncio.Lock)


@dataclass
class RelaySession:
    session_id: str
    install_id: str
    device_writer: asyncio.StreamWriter | None = None
    device_lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    browsers: set[BrowserClient] = field(default_factory=set)
    ring: bytearray = field(default_factory=bytearray)
    active: bool = True
    pending_resize: dict[str, Any] | None = None

    def append_output(self, chunk: bytes) -> None:
        self.ring.extend(chunk)
        if len(self.ring) > RING_LIMIT:
            del self.ring[: len(self.ring) - RING_LIMIT]


class RelayState:
    def __init__(self, discovery_port: int, advertise_url: str) -> None:
        self.discovery_port = discovery_port
        self.advertise_url = advertise_url.rstrip("/")
        self.devices: dict[str, DeviceRecord] = {}
        self.sessions: dict[str, RelaySession] = {}
        self.lock = asyncio.Lock()

    def server_ws_url(self) -> str:
        parsed = urlparse(self.advertise_url)
        scheme = "wss" if parsed.scheme == "https" else "ws"
        return f"{scheme}://{parsed.netloc}/ws/device"


async def read_http_request(reader: asyncio.StreamReader) -> tuple[str, str, str, dict[str, str], bytes]:
    raw = await reader.readuntil(b"\r\n\r\n")
    text = raw.decode("iso-8859-1")
    lines = text.split("\r\n")
    method, target, version = lines[0].split(" ", 2)
    headers: dict[str, str] = {}
    for line in lines[1:]:
        if not line or ":" not in line:
            continue
        key, value = line.split(":", 1)
        headers[key.strip().lower()] = value.strip()
    length = int(headers.get("content-length", "0") or "0")
    body = await reader.readexactly(length) if length else b""
    return method, target, version, headers, body


def http_response(status: str, body: bytes, content_type: str = "application/json; charset=utf-8") -> bytes:
    headers = (
        f"HTTP/1.1 {status}\r\n"
        f"Content-Length: {len(body)}\r\n"
        f"Content-Type: {content_type}\r\n"
        "Cache-Control: no-store\r\n"
        "Connection: close\r\n\r\n"
    )
    return headers.encode("utf-8") + body


def json_response(status: str, data: dict[str, Any]) -> bytes:
    return http_response(status, json.dumps(data, ensure_ascii=False).encode("utf-8"))


def parse_json_body(body: bytes) -> dict[str, Any]:
    if not body:
        return {}
    data = json.loads(body.decode("utf-8"))
    if not isinstance(data, dict):
        raise ValueError("JSON body must be an object")
    return data


def file_response(path: Path) -> bytes:
    content_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    if content_type.startswith("text/") or path.suffix in {".js", ".css", ".svg"}:
        content_type += "; charset=utf-8"
    return http_response("200 OK", path.read_bytes(), content_type)


def console_static_response(path: str) -> bytes | None:
    if not CONSOLE_OUT_DIR.is_dir():
        return None
    if path in {"/api", "/ws"} or path.startswith(("/api/", "/ws/")):
        return None

    normalized = posixpath.normpath(unquote(path)).lstrip("/")
    root = CONSOLE_OUT_DIR.resolve()
    candidates: list[Path] = []
    if normalized in {"", "."}:
        candidates.append(root / "index.html")
    else:
        base = (root / normalized).resolve()
        candidates.extend(
            [
                base,
                root / f"{normalized}.html",
                base / "index.html",
            ]
        )
        if not Path(normalized).suffix:
            candidates.append(root / "index.html")

    for candidate in candidates:
        try:
            candidate.relative_to(root)
        except ValueError:
            continue
        if candidate.is_file():
            return file_response(candidate)
    return None


def static_response(path: str) -> bytes | None:
    response = console_static_response(path)
    if response is not None:
        return response
    if path == "/":
        message = (
            "Mira console is not built. Run `npm --prefix apps/console install` "
            "and `npm --prefix apps/console run build`, then restart relay.\n"
        )
        return http_response("503 Service Unavailable", message.encode("utf-8"), "text/plain; charset=utf-8")
    return None


def scan_lan_blocking(server_url: str, target: str, discovery_port: int, timeout: float) -> list[dict[str, Any]]:
    payload = json.dumps(
        {
            "type": "mira.discover",
            "protocol": PROTOCOL_VERSION,
            "serverUrl": server_url,
            "nonce": uuid.uuid4().hex,
        }
    ).encode("utf-8")
    results: dict[str, dict[str, Any]] = {}
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.settimeout(0.2)
        sock.bind(("", 0))
        sock.sendto(payload, (target, discovery_port))
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                data, address = sock.recvfrom(65535)
            except socket.timeout:
                continue
            try:
                message = json.loads(data.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                continue
            if message.get("type") != "mira.device" or not message.get("installId"):
                continue
            message["address"] = address[0]
            results[str(message["installId"])] = message
    return list(results.values())


async def send_json(writer: asyncio.StreamWriter, lock: asyncio.Lock, message: dict[str, Any]) -> None:
    await send_frame(writer, json.dumps(message, ensure_ascii=False).encode("utf-8"), opcode=0x1, lock=lock)


async def broadcast_session(session: RelaySession, message: dict[str, Any]) -> None:
    dead: list[BrowserClient] = []
    for browser in list(session.browsers):
        try:
            await send_json(browser.writer, browser.lock, message)
        except Exception:
            dead.append(browser)
    for browser in dead:
        session.browsers.discard(browser)


def device_payload(record: DeviceRecord) -> dict[str, Any]:
    data = dict(record.data)
    if record.address:
        data["address"] = record.address
    if data.get("transport") == "control" and (record.control_writer is None or record.control_writer.is_closing()):
        data["state"] = "offline"
    return data


async def api_discover(state: RelayState, body: dict[str, Any]) -> bytes:
    target = str(body.get("broadcastTarget") or "255.255.255.255")
    devices = await asyncio.to_thread(scan_lan_blocking, state.advertise_url, target, state.discovery_port, 1.2)
    now = time.time()
    async with state.lock:
        for device in devices:
            install_id = str(device["installId"])
            record = state.devices.get(install_id)
            if record is None:
                state.devices[install_id] = DeviceRecord(install_id, device, str(device.get("address", "")), now)
            else:
                record.data.update(device)
                record.address = str(device.get("address", ""))
                record.last_seen = now
        return json_response("200 OK", {"devices": [device_payload(record) for record in state.devices.values()]})


async def api_devices(state: RelayState) -> bytes:
    async with state.lock:
        return json_response("200 OK", {"devices": [device_payload(record) for record in state.devices.values()]})


def post_json(url: str, payload: dict[str, Any], timeout: float = 5.0) -> dict[str, Any]:
    data = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(url, data=data, method="POST", headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        body = response.read()
    if not body:
        return {}
    return json.loads(body.decode("utf-8"))


async def api_open(state: RelayState, body: dict[str, Any]) -> bytes:
    install_id = str(body.get("installId") or "")
    async with state.lock:
        record = state.devices.get(install_id)
        if not record:
            return json_response("404 Not Found", {"error": "device not found"})
        for session in state.sessions.values():
            if session.install_id == install_id and session.active:
                return json_response("409 Conflict", {"error": "device already has active session", "sessionId": session.session_id})
        session_id = str(uuid.uuid4())
        state.sessions[session_id] = RelaySession(session_id=session_id, install_id=install_id)
        wake_url = str(record.data.get("wakeUrl") or "")
        control_writer = record.control_writer
        control_lock = record.control_lock
        record.data["state"] = "opening"
    payload = {
        "type": "session.open",
        "protocol": PROTOCOL_VERSION,
        "installId": install_id,
        "sessionId": session_id,
        "serverWs": state.server_ws_url(),
        "cols": int(body.get("cols") or 120),
        "rows": int(body.get("rows") or 36),
    }
    if control_writer is not None and not control_writer.is_closing():
        try:
            await send_json(control_writer, control_lock, payload)
        except Exception as exc:  # noqa: BLE001
            async with state.lock:
                state.sessions.pop(session_id, None)
                if record := state.devices.get(install_id):
                    record.data["state"] = "idle"
            return json_response("502 Bad Gateway", {"error": f"control wake failed: {exc}"})
    elif wake_url:
        try:
            await asyncio.to_thread(post_json, wake_url, payload)
        except (urllib.error.URLError, TimeoutError, OSError, json.JSONDecodeError) as exc:
            async with state.lock:
                state.sessions.pop(session_id, None)
                if record := state.devices.get(install_id):
                    record.data["state"] = "idle"
            return json_response("502 Bad Gateway", {"error": f"wake failed: {exc}"})
    else:
        async with state.lock:
            state.sessions.pop(session_id, None)
            if record := state.devices.get(install_id):
                record.data["state"] = "offline"
        return json_response("409 Conflict", {"error": "device control channel is not connected"})
    print(f"Opened session {session_id} for {install_id}", flush=True)
    return json_response("200 OK", {"sessionId": session_id})


async def close_session(state: RelayState, session_id: str) -> None:
    async with state.lock:
        session = state.sessions.get(session_id)
        if not session:
            return
        session.active = False
        record = state.devices.get(session.install_id)
        control_writer = record.control_writer if record else None
        control_lock = record.control_lock if record else None
        if record:
            record.data["state"] = "idle"
    message = {"type": "session.close", "sessionId": session_id}
    if session.device_writer is not None:
        try:
            await send_json(session.device_writer, session.device_lock, message)
        except Exception:
            pass
    elif control_writer is not None and control_lock is not None and not control_writer.is_closing():
        try:
            await send_json(control_writer, control_lock, message)
        except Exception:
            pass
    await broadcast_session(session, message)


async def api_close(state: RelayState, body: dict[str, Any]) -> bytes:
    await close_session(state, str(body.get("sessionId") or ""))
    return json_response("200 OK", {"ok": True})


async def handle_device_ws(state: RelayState, reader: asyncio.StreamReader, writer: asyncio.StreamWriter, headers: dict[str, str]) -> None:
    peer = writer.get_extra_info("peername")
    print(f"Device websocket from {peer}", flush=True)
    writer.write(handshake_response(headers))
    await writer.drain()
    session: RelaySession | None = None
    try:
        frame = await read_frame(reader)
        attach = json.loads(frame.payload.decode("utf-8"))
        if attach.get("type") != "device.attach":
            await send_json(writer, asyncio.Lock(), {"type": "error", "error": "invalid device attach"})
            print(f"Invalid device attach from {peer}: {attach}", flush=True)
            return
        session_id = str(attach.get("sessionId") or "")
        install_id = str(attach.get("installId") or "")
        async with state.lock:
            session = state.sessions.get(session_id)
            if not session or session.install_id != install_id:
                await send_json(writer, asyncio.Lock(), {"type": "error", "error": "unknown session"})
                print(f"Unknown device session from {peer}: session={session_id} installId={install_id}", flush=True)
                return
            session.device_writer = writer
            session.device_lock = asyncio.Lock()
            pending_resize = session.pending_resize
            session.pending_resize = None
            if record := state.devices.get(install_id):
                record.data["state"] = "active"
                record.last_seen = time.time()
        print(f"Device attached session={session_id} installId={install_id}", flush=True)
        if pending_resize is not None:
            await send_json(writer, session.device_lock, pending_resize)
        await broadcast_session(session, {"type": "session.status", "sessionId": session_id, "state": "active"})
        while True:
            frame = await read_frame(reader)
            if frame.is_close:
                break
            if frame.is_ping:
                await send_frame(writer, frame.payload, opcode=0xA, lock=session.device_lock)
                continue
            if not frame.is_text:
                continue
            try:
                message = json.loads(frame.payload.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                continue
            if message.get("type") == "terminal.output" and message.get("sessionId") == session.session_id:
                try:
                    chunk = base64.b64decode(str(message.get("dataBase64") or ""))
                except Exception:
                    chunk = b""
                session.append_output(chunk)
                await broadcast_session(session, message)
            elif message.get("type") == "session.close":
                await close_session(state, session.session_id)
                break
    except (WebSocketClosed, asyncio.IncompleteReadError, ConnectionResetError, BrokenPipeError):
        pass
    finally:
        if session is not None:
            if session.device_writer is writer:
                session.device_writer = None
                session.active = False
                async with state.lock:
                    if record := state.devices.get(session.install_id):
                        record.data["state"] = "idle"
                await broadcast_session(session, {"type": "session.status", "sessionId": session.session_id, "state": "device disconnected"})
        writer.close()
        await writer.wait_closed()


async def handle_control_ws(state: RelayState, reader: asyncio.StreamReader, writer: asyncio.StreamWriter, headers: dict[str, str]) -> None:
    peer = writer.get_extra_info("peername")
    print(f"Control websocket from {peer}", flush=True)
    writer.write(handshake_response(headers))
    await writer.drain()
    lock = asyncio.Lock()
    install_id = ""
    try:
        frame = await read_frame(reader)
        register = json.loads(frame.payload.decode("utf-8"))
        if register.get("type") != "device.register":
            await send_json(writer, lock, {"type": "error", "error": "invalid device register"})
            return
        install_id = str(register.get("installId") or "")
        if not install_id:
            await send_json(writer, lock, {"type": "error", "error": "missing installId"})
            return
        address = peer[0] if isinstance(peer, tuple) and peer else ""
        data = dict(register)
        data["type"] = "mira.device"
        data["transport"] = "control"
        data["address"] = address
        data["state"] = "idle"
        data.pop("wakeUrl", None)
        async with state.lock:
            record = state.devices.get(install_id)
            if record is None:
                record = DeviceRecord(install_id, data, address, time.time())
                state.devices[install_id] = record
            else:
                record.data.update(data)
                record.address = address
                record.last_seen = time.time()
            record.control_writer = writer
            record.control_lock = lock
        await send_json(writer, lock, {"type": "control.ready", "protocol": PROTOCOL_VERSION, "installId": install_id})
        print(f"Device registered installId={install_id} address={address}", flush=True)
        while True:
            frame = await read_frame(reader)
            if frame.is_close:
                break
            if frame.is_ping:
                await send_frame(writer, frame.payload, opcode=0xA, lock=lock)
                continue
            if not frame.is_text:
                continue
            try:
                message = json.loads(frame.payload.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                continue
            if message.get("type") == "device.status":
                async with state.lock:
                    if record := state.devices.get(install_id):
                        record.data["state"] = str(message.get("state") or record.data.get("state") or "idle")
                        record.last_seen = time.time()
    except (WebSocketClosed, asyncio.IncompleteReadError, ConnectionResetError, BrokenPipeError):
        pass
    finally:
        async with state.lock:
            if install_id and (record := state.devices.get(install_id)) and record.control_writer is writer:
                record.control_writer = None
                record.data["state"] = "offline"
                record.last_seen = time.time()
        writer.close()
        await writer.wait_closed()


async def handle_browser_ws(state: RelayState, reader: asyncio.StreamReader, writer: asyncio.StreamWriter, headers: dict[str, str]) -> None:
    writer.write(handshake_response(headers))
    await writer.drain()
    client = BrowserClient(writer=writer)
    session: RelaySession | None = None
    try:
        frame = await read_frame(reader)
        attach = json.loads(frame.payload.decode("utf-8"))
        if attach.get("type") != "browser.attach":
            await send_json(writer, client.lock, {"type": "error", "error": "invalid browser attach"})
            return
        session_id = str(attach.get("sessionId") or "")
        install_id = str(attach.get("installId") or "")
        async with state.lock:
            session = state.sessions.get(session_id)
            if not session or session.install_id != install_id:
                await send_json(writer, client.lock, {"type": "error", "error": "unknown session"})
                return
            session.browsers.add(client)
            replay = bytes(session.ring)
        if replay:
            await send_json(
                writer,
                client.lock,
                {"type": "terminal.output", "sessionId": session.session_id, "dataBase64": base64.b64encode(replay).decode("ascii")},
            )
        await send_json(writer, client.lock, {"type": "session.status", "sessionId": session.session_id, "state": "active" if session.device_writer else "waiting for device"})
        while True:
            frame = await read_frame(reader)
            if frame.is_close:
                break
            if frame.is_ping:
                await send_frame(writer, frame.payload, opcode=0xA, lock=client.lock)
                continue
            if not frame.is_text:
                continue
            try:
                message = json.loads(frame.payload.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                continue
            message_type = message.get("type")
            if message_type == "session.close":
                await close_session(state, session.session_id)
                break
            if message.get("sessionId") != session.session_id:
                continue
            if message_type in {"terminal.input", "terminal.resize"}:
                if session.device_writer is None:
                    if message_type == "terminal.resize":
                        session.pending_resize = message
                        continue
                    await send_json(writer, client.lock, {"type": "error", "error": "device is not connected"})
                    continue
                await send_json(session.device_writer, session.device_lock, message)
    except (WebSocketClosed, asyncio.IncompleteReadError, ConnectionResetError, BrokenPipeError):
        pass
    finally:
        if session is not None:
            session.browsers.discard(client)
        writer.close()
        await writer.wait_closed()


async def handle_client(state: RelayState, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    try:
        method, target, _version, headers, body = await read_http_request(reader)
        parsed = urlparse(target)
        path = parsed.path
        if path == "/ws/device" and is_upgrade_request(method, headers):
            await handle_device_ws(state, reader, writer, headers)
            return
        if path == "/ws/control" and is_upgrade_request(method, headers):
            await handle_control_ws(state, reader, writer, headers)
            return
        if path == "/ws/browser" and is_upgrade_request(method, headers):
            await handle_browser_ws(state, reader, writer, headers)
            return
        response = static_response(path)
        if response is not None and method.upper() == "GET":
            writer.write(response)
            await writer.drain()
            return
        if path == "/api/devices" and method.upper() == "GET":
            writer.write(await api_devices(state))
        elif path == "/api/discover" and method.upper() == "POST":
            writer.write(await api_discover(state, parse_json_body(body)))
        elif path == "/api/open" and method.upper() == "POST":
            writer.write(await api_open(state, parse_json_body(body)))
        elif path == "/api/close" and method.upper() == "POST":
            writer.write(await api_close(state, parse_json_body(body)))
        else:
            writer.write(json_response("404 Not Found", {"error": "not found"}))
        await writer.drain()
    except (asyncio.IncompleteReadError, ConnectionResetError, BrokenPipeError):
        pass
    except Exception as exc:  # noqa: BLE001
        writer.write(json_response("500 Internal Server Error", {"error": str(exc)}))
        await writer.drain()
    finally:
        if not writer.is_closing():
            writer.close()
            await writer.wait_closed()


async def run_server(host: str, port: int, discovery_port: int, advertise_url: str) -> None:
    state = RelayState(discovery_port=discovery_port, advertise_url=advertise_url or f"http://{host}:{port}")
    server = await asyncio.start_server(lambda r, w: handle_client(state, r, w), host, port)
    addresses = ", ".join(str(sock.getsockname()) for sock in server.sockets or [])
    print(f"Mira Relay listening on {addresses}", flush=True)
    print(f"Open {state.advertise_url}", flush=True)
    print(f"Android Relay URL {state.advertise_url}", flush=True)
    print(f"Control WebSocket {state.server_ws_url().replace('/ws/device', '/ws/control')}", flush=True)
    print(f"Legacy discovery UDP port {discovery_port}", flush=True)
    async with server:
        await server.serve_forever()


def main() -> None:
    parser = argparse.ArgumentParser(description="Run Mira Relay Server")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--discovery-port", type=int, default=8766)
    parser.add_argument("--advertise-url", default="")
    args = parser.parse_args()
    asyncio.run(run_server(args.host, args.port, args.discovery_port, args.advertise_url))


if __name__ == "__main__":
    main()
