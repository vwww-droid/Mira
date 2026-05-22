"""Mira 按需远程终端 relay 服务端。"""

from __future__ import annotations

import argparse
import asyncio
import base64
import json
import math
import mimetypes
import posixpath
import socket
import sys
import threading
import time
import traceback
import urllib.error
import urllib.request
import uuid
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, unquote, urlparse

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
MAX_SCREEN_FRAME_BASE64 = 2_500_000
MAX_SCREEN_FRAME_BYTES = 2_000_000
MAX_SCREEN_FRAME_DIMENSION = 8192
MAX_SCREEN_INPUT_TEXT = 20_000
SCREEN_STREAM_BOUNDARY = "mira-screen-frame"
SESSION_ATTACH_GRACE_SECONDS = 5.0
SERVER_LOG_LIMIT = 2000
SCREEN_BROADCAST_TIMEOUT_SECONDS = 0.5
_SERVER_LOG_LOCK = threading.Lock()
_SERVER_LOG_SEQ = 0
_SERVER_LOG_RING: deque[dict[str, Any]] = deque(maxlen=SERVER_LOG_LIMIT)


def relay_log(message: str) -> None:
    global _SERVER_LOG_SEQ
    text = str(message).replace("\r\n", "\n").replace("\r", "\n")
    lines = text.split("\n")
    if lines and lines[-1] == "":
        lines = lines[:-1]
    if not lines:
        lines = [""]
    now = time.time()
    with _SERVER_LOG_LOCK:
        for line in lines:
            _SERVER_LOG_SEQ += 1
            _SERVER_LOG_RING.append({"cursor": _SERVER_LOG_SEQ, "at": now, "line": line})
    stdout = getattr(sys, "__stdout__", None) or sys.stdout
    stdout.write(text if text.endswith("\n") else text + "\n")
    stdout.flush()


def relay_exc_text(exc: BaseException) -> str:
    return f"{exc.__class__.__name__}: {exc}"


def read_server_logs(cursor: int = 0, limit: int = 400) -> dict[str, Any]:
    limit = max(1, min(limit, SERVER_LOG_LIMIT))
    with _SERVER_LOG_LOCK:
        entries = list(_SERVER_LOG_RING)
    if not entries:
        return {"entries": [], "nextCursor": cursor, "reset": False}
    oldest_cursor = int(entries[0]["cursor"])
    latest_cursor = int(entries[-1]["cursor"])
    if cursor <= 0:
        selected = entries[-limit:]
        return {"entries": selected, "nextCursor": latest_cursor, "reset": False}
    if cursor < oldest_cursor:
        selected = entries[-limit:]
        return {"entries": selected, "nextCursor": latest_cursor, "reset": True}
    selected = [entry for entry in entries if int(entry["cursor"]) > cursor]
    if len(selected) > limit:
        selected = selected[-limit:]
        return {"entries": selected, "nextCursor": latest_cursor, "reset": True}
    return {"entries": selected, "nextCursor": latest_cursor, "reset": False}


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
    outline: dict[str, Any] | None = None
    outline_last_seen: float | None = None
    metrics: dict[str, Any] | None = None
    metrics_last_seen: float | None = None
    screen_frame: dict[str, Any] | None = None
    screen_frame_seen: float | None = None
    screen_last_seen: float | None = None
    screen_info: dict[str, Any] | None = None
    screen_device_writer: asyncio.StreamWriter | None = None
    screen_device_lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    screen_clients: set[BrowserClient] = field(default_factory=set)
    screen_video_packets: int = 0
    screen_video_bytes: int = 0
    screen_video_last_packet_at: float | None = None
    screen_video_last_broadcast_ms: float | None = None
    screen_video_broadcast_timeouts: int = 0
    control_writer: asyncio.StreamWriter | None = None
    control_lock: asyncio.Lock = field(default_factory=asyncio.Lock)


@dataclass
class PendingControlRequest:
    install_id: str
    future: asyncio.Future[dict[str, Any]]


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
    created_at: float = field(default_factory=time.time)

    def append_output(self, chunk: bytes) -> None:
        self.ring.extend(chunk)
        if len(self.ring) > RING_LIMIT:
            del self.ring[: len(self.ring) - RING_LIMIT]


class RelayState:
    def __init__(self, discovery_port: int, advertise_url: str, advertise_url_file: str = "") -> None:
        self.discovery_port = discovery_port
        self._advertise_url = advertise_url.rstrip("/")
        self.advertise_url_file = Path(advertise_url_file) if advertise_url_file else None
        self.devices: dict[str, DeviceRecord] = {}
        self.sessions: dict[str, RelaySession] = {}
        self.pending_control_requests: dict[str, PendingControlRequest] = {}
        self.lock = asyncio.Lock()

    @property
    def advertise_url(self) -> str:
        if self.advertise_url_file is not None:
            try:
                advertise_url = self.advertise_url_file.read_text(encoding="utf-8").strip()
            except OSError:
                advertise_url = ""
            if advertise_url:
                return advertise_url.rstrip("/")
        return self._advertise_url

    def server_ws_url(self) -> str:
        return relay_ws_url(self.advertise_url, "/ws/device")


def relay_ws_url(relay_url: str, path: str) -> str:
    parsed = urlparse(relay_url)
    scheme = "wss" if parsed.scheme in {"https", "wss"} else "ws"
    return f"{scheme}://{parsed.netloc}{path}"


def is_local_advertise_url(advertise_url: str) -> bool:
    parsed = urlparse(advertise_url)
    host = (parsed.hostname or "").lower()
    return host in {"localhost", "0.0.0.0", "127.0.0.1", "::1"} or host.startswith("127.")


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


def int_query_field(values: dict[str, list[str]], key: str, default: int, minimum: int, maximum: int) -> int:
    raw = values.get(key, [str(default)])[0]
    try:
        parsed = int(raw)
    except (TypeError, ValueError):
        return default
    return max(minimum, min(maximum, parsed))


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


def udp_bind_host_for_advertise_url(server_url: str) -> str:
    try:
        host = urlparse(server_url).hostname or ""
    except ValueError:
        return ""
    if host in {"", "0.0.0.0", "::", "localhost"} or host.startswith("127."):
        return "127.0.0.1"
    return host


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
        sock.bind((udp_bind_host_for_advertise_url(server_url), 0))
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
        except Exception as exc:
            relay_log(f"Broadcast to terminal browser failed sessionId={session.session_id} error={relay_exc_text(exc)}")
            dead.append(browser)
    for browser in dead:
        session.browsers.discard(browser)


async def broadcast_screen_clients(record: DeviceRecord, payload: dict[str, Any] | bytes, opcode: int = 0x1) -> dict[str, int]:
    dead: list[BrowserClient] = []
    sent = 0
    timeouts = 0
    if isinstance(payload, dict):
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    else:
        data = payload
    for browser in list(record.screen_clients):
        try:
            if opcode == 0x2:
                await asyncio.wait_for(
                    send_frame(browser.writer, data, opcode=opcode, lock=browser.lock),
                    timeout=SCREEN_BROADCAST_TIMEOUT_SECONDS,
                )
            else:
                await send_frame(browser.writer, data, opcode=opcode, lock=browser.lock)
            sent += 1
        except asyncio.TimeoutError:
            timeouts += 1
            relay_log(
                f"Broadcast to screen browser timed out installId={record.install_id} opcode={opcode} timeout={SCREEN_BROADCAST_TIMEOUT_SECONDS:.1f}s"
            )
            dead.append(browser)
        except Exception as exc:
            relay_log(
                f"Broadcast to screen browser failed installId={record.install_id} opcode={opcode} error={relay_exc_text(exc)}"
            )
            dead.append(browser)
    for browser in dead:
        record.screen_clients.discard(browser)
        try:
            browser.writer.close()
        except Exception as exc:
            relay_log(f"Screen browser close failed: {relay_exc_text(exc)}")
    return {"sent": sent, "timeouts": timeouts}


def screen_input_payload(body: dict[str, Any], install_id: str) -> tuple[dict[str, Any] | None, str]:
    kind = str(body.get("kind") or "").strip().lower()
    if kind not in {"tap", "text", "paste", "key", "copy", "selectall", "clear"}:
        return None, "unsupported input kind"

    payload: dict[str, Any] = {
        "type": "screen.input",
        "protocol": PROTOCOL_VERSION,
        "installId": install_id,
        "kind": kind,
    }
    request_id = str(body.get("requestId") or "").strip()
    if request_id:
        payload["requestId"] = request_id[:128]
    client_id = str(body.get("clientId") or "").strip()
    if client_id:
        payload["clientId"] = client_id[:128]

    if kind == "tap":
        x = float_field(body, "x")
        y = float_field(body, "y")
        if x is None or y is None or x < 0 or y < 0:
            return None, "invalid tap coordinates"
        payload["x"] = x
        payload["y"] = y
    elif kind in {"text", "paste"}:
        text = body.get("text")
        if not isinstance(text, str):
            return None, "missing input text"
        if len(text) > MAX_SCREEN_INPUT_TEXT:
            return None, "input text too large"
        payload["text"] = text
    elif kind == "key":
        key = str(body.get("key") or "").strip()
        if not key:
            return None, "missing key"
        if len(key) > 64:
            return None, "key too large"
        payload["key"] = key

    return payload, ""


async def send_screen_input_to_device(state: RelayState, install_id: str, payload: dict[str, Any]) -> tuple[bool, str]:
    async with state.lock:
        record = state.devices.get(install_id)
        if record is None:
            return False, "device not found"
        control_writer = record.control_writer
        control_lock = record.control_lock
    if control_writer is None or control_writer.is_closing():
        relay_log(f"Screen input rejected installId={install_id} kind={payload.get('kind')} reason=device control channel is not connected")
        return False, "device control channel is not connected"
    try:
        await send_json(control_writer, control_lock, payload)
    except Exception as exc:  # noqa: BLE001
        relay_log(f"Screen input send failed installId={install_id} kind={payload.get('kind')} error={relay_exc_text(exc)}")
        return False, f"screen input failed: {exc}"
    return True, ""


async def send_control_message_to_device(state: RelayState, install_id: str, payload: dict[str, Any]) -> tuple[bool, str]:
    async with state.lock:
        record = state.devices.get(install_id)
        if record is None:
            return False, "device not found"
        control_writer = record.control_writer
        control_lock = record.control_lock
    if control_writer is None or control_writer.is_closing():
        return False, "device control channel is not connected"
    try:
        await send_json(control_writer, control_lock, payload)
    except Exception as exc:  # noqa: BLE001
        return False, f"device control send failed: {exc}"
    return True, ""


async def send_control_request(
    state: RelayState,
    install_id: str,
    payload: dict[str, Any],
    timeout: float = 8.0,
) -> tuple[bool, str, dict[str, Any] | None]:
    request_id = str(payload.get("requestId") or "").strip()
    if not request_id:
        return False, "missing requestId", None
    loop = asyncio.get_running_loop()
    future = loop.create_future()
    async with state.lock:
        record = state.devices.get(install_id)
        if not record:
            return False, "device not found", None
        control_writer = record.control_writer
        control_lock = record.control_lock
        if control_writer is None or control_writer.is_closing():
            return False, "device control channel is not connected", None
        state.pending_control_requests[request_id] = PendingControlRequest(install_id=install_id, future=future)
    relay_log(
        f"Control request queued installId={install_id} requestId={request_id} command={payload.get('command')} timeout={timeout:.1f}s"
    )
    try:
        await send_json(control_writer, control_lock, payload)
    except Exception as exc:  # noqa: BLE001
        async with state.lock:
            state.pending_control_requests.pop(request_id, None)
        relay_log(f"Failed to send control request installId={install_id} requestId={request_id}: {exc}")
        return False, f"send control request failed: {exc}", None

    try:
        message = await asyncio.wait_for(future, timeout)
    except asyncio.TimeoutError:
        async with state.lock:
            same_install_pending = [
                item_id for item_id, item in state.pending_control_requests.items() if item.install_id == install_id
            ]
        relay_log(
            f"Control request timeout installId={install_id} requestId={request_id} timeout={timeout:.1f}s pending={same_install_pending}"
        )
        return False, f"device control request timeout after {timeout:.1f}s", None
    finally:
        async with state.lock:
            state.pending_control_requests.pop(request_id, None)
    if not isinstance(message, dict):
        return False, "invalid control response", None
    return True, "", message


def screen_info_payload(record: DeviceRecord, now: float | None = None) -> dict[str, Any]:
    if now is None:
        now = time.time()
    screen_info = dict(record.screen_info) if record.screen_info is not None else None
    latest_frame = dict(record.screen_frame) if record.screen_frame is not None else None
    if latest_frame is not None:
        latest_frame.pop("dataBase64", None)
        if record.screen_frame_seen is not None:
            latest_frame["receivedAt"] = record.screen_frame_seen
            latest_frame["ageMs"] = max(0, int((now - record.screen_frame_seen) * 1000))
    screen_age_ms = None
    if record.screen_last_seen is not None:
        screen_age_ms = max(0, int((now - record.screen_last_seen) * 1000))
    return {
        "installId": record.install_id,
        "deviceName": str(record.data.get("deviceName") or record.install_id),
        "address": record.address or str(record.data.get("address") or ""),
        "available": screen_info is not None or latest_frame is not None,
        "live": record.screen_device_writer is not None and not record.screen_device_writer.is_closing(),
        "inputAvailable": record.control_writer is not None and not record.control_writer.is_closing(),
        "decodePath": "browser-wasm",
        "screenInfo": screen_info,
        "screenLastSeen": record.screen_last_seen,
        "screenAgeMs": screen_age_ms,
        "videoPackets": record.screen_video_packets,
        "videoBytes": record.screen_video_bytes,
        "videoLastPacketAt": record.screen_video_last_packet_at,
        "videoLastBroadcastMs": record.screen_video_last_broadcast_ms,
        "videoBroadcastTimeouts": record.screen_video_broadcast_timeouts,
        "latestFrame": latest_frame,
        "viewerCount": len(record.screen_clients),
    }


def device_payload(record: DeviceRecord) -> dict[str, Any]:
    data = dict(record.data)
    if record.screen_info is not None or record.screen_frame is not None or record.screen_last_seen is not None:
        data["screen"] = screen_info_payload(record)
    if record.outline is not None:
        data["outline"] = record.outline
    if record.outline_last_seen is not None:
        data["outlineLastSeen"] = record.outline_last_seen
    if record.screen_info is not None:
        data["screenInfo"] = record.screen_info
    if record.screen_last_seen is not None:
        data["screenLastSeen"] = record.screen_last_seen
    if record.metrics is not None:
        data["metrics"] = record.metrics
    if record.metrics_last_seen is not None:
        data["metricsLastSeen"] = record.metrics_last_seen
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


async def api_server_logs(query: dict[str, list[str]]) -> bytes:
    cursor = int_query_field(query, "cursor", 0, 0, 2_000_000_000)
    limit = int_query_field(query, "limit", 300, 1, 1000)
    payload = read_server_logs(cursor=cursor, limit=limit)
    entries = payload["entries"]
    return json_response(
        "200 OK",
        {
            "entries": entries,
            "lines": [str(entry.get("line") or "") for entry in entries],
            "nextCursor": int(payload["nextCursor"]),
            "reset": bool(payload["reset"]),
        },
    )


async def api_browser_log(body: dict[str, Any]) -> bytes:
    scope = str(body.get("scope") or "browser").strip() or "browser"
    message = str(body.get("message") or "").strip() or "-"
    install_id = str(body.get("installId") or "").strip() or "-"
    details = body.get("details")
    suffix = ""
    if details not in (None, "", {}, []):
        try:
            suffix = f" details={json.dumps(details, ensure_ascii=False, separators=(',', ':'))}"
        except (TypeError, ValueError):
            suffix = f" details={details}"
    relay_log(f"Browser log installId={install_id} scope={scope} message={message}{suffix}")
    return json_response("200 OK", {"ok": True})


async def api_devices(state: RelayState) -> bytes:
    async with state.lock:
        return json_response("200 OK", {"devices": [device_payload(record) for record in state.devices.values()]})


async def api_outline(state: RelayState, body: dict[str, Any]) -> bytes:
    install_id = str(body.get("installId") or "")
    if not install_id:
        return json_response("400 Bad Request", {"error": "missing installId"})
    outline = body.get("outline")
    if not isinstance(outline, dict):
        return json_response("400 Bad Request", {"error": "invalid device outline"})
    now = time.time()
    async with state.lock:
        record = state.devices.get(install_id)
        if record is None:
            data = {
                "type": "mira.device",
                "protocol": PROTOCOL_VERSION,
                "installId": install_id,
                "deviceName": str(body.get("deviceName") or "Mira Device"),
                "state": str(body.get("state") or "idle"),
                "transport": str(body.get("transport") or "control"),
            }
            record = DeviceRecord(install_id, data, "", now)
            state.devices[install_id] = record
        else:
            if body.get("deviceName"):
                record.data["deviceName"] = str(body.get("deviceName"))
            if body.get("state"):
                record.data["state"] = str(body.get("state"))
        record.outline = outline
        record.outline_last_seen = now
        record.data["outline"] = outline
        record.data["outlineLastSeen"] = now
        record.last_seen = now
    nodes = outline.get("nodes")
    node_count = len(nodes) if isinstance(nodes, list) else outline.get("nodeCount", "unknown")
    relay_log(f"Received outline installId={install_id} nodes={node_count}")
    return json_response("200 OK", {"ok": True, "outlineLastSeen": now})


def int_field(body: dict[str, Any], name: str, minimum: int = 0, maximum: int | None = None) -> int | None:
    try:
        value = int(body.get(name))
    except (TypeError, ValueError):
        return None
    if value < minimum:
        return None
    if maximum is not None and value > maximum:
        return None
    return value


def float_field(body: dict[str, Any], name: str) -> float | None:
    try:
        value = float(body.get(name))
    except (TypeError, ValueError):
        return None
    if math.isnan(value) or math.isinf(value):
        return None
    return value


def normalize_logcat_level(raw: str) -> str | None:
    if not raw:
        return None
    level = raw.strip().upper()
    if not level:
        return None
    aliases = {
        "VERBOSE": "V",
        "DEBUG": "D",
        "INFO": "I",
        "WARN": "W",
        "ERROR": "E",
        "FATAL": "F",
        "SILENT": "S",
        "ASSERT": "A",
    }
    if level in aliases:
        return aliases[level]
    if len(level) == 1 and level in {"V", "D", "I", "W", "E", "F", "S", "A"}:
        return level
    return None


def build_logcat_args(body: dict[str, Any]) -> tuple[list[str], str | None]:
    count = int_field(body, "count", 1, 5000)
    if count is None:
        return [], "invalid logcat count"
    buffer_name = str(body.get("buffer") or "all").strip().lower()
    allowed_buffers = {"all", "main", "system", "events", "radio", "crash", "kernel"}
    if buffer_name not in allowed_buffers:
        return [], "invalid logcat buffer"
    tag = str(body.get("tag") or "").strip()
    level = normalize_logcat_level(str(body.get("level") or "").strip())
    if body.get("level") not in (None, "") and level is None:
        return [], "invalid logcat level"

    args: list[str] = ["-d", "-b", buffer_name, "-t", str(count), "-v", "time"]
    if tag:
        if level:
            args.append(f"{tag}:{level}")
        else:
            args.extend(["--tag", tag])
    elif level:
        args.append(f"*:{level}")
    return args, None


async def api_device_command(state: RelayState, body: dict[str, Any], command: str, args: list[str] | None = None, extra: dict[str, Any] | None = None) -> bytes:
    install_id = str(body.get("installId") or "").strip()
    if not install_id:
        return json_response("400 Bad Request", {"error": "missing installId"})
    request_timeout = int_field(body, "timeoutMs", 1000, 30000)
    if request_timeout is None:
        request_timeout = 15000
    request_id = uuid.uuid4().hex
    payload: dict[str, Any] = {
        "type": "device.command",
        "protocol": PROTOCOL_VERSION,
        "installId": install_id,
        "requestId": request_id,
        "command": command,
        "arguments": args or [],
        "timeoutMs": request_timeout,
    }
    if extra:
        payload.update(extra)
    request_timeout_seconds = request_timeout / 1000 + 5.0
    ok, send_error, response = await send_control_request(state, install_id, payload, request_timeout_seconds)
    if not ok:
        if send_error == "device not found":
            return json_response("404 Not Found", {"error": send_error})
        if "not connected" in send_error:
            return json_response("409 Conflict", {"error": send_error})
        if send_error.startswith("device control request timeout"):
            return json_response("504 Gateway Timeout", {"error": send_error})
        return json_response("502 Bad Gateway", {"error": send_error})
    return json_response(
        "200 OK",
        {
            "ok": bool(response.get("ok")),
            "installId": response.get("installId", install_id),
            "requestId": response.get("requestId", request_id),
            "command": response.get("command", command),
            "exitCode": response.get("exitCode", 1),
            "stdout": response.get("stdout", ""),
            "stderr": response.get("stderr", ""),
            "error": response.get("error", ""),
        },
    )


async def api_device_logcat(state: RelayState, body: dict[str, Any]) -> bytes:
    args, error = build_logcat_args(body)
    if error:
        return json_response("400 Bad Request", {"error": error})
    return await api_device_command(state, body, "mira-logcat", args)


async def api_device_ios_logs(state: RelayState, body: dict[str, Any]) -> bytes:
    count = int_field(body, "count", 1, 5000)
    if count is None:
        return json_response("400 Bad Request", {"error": "invalid iOS log count"})
    return await api_device_command(state, body, "mira-ios-logs", ["--tail", str(count)], {"count": count})


async def api_screen_frame(state: RelayState, body: dict[str, Any]) -> bytes:
    install_id = str(body.get("installId") or "").strip()
    if not install_id:
        return json_response("400 Bad Request", {"error": "missing installId"})
    if str(body.get("type") or "") != "device.screen.frame":
        return json_response("400 Bad Request", {"error": "invalid frame type"})
    frame_format = str(body.get("format") or "").lower()
    if frame_format != "jpeg":
        return json_response("400 Bad Request", {"error": "unsupported frame format"})
    width = int_field(body, "width", 1, MAX_SCREEN_FRAME_DIMENSION)
    height = int_field(body, "height", 1, MAX_SCREEN_FRAME_DIMENSION)
    seq = int_field(body, "seq", 0)
    if width is None or height is None:
        return json_response("400 Bad Request", {"error": "invalid frame dimensions"})
    if seq is None:
        return json_response("400 Bad Request", {"error": "invalid frame seq"})
    captured_at = int_field(body, "capturedAt", 0)
    source_width = int_field(body, "sourceWidth", 0, MAX_SCREEN_FRAME_DIMENSION)
    source_height = int_field(body, "sourceHeight", 0, MAX_SCREEN_FRAME_DIMENSION)
    data_base64 = str(body.get("dataBase64") or "")
    if not data_base64:
        return json_response("400 Bad Request", {"error": "missing frame data"})
    if len(data_base64) > MAX_SCREEN_FRAME_BASE64:
        return json_response("413 Payload Too Large", {"error": "frame data too large"})
    try:
        decoded = base64.b64decode(data_base64, validate=True)
    except Exception:
        return json_response("400 Bad Request", {"error": "invalid frame dataBase64"})
    if len(decoded) > MAX_SCREEN_FRAME_BYTES:
        return json_response("413 Payload Too Large", {"error": "frame jpeg too large"})
    if not decoded.startswith(b"\xff\xd8"):
        return json_response("400 Bad Request", {"error": "frame is not jpeg"})

    now = time.time()
    frame = {
        "type": "device.screen.frame",
        "protocol": PROTOCOL_VERSION,
        "installId": install_id,
        "deviceName": str(body.get("deviceName") or "Mira Device"),
        "capturedAt": captured_at or int(now * 1000),
        "seq": seq,
        "format": "jpeg",
        "width": width,
        "height": height,
        "sourceWidth": source_width or width,
        "sourceHeight": source_height or height,
        "dataBase64": data_base64,
    }
    async with state.lock:
        record = state.devices.get(install_id)
        if record is None:
            data = {
                "type": "mira.device",
                "protocol": PROTOCOL_VERSION,
                "installId": install_id,
                "deviceName": frame["deviceName"],
                "state": "idle",
                "transport": "control",
            }
            record = DeviceRecord(install_id, data, "", now)
            state.devices[install_id] = record
        else:
            if body.get("deviceName"):
                record.data["deviceName"] = str(body.get("deviceName"))
        record.screen_frame = frame
        record.screen_frame_seen = now
        record.screen_last_seen = now
        record.last_seen = now
    relay_log(f"Received screen frame installId={install_id} seq={seq} size={width}x{height}")
    return json_response("200 OK", {"ok": True, "screenLastSeen": now, "seq": seq})


async def api_screen_info(state: RelayState, query: dict[str, list[str]]) -> bytes:
    install_id = str((query.get("installId") or [""])[0] or "").strip()
    now = time.time()
    async with state.lock:
        if install_id:
            record = state.devices.get(install_id)
            if record is None:
                return json_response("404 Not Found", {"error": "device not found"})
            return json_response("200 OK", screen_info_payload(record, now))
        return json_response("200 OK", {"screens": [screen_info_payload(record, now) for record in state.devices.values()]})


async def api_screen_latest(state: RelayState, query: dict[str, list[str]]) -> bytes:
    install_id = str((query.get("installId") or [""])[0] or "").strip()
    if not install_id:
        return json_response("400 Bad Request", {"error": "missing installId"})
    async with state.lock:
        record = state.devices.get(install_id)
        if record is None or record.screen_frame is None:
            return json_response("404 Not Found", {"error": "no screen frame"})
        frame = dict(record.screen_frame)
        received_at = record.screen_frame_seen
    if received_at is not None:
        frame["receivedAt"] = received_at
        frame["ageMs"] = max(0, int((time.time() - received_at) * 1000))
    metadata = str((query.get("metadata") or query.get("metadataOnly") or [""])[0] or "").lower()
    if metadata in {"1", "true", "yes", "only"}:
        frame.pop("dataBase64", None)
    return json_response("200 OK", frame)


async def api_screen_stream(state: RelayState, writer: asyncio.StreamWriter, query: dict[str, list[str]]) -> None:
    install_id = str((query.get("installId") or [""])[0] or "").strip()
    if not install_id:
        writer.write(json_response("400 Bad Request", {"error": "missing installId"}))
        await writer.drain()
        return
    async with state.lock:
        record = state.devices.get(install_id)
        has_frame = bool(record and record.screen_frame)
    if not has_frame:
        writer.write(json_response("404 Not Found", {"error": "no screen frame"}))
        await writer.drain()
        return

    headers = (
        "HTTP/1.1 200 OK\r\n"
        f"Content-Type: multipart/x-mixed-replace; boundary={SCREEN_STREAM_BOUNDARY}\r\n"
        "Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n"
        "Pragma: no-cache\r\n"
        "X-Accel-Buffering: no\r\n"
        "Connection: close\r\n\r\n"
    )
    writer.write(headers.encode("utf-8"))
    await writer.drain()

    last_seq: int | None = None
    try:
        while not writer.is_closing():
            async with state.lock:
                record = state.devices.get(install_id)
                frame = dict(record.screen_frame) if record and record.screen_frame else None
            if frame is None:
                await asyncio.sleep(0.25)
                continue
            seq = int(frame.get("seq") or 0)
            if last_seq == seq:
                await asyncio.sleep(0.08)
                continue
            try:
                jpeg = base64.b64decode(str(frame.get("dataBase64") or ""), validate=False)
            except Exception:
                await asyncio.sleep(0.25)
                continue
            if not jpeg:
                await asyncio.sleep(0.25)
                continue
            part_headers = (
                f"--{SCREEN_STREAM_BOUNDARY}\r\n"
                "Content-Type: image/jpeg\r\n"
                f"Content-Length: {len(jpeg)}\r\n"
                f"X-Mira-Seq: {seq}\r\n"
                f"X-Mira-Captured-At: {frame.get('capturedAt') or ''}\r\n\r\n"
            )
            writer.write(part_headers.encode("utf-8"))
            writer.write(jpeg)
            writer.write(b"\r\n")
            await writer.drain()
            last_seq = seq
    except (asyncio.IncompleteReadError, ConnectionResetError, BrokenPipeError):
        return


async def api_screen_input(state: RelayState, body: dict[str, Any]) -> bytes:
    install_id = str(body.get("installId") or "").strip()
    if not install_id:
        return json_response("400 Bad Request", {"error": "missing installId"})
    payload, validation_error = screen_input_payload(body, install_id)
    if payload is None:
        return json_response("400 Bad Request", {"error": validation_error})
    ok, error = await send_screen_input_to_device(state, install_id, payload)
    if not ok:
        status = "404 Not Found" if error == "device not found" else "409 Conflict" if "control channel" in error else "502 Bad Gateway"
        return json_response(status, {"error": error})
    return json_response("200 OK", {"ok": True, "requestId": payload.get("requestId", ""), "kind": payload.get("kind", "")})


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
        now = time.time()
        stale_session_ids: list[str] = []
        for session in state.sessions.values():
            if session.install_id == install_id and session.active:
                if session.device_writer is None and now - session.created_at > SESSION_ATTACH_GRACE_SECONDS:
                    stale_session_ids.append(session.session_id)
                    continue
                return json_response("409 Conflict", {"error": "device already has active session", "sessionId": session.session_id})
        for session_id in stale_session_ids:
            state.sessions.pop(session_id, None)
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
        "serverWs": relay_ws_url(str(record.data.get("relayUrl") or state.advertise_url), "/ws/device"),
        "cols": int(body.get("cols") or 120),
        "rows": int(body.get("rows") or 36),
        "cellWidth": int(body.get("cellWidth") or 0),
        "cellHeight": int(body.get("cellHeight") or 0),
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
    relay_log(f"Opened session {session_id} for {install_id}")
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
        except Exception as exc:
            relay_log(f"Session device close notify failed: {relay_exc_text(exc)}")
    elif control_writer is not None and control_lock is not None and not control_writer.is_closing():
        try:
            await send_json(control_writer, control_lock, message)
        except Exception as exc:
            relay_log(f"Session control close notify failed: {relay_exc_text(exc)}")
    await broadcast_session(session, message)


async def api_close(state: RelayState, body: dict[str, Any]) -> bytes:
    await close_session(state, str(body.get("sessionId") or ""))
    return json_response("200 OK", {"ok": True})


async def handle_device_ws(state: RelayState, reader: asyncio.StreamReader, writer: asyncio.StreamWriter, headers: dict[str, str]) -> None:
    peer = writer.get_extra_info("peername")
    relay_log(f"Device websocket from {peer}")
    writer.write(handshake_response(headers))
    await writer.drain()
    session: RelaySession | None = None
    try:
        frame = await read_frame(reader)
        attach = json.loads(frame.payload.decode("utf-8"))
        if attach.get("type") != "device.attach":
            await send_json(writer, asyncio.Lock(), {"type": "error", "error": "invalid device attach"})
            relay_log(f"Invalid device attach from {peer}: {attach}")
            return
        session_id = str(attach.get("sessionId") or "")
        install_id = str(attach.get("installId") or "")
        async with state.lock:
            session = state.sessions.get(session_id)
            if not session or session.install_id != install_id:
                await send_json(writer, asyncio.Lock(), {"type": "error", "error": "unknown session"})
                relay_log(f"Unknown device session from {peer}: session={session_id} installId={install_id}")
                return
            session.device_writer = writer
            session.device_lock = asyncio.Lock()
            pending_resize = session.pending_resize
            session.pending_resize = None
            if record := state.devices.get(install_id):
                record.data["state"] = "active"
                record.last_seen = time.time()
        relay_log(f"Device attached session={session_id} installId={install_id}")
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
                except Exception as exc:
                    relay_log(f"Invalid terminal output payload: {relay_exc_text(exc)}")
                    chunk = b""
                session.append_output(chunk)
                await broadcast_session(session, message)
            elif message.get("type") == "session.close":
                await close_session(state, session.session_id)
                break
    except (WebSocketClosed, asyncio.IncompleteReadError, ConnectionResetError, BrokenPipeError):
        return
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
    relay_log(f"Control websocket from {peer}")
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
        relay_log(f"Device registered installId={install_id} address={address}")
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
            elif message.get("type") == "device.outline":
                message_install_id = str(message.get("installId") or "")
                if message_install_id and message_install_id != install_id:
                    await send_json(writer, lock, {"type": "error", "error": "installId mismatch"})
                    continue
                outline = message.get("outline")
                if not isinstance(outline, dict):
                    await send_json(writer, lock, {"type": "error", "error": "invalid device outline"})
                    continue
                now = time.time()
                async with state.lock:
                    if record := state.devices.get(install_id):
                        record.outline = outline
                        record.outline_last_seen = now
                        record.data["outline"] = outline
                        record.data["outlineLastSeen"] = now
                        record.last_seen = now
            elif message.get("type") == "device.metrics":
                message_install_id = str(message.get("installId") or "")
                if message_install_id and message_install_id != install_id:
                    await send_json(writer, lock, {"type": "error", "error": "installId mismatch"})
                    continue
                metrics = message.get("metrics")
                if not isinstance(metrics, dict):
                    await send_json(writer, lock, {"type": "error", "error": "invalid device metrics"})
                    continue
                now = time.time()
                async with state.lock:
                    if record := state.devices.get(install_id):
                        record.metrics = metrics
                        record.metrics_last_seen = now
                        record.data["metrics"] = metrics
                        record.data["metricsLastSeen"] = now
                        record.last_seen = now
            elif message.get("type") == "screen.input.result":
                message_install_id = str(message.get("installId") or install_id)
                if message_install_id != install_id:
                    await send_json(writer, lock, {"type": "error", "error": "installId mismatch"})
                    continue
                message["installId"] = install_id
                async with state.lock:
                    record = state.devices.get(install_id)
                    if record is None:
                        continue
                    clients_record = record
                await broadcast_screen_clients(clients_record, message)
            elif message.get("type") == "device.log":
                scope = str(message.get("scope") or "device")
                level = str(message.get("level") or "INFO").upper()
                text = str(message.get("message") or "").strip()
                details = message.get("details")
                async with state.lock:
                    if record := state.devices.get(install_id):
                        record.last_seen = time.time()
                suffix = ""
                if details not in (None, "", {}, []):
                    try:
                        suffix = f" details={json.dumps(details, ensure_ascii=False, separators=(',', ':'))}"
                    except (TypeError, ValueError):
                        suffix = f" details={details}"
                relay_log(f"Device log installId={install_id} level={level} scope={scope} message={text or '-'}{suffix}")
            elif message.get("type") == "device.command.result":
                request_id = str(message.get("requestId") or "")
                if not request_id:
                    continue
                message_install_id = str(message.get("installId") or install_id)
                async with state.lock:
                    pending = state.pending_control_requests.get(request_id)
                if pending is None or pending.install_id != message_install_id:
                    relay_log(
                        f"Unmatched device.command.result requestId={request_id} installId={message_install_id} expected={install_id}"
                    )
                    continue
                if not pending.future.done():
                    pending.future.set_result(message)
    except (WebSocketClosed, asyncio.IncompleteReadError, ConnectionResetError, BrokenPipeError) as exc:
        relay_log(f"Control websocket disconnected installId={install_id or '-'} peer={peer} error={relay_exc_text(exc)}")
    finally:
        async with state.lock:
            if install_id and (record := state.devices.get(install_id)) and record.control_writer is writer:
                record.control_writer = None
                record.data["state"] = "offline"
                record.last_seen = time.time()
        relay_log(f"Control websocket closed installId={install_id or '-'} peer={peer}")
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
        return
    finally:
        if session is not None:
            session.browsers.discard(client)
        writer.close()
        await writer.wait_closed()


async def handle_screen_device_ws(state: RelayState, reader: asyncio.StreamReader, writer: asyncio.StreamWriter, headers: dict[str, str]) -> None:
    peer = writer.get_extra_info("peername")
    relay_log(f"Screen device websocket from {peer}")
    writer.write(handshake_response(headers))
    await writer.drain()
    lock = asyncio.Lock()
    install_id = ""
    try:
        frame = await read_frame(reader)
        if not frame.is_text:
            await send_json(writer, lock, {"type": "error", "error": "invalid screen attach"})
            return
        attach = json.loads(frame.payload.decode("utf-8"))
        if attach.get("type") != "screen.video.info":
            relay_log(f"Invalid screen device attach from {peer}: {attach}")
            await send_json(writer, lock, {"type": "error", "error": "invalid screen attach"})
            return
        install_id = str(attach.get("installId") or "")
        if not install_id:
            relay_log(f"Missing installId for screen device attach from {peer}")
            await send_json(writer, lock, {"type": "error", "error": "missing installId"})
            return
        address = peer[0] if isinstance(peer, tuple) and peer else ""
        info = dict(attach)
        info["transport"] = "screen-ws"
        info["receivedAt"] = time.time()
        async with state.lock:
            record = state.devices.get(install_id)
            if record is None:
                data = {
                    "type": "mira.device",
                    "protocol": PROTOCOL_VERSION,
                    "installId": install_id,
                    "deviceName": str(info.get("deviceName") or "Mira Device"),
                    "state": "idle",
                    "transport": "control",
                    "address": address,
                }
                record = DeviceRecord(install_id, data, address, time.time())
                state.devices[install_id] = record
            record.screen_device_writer = writer
            record.screen_device_lock = lock
            record.screen_info = info
            record.screen_frame = None
            record.screen_frame_seen = None
            record.screen_last_seen = time.time()
            record.screen_video_packets = 0
            record.screen_video_bytes = 0
            record.screen_video_last_packet_at = None
            record.screen_video_last_broadcast_ms = None
            record.screen_video_broadcast_timeouts = 0
            record.last_seen = time.time()
            clients_record = record
        await broadcast_screen_clients(clients_record, info)
        relay_log(
            f"Screen video attached installId={install_id} codec={info.get('codec')} size={info.get('width')}x{info.get('height')}"
        )
        while True:
            frame = await read_frame(reader)
            if frame.is_close:
                break
            if frame.is_ping:
                await send_frame(writer, frame.payload, opcode=0xA, lock=lock)
                continue
            if frame.is_text:
                try:
                    message = json.loads(frame.payload.decode("utf-8"))
                except (UnicodeDecodeError, json.JSONDecodeError):
                    continue
                if message.get("type") == "screen.video.info":
                    message["transport"] = "screen-ws"
                    message["receivedAt"] = time.time()
                    async with state.lock:
                        if record := state.devices.get(install_id):
                            record.screen_info = message
                            record.screen_last_seen = time.time()
                            record.last_seen = time.time()
                            clients_record = record
                        else:
                            continue
                    await broadcast_screen_clients(clients_record, message)
                continue
            if not frame.is_binary:
                continue
            packet_received_at = time.time()
            packet_size = len(frame.payload)
            async with state.lock:
                record = state.devices.get(install_id)
                if record is None:
                    continue
                record.screen_last_seen = packet_received_at
                record.last_seen = packet_received_at
                record.screen_video_packets += 1
                record.screen_video_bytes += packet_size
                record.screen_video_last_packet_at = packet_received_at
                clients_record = record
            broadcast_started = time.perf_counter()
            broadcast_stats = await broadcast_screen_clients(clients_record, frame.payload, opcode=0x2)
            broadcast_ms = (time.perf_counter() - broadcast_started) * 1000
            async with state.lock:
                if record := state.devices.get(install_id):
                    record.screen_video_last_broadcast_ms = broadcast_ms
                    record.screen_video_broadcast_timeouts += int(broadcast_stats.get("timeouts") or 0)
    except (WebSocketClosed, asyncio.IncompleteReadError, ConnectionResetError, BrokenPipeError) as exc:
        relay_log(f"Screen device websocket disconnected installId={install_id or '-'} peer={peer} error={relay_exc_text(exc)}")
    finally:
        async with state.lock:
            if install_id and (record := state.devices.get(install_id)) and record.screen_device_writer is writer:
                record.screen_device_writer = None
                record.screen_last_seen = time.time()
                close_notice = {"type": "screen.video.close", "installId": install_id}
                clients_record = record
            else:
                clients_record = None
                close_notice = None
        if clients_record is not None and close_notice is not None:
            await broadcast_screen_clients(clients_record, close_notice)
        relay_log(f"Screen device websocket closed installId={install_id or '-'} peer={peer}")
        writer.close()
        await writer.wait_closed()


async def handle_screen_browser_ws(state: RelayState, reader: asyncio.StreamReader, writer: asyncio.StreamWriter, headers: dict[str, str]) -> None:
    writer.write(handshake_response(headers))
    await writer.drain()
    client = BrowserClient(writer=writer)
    install_id = ""
    peer = writer.get_extra_info("peername")
    try:
        frame = await read_frame(reader)
        if not frame.is_text:
            await send_json(writer, client.lock, {"type": "error", "error": "invalid screen browser attach"})
            return
        attach = json.loads(frame.payload.decode("utf-8"))
        if attach.get("type") != "screen.browser.attach":
            await send_json(writer, client.lock, {"type": "error", "error": "invalid screen browser attach"})
            return
        install_id = str(attach.get("installId") or "")
        if not install_id:
            await send_json(writer, client.lock, {"type": "error", "error": "missing installId"})
            return
        relay_log(f"Screen browser websocket attached installId={install_id} peer={peer}")
        async with state.lock:
            record = state.devices.get(install_id)
            if record is None:
                record = DeviceRecord(
                    install_id,
                    {
                        "type": "mira.device",
                        "protocol": PROTOCOL_VERSION,
                        "installId": install_id,
                        "deviceName": "Mira Device",
                        "state": "unknown",
                        "transport": "control",
                    },
                    "",
                    time.time(),
                )
                state.devices[install_id] = record
            record.screen_clients.add(client)
            screen_live = record.screen_device_writer is not None and not record.screen_device_writer.is_closing()
            info = dict(record.screen_info) if screen_live and record.screen_info else None
        if info is not None:
            await send_json(writer, client.lock, info)
        else:
            await send_json(writer, client.lock, {"type": "screen.video.waiting", "installId": install_id})
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
            if message.get("type") == "screen.input":
                message_install_id = str(message.get("installId") or install_id)
                if message_install_id != install_id:
                    await send_json(writer, client.lock, {"type": "error", "error": "installId mismatch"})
                    continue
                payload, validation_error = screen_input_payload(message, install_id)
                if payload is None:
                    await send_json(
                        writer,
                        client.lock,
                        {
                            "type": "screen.input.result",
                            "installId": install_id,
                            "requestId": str(message.get("requestId") or ""),
                            "clientId": str(message.get("clientId") or ""),
                            "kind": str(message.get("kind") or ""),
                            "ok": False,
                            "error": validation_error,
                        },
                    )
                    continue
                ok, error = await send_screen_input_to_device(state, install_id, payload)
                await send_json(
                    writer,
                    client.lock,
                    {
                        "type": "screen.input.queued",
                        "installId": install_id,
                        "requestId": payload.get("requestId", ""),
                        "clientId": payload.get("clientId", ""),
                        "kind": payload.get("kind", ""),
                        "ok": ok,
                        "error": error,
                    },
                )
    except (WebSocketClosed, asyncio.IncompleteReadError, ConnectionResetError, BrokenPipeError) as exc:
        relay_log(f"Screen browser websocket disconnected installId={install_id or '-'} peer={peer} error={relay_exc_text(exc)}")
    finally:
        async with state.lock:
            if install_id and (record := state.devices.get(install_id)):
                record.screen_clients.discard(client)
        relay_log(f"Screen browser websocket closed installId={install_id or '-'} peer={peer}")
        writer.close()
        await writer.wait_closed()


async def handle_client(state: RelayState, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    try:
        method, target, _version, headers, body = await read_http_request(reader)
        parsed = urlparse(target)
        path = parsed.path
        query = parse_qs(parsed.query)
        if path == "/ws/device" and is_upgrade_request(method, headers):
            await handle_device_ws(state, reader, writer, headers)
            return
        if path == "/ws/control" and is_upgrade_request(method, headers):
            await handle_control_ws(state, reader, writer, headers)
            return
        if path == "/ws/browser" and is_upgrade_request(method, headers):
            await handle_browser_ws(state, reader, writer, headers)
            return
        if path == "/ws/screen/device" and is_upgrade_request(method, headers):
            await handle_screen_device_ws(state, reader, writer, headers)
            return
        if path == "/ws/screen/browser" and is_upgrade_request(method, headers):
            await handle_screen_browser_ws(state, reader, writer, headers)
            return
        response = static_response(path)
        if response is not None and method.upper() == "GET":
            writer.write(response)
            await writer.drain()
            return
        if path == "/api/devices" and method.upper() == "GET":
            writer.write(await api_devices(state))
        elif path in {"/api/outline", "/api/device/outline"} and method.upper() == "POST":
            writer.write(await api_outline(state, parse_json_body(body)))
        elif path == "/api/screen/frame" and method.upper() == "POST":
            writer.write(await api_screen_frame(state, parse_json_body(body)))
        elif path == "/api/screen/info" and method.upper() == "GET":
            writer.write(await api_screen_info(state, query))
        elif path == "/api/screen/latest" and method.upper() == "GET":
            writer.write(await api_screen_latest(state, query))
        elif path == "/api/screen/stream" and method.upper() == "GET":
            await api_screen_stream(state, writer, query)
            return
        elif path == "/api/screen/input" and method.upper() == "POST":
            writer.write(await api_screen_input(state, parse_json_body(body)))
        elif path == "/api/discover" and method.upper() == "POST":
            writer.write(await api_discover(state, parse_json_body(body)))
        elif path == "/api/server/logs" and method.upper() == "GET":
            writer.write(await api_server_logs(query))
        elif path == "/api/browser/log" and method.upper() == "POST":
            writer.write(await api_browser_log(parse_json_body(body)))
        elif path == "/api/device/logcat" and method.upper() == "POST":
            writer.write(await api_device_logcat(state, parse_json_body(body)))
        elif path == "/api/device/ios/logs" and method.upper() == "POST":
            writer.write(await api_device_ios_logs(state, parse_json_body(body)))
        elif path == "/api/open" and method.upper() == "POST":
            writer.write(await api_open(state, parse_json_body(body)))
        elif path == "/api/close" and method.upper() == "POST":
            writer.write(await api_close(state, parse_json_body(body)))
        else:
            writer.write(json_response("404 Not Found", {"error": "not found"}))
        await writer.drain()
    except (asyncio.IncompleteReadError, ConnectionResetError, BrokenPipeError):
        return
    except Exception as exc:  # noqa: BLE001
        relay_log(f"HTTP handler error: {traceback.format_exc().rstrip()}")
        writer.write(json_response("500 Internal Server Error", {"error": str(exc)}))
        await writer.drain()
    finally:
        if not writer.is_closing():
            writer.close()
            await writer.wait_closed()


async def run_server(
    host: str,
    port: int,
    discovery_port: int,
    advertise_url: str,
    advertise_url_file: str = "",
) -> None:
    state = RelayState(
        discovery_port=discovery_port,
        advertise_url=advertise_url or f"http://{host}:{port}",
        advertise_url_file=advertise_url_file,
    )
    server = await asyncio.start_server(lambda r, w: handle_client(state, r, w), host, port)
    addresses = ", ".join(str(sock.getsockname()) for sock in server.sockets or [])
    relay_log(f"Mira Relay listening on {addresses}")
    relay_log(f"Open {state.advertise_url}")
    if is_local_advertise_url(state.advertise_url):
        relay_log("Android Relay URL pending public tunnel or LAN IP")
    else:
        relay_log(f"Android Relay URL {state.advertise_url}")
    relay_log(f"Control WebSocket {state.server_ws_url().replace('/ws/device', '/ws/control')}")
    relay_log(f"Legacy discovery UDP port {discovery_port}")
    async with server:
        await server.serve_forever()


def main() -> None:
    parser = argparse.ArgumentParser(description="Run Mira Relay Server")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--discovery-port", type=int, default=8766)
    parser.add_argument("--advertise-url", default="")
    parser.add_argument("--advertise-url-file", default="")
    args = parser.parse_args()
    asyncio.run(run_server(args.host, args.port, args.discovery_port, args.advertise_url, args.advertise_url_file))


if __name__ == "__main__":
    main()
