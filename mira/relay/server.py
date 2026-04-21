"""Mira 局域网按需远程终端 relay 服务端。"""

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
WEB_DIR = ROOT_DIR / "web"
RING_LIMIT = 1024 * 1024
PROTOCOL_VERSION = 1

INDEX_HTML = """<!doctype html>
<html lang=\"zh-CN\">
  <head>
    <meta charset=\"utf-8\" />
    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />
    <title>Mira Relay Terminal</title>
    <link rel=\"stylesheet\" href=\"/vendor/xterm/xterm.css\" />
    <link rel=\"stylesheet\" href=\"/relay.css\" />
  </head>
  <body>
    <main class=\"layout\">
      <aside class=\"sidebar\">
        <header>
          <h1>Mira Relay</h1>
          <p>局域网按需打开 Android PTY。</p>
        </header>
        <label>Broadcast Target <input id=\"broadcast\" value=\"255.255.255.255\" /></label>
        <button id=\"scan\">Scan LAN</button>
        <div id=\"status\" class=\"status\">idle</div>
        <section>
          <h2>Devices</h2>
          <div id=\"devices\" class=\"devices\"></div>
        </section>
      </aside>
      <section class=\"terminal-pane\">
        <div class=\"terminal-toolbar\">
          <div id=\"sessionTitle\">No session</div>
          <button id=\"closeSession\" disabled>Close Session</button>
        </div>
        <div id=\"terminal\" class=\"terminal\"></div>
      </section>
    </main>
    <script src=\"/vendor/xterm/xterm.js\"></script>
    <script src=\"/vendor/xterm/addon-fit.js\"></script>
    <script src=\"/relay.js\"></script>
  </body>
</html>
"""

RELAY_CSS = """:root{color-scheme:dark;--bg:#0b1020;--panel:#111827;--line:#263043;--text:#e5e7eb;--muted:#9ca3af;--green:#34d399;--red:#f87171;--yellow:#fbbf24}*{box-sizing:border-box}html,body{height:100%;margin:0}body{background:#070b14;color:var(--text);font-family:Inter,ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,\"Segoe UI\",sans-serif}.layout{display:grid;grid-template-columns:340px 1fr;height:100%;gap:0}.sidebar{border-right:1px solid var(--line);background:linear-gradient(180deg,#111827,#0b1020);padding:18px;overflow:auto}.sidebar h1{font-size:22px;margin:0 0 6px}.sidebar p{color:var(--muted);margin:0 0 18px}.sidebar label{display:block;color:var(--muted);font-size:12px;margin:12px 0}.sidebar input{width:100%;margin-top:6px;border:1px solid var(--line);border-radius:10px;background:#05070d;color:var(--text);padding:10px}.sidebar button,.terminal-toolbar button,.device button{border:0;border-radius:10px;background:#2563eb;color:white;padding:10px 12px;cursor:pointer}.sidebar button:disabled,.terminal-toolbar button:disabled,.device button:disabled{background:#374151;color:#9ca3af;cursor:not-allowed}.status{margin-top:12px;color:var(--yellow);font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}.devices{display:grid;gap:10px}.device{border:1px solid var(--line);border-radius:14px;background:#0f172a;padding:12px}.device-title{font-weight:700}.device-meta{color:var(--muted);font-size:12px;line-height:1.5;margin:6px 0 10px;word-break:break-all}.terminal-pane{display:grid;grid-template-rows:auto 1fr;min-width:0;background:radial-gradient(circle at top left,#111827,#05070d 55%)}.terminal-toolbar{display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid var(--line);padding:12px 16px;background:#0b1020}.terminal{min-height:0;padding:12px}.xterm{height:100%}.ok{color:var(--green)}.bad{color:var(--red)}"""

RELAY_JS = r"""(() => {
  const broadcastInput = document.getElementById('broadcast');
  const scanButton = document.getElementById('scan');
  const devicesEl = document.getElementById('devices');
  const statusEl = document.getElementById('status');
  const titleEl = document.getElementById('sessionTitle');
  const closeButton = document.getElementById('closeSession');
  const terminalEl = document.getElementById('terminal');

  broadcastInput.value = localStorage.getItem('mira.relay.broadcast') || broadcastInput.value;

  const term = new Terminal({
    cursorBlink: true,
    convertEol: true,
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
    fontSize: 14,
    theme: { background: '#05070d', foreground: '#e5e7eb', cursor: '#34d399' },
  });
  const fitAddon = new FitAddon.FitAddon();
  term.loadAddon(fitAddon);
  term.open(terminalEl);
  fitAddon.fit();

  let socket = null;
  let activeSession = null;
  let activeInstallId = null;
  let resizeTimer = null;

  function setStatus(text, ok = false) {
    statusEl.textContent = text;
    statusEl.className = ok ? 'status ok' : 'status';
  }

  function broadcastTarget() {
    const value = broadcastInput.value.trim() || '255.255.255.255';
    localStorage.setItem('mira.relay.broadcast', value);
    return value;
  }

  function bytesToBase64(value) {
    const bytes = new TextEncoder().encode(value);
    let binary = '';
    for (const byte of bytes) binary += String.fromCharCode(byte);
    return btoa(binary);
  }

  function base64ToBytes(value) {
    const binary = atob(value || '');
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
    return bytes;
  }

  async function api(path, body) {
    const response = await fetch(path, {
      method: body ? 'POST' : 'GET',
      headers: body ? { 'Content-Type': 'application/json' } : {},
      body: body ? JSON.stringify(body) : undefined,
    });
    const text = await response.text();
    let data = {};
    try { data = text ? JSON.parse(text) : {}; } catch (_) { data = { error: text }; }
    if (!response.ok) throw new Error(data.error || response.statusText);
    return data;
  }

  function renderDevices(devices) {
    devicesEl.innerHTML = '';
    if (!devices.length) {
      devicesEl.innerHTML = '<div class="device-meta">No devices discovered.</div>';
      return;
    }
    for (const device of devices) {
      const item = document.createElement('div');
      item.className = 'device';
      const shortId = (device.installId || '').slice(0, 8);
      item.innerHTML = `
        <div class="device-title">${device.deviceName || device.model || 'Mira Device'} <span class="device-meta">${shortId}</span></div>
        <div class="device-meta">${device.model || ''} · ${device.arch || ''}<br>${device.address || ''}<br>${device.state || 'unknown'}</div>
      `;
      const open = document.createElement('button');
      open.textContent = 'Open Terminal';
      open.onclick = () => openTerminal(device);
      item.appendChild(open);
      devicesEl.appendChild(item);
    }
  }

  async function refreshDevices() {
    const data = await api('/api/devices');
    renderDevices(data.devices || []);
  }

  async function scan() {
    scanButton.disabled = true;
    setStatus('scanning...');
    try {
      const data = await api('/api/discover', { broadcastTarget: broadcastTarget() });
      renderDevices(data.devices || []);
      setStatus(`found ${(data.devices || []).length} device(s)`, true);
    } catch (error) {
      setStatus(`scan failed: ${error.message}`);
    } finally {
      scanButton.disabled = false;
    }
  }

  function wsUrl() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${protocol}//${window.location.host}/ws/browser`;
  }

  function send(message) {
    if (socket && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message));
  }

  function connectBrowser(device, sessionId) {
    if (socket) socket.close();
    activeSession = sessionId;
    activeInstallId = device.installId;
    titleEl.textContent = `${device.deviceName || device.model || 'Mira Device'} · ${sessionId.slice(0, 8)}`;
    closeButton.disabled = false;
    term.clear();
    term.writeln('\x1b[33mConnecting relay session...\x1b[0m');
    socket = new WebSocket(wsUrl());
    socket.addEventListener('open', () => {
      send({ type: 'browser.attach', protocol: 1, installId: activeInstallId, sessionId: activeSession });
      fitAndResize();
    });
    socket.addEventListener('message', (event) => {
      let message;
      try { message = JSON.parse(event.data); } catch (_) { return; }
      if (message.type === 'terminal.output') term.write(base64ToBytes(message.dataBase64));
      if (message.type === 'session.status') setStatus(message.state || 'session status', message.state === 'active');
      if (message.type === 'session.close') {
        setStatus('session closed');
        closeButton.disabled = true;
      }
      if (message.type === 'error') term.writeln(`\r\n\x1b[31m${message.error}\x1b[0m`);
    });
    socket.addEventListener('close', () => setStatus('browser websocket closed'));
  }

  async function openTerminal(device) {
    setStatus('opening terminal...');
    fitAddon.fit();
    const data = await api('/api/open', {
      installId: device.installId, cols: term.cols || 120, rows: term.rows || 36,
    });
    connectBrowser(device, data.sessionId);
    await refreshDevices().catch(() => {});
  }

  async function closeSession() {
    if (!activeSession) return;
    await api('/api/close', { sessionId: activeSession }).catch(() => {});
    if (socket) socket.close();
    closeButton.disabled = true;
    setStatus('session close requested');
  }

  function fitAndResize() {
    fitAddon.fit();
    if (activeSession) send({ type: 'terminal.resize', sessionId: activeSession, cols: term.cols, rows: term.rows });
  }

  term.onData((data) => {
    if (!activeSession) return;
    send({ type: 'terminal.input', sessionId: activeSession, dataBase64: bytesToBase64(data) });
  });
  window.addEventListener('resize', () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(fitAndResize, 80);
  });
  scanButton.addEventListener('click', scan);
  closeButton.addEventListener('click', closeSession);
  refreshDevices().catch(() => {});
})();
"""


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


@dataclass
class RelaySession:
    session_id: str
    install_id: str
    device_writer: asyncio.StreamWriter | None = None
    device_lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    browsers: set[BrowserClient] = field(default_factory=set)
    ring: bytearray = field(default_factory=bytearray)
    active: bool = True

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


def static_response(path: str) -> bytes | None:
    if path == "/":
        return http_response("200 OK", INDEX_HTML.encode("utf-8"), "text/html; charset=utf-8")
    if path == "/relay.js":
        return http_response("200 OK", RELAY_JS.encode("utf-8"), "text/javascript; charset=utf-8")
    if path == "/relay.css":
        return http_response("200 OK", RELAY_CSS.encode("utf-8"), "text/css; charset=utf-8")
    if path.startswith("/vendor/"):
        normalized = posixpath.normpath(unquote(path)).lstrip("/")
        candidate = (WEB_DIR / normalized).resolve()
        try:
            candidate.relative_to(WEB_DIR.resolve())
        except ValueError:
            return None
        if candidate.is_file():
            content_type = mimetypes.guess_type(candidate.name)[0] or "application/octet-stream"
            if content_type.startswith("text/") or candidate.suffix in {".js", ".css"}:
                content_type += "; charset=utf-8"
            return http_response("200 OK", candidate.read_bytes(), content_type)
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


async def api_discover(state: RelayState, body: dict[str, Any]) -> bytes:
    target = str(body.get("broadcastTarget") or "255.255.255.255")
    devices = await asyncio.to_thread(scan_lan_blocking, state.advertise_url, target, state.discovery_port, 1.2)
    now = time.time()
    async with state.lock:
        for device in devices:
            install_id = str(device["installId"])
            state.devices[install_id] = DeviceRecord(install_id, device, str(device.get("address", "")), now)
        return json_response("200 OK", {"devices": [record.data for record in state.devices.values()]})


async def api_devices(state: RelayState) -> bytes:
    async with state.lock:
        return json_response("200 OK", {"devices": [record.data for record in state.devices.values()]})


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
    payload = {
        "type": "session.open",
        "protocol": PROTOCOL_VERSION,
        "installId": install_id,
        "sessionId": session_id,
        "serverWs": state.server_ws_url(),
        "cols": int(body.get("cols") or 120),
        "rows": int(body.get("rows") or 36),
    }
    try:
        await asyncio.to_thread(post_json, wake_url, payload)
    except (urllib.error.URLError, TimeoutError, OSError, json.JSONDecodeError) as exc:
        async with state.lock:
            state.sessions.pop(session_id, None)
        return json_response("502 Bad Gateway", {"error": f"wake failed: {exc}"})
    print(f"Opened session {session_id} for {install_id}", flush=True)
    return json_response("200 OK", {"sessionId": session_id})


async def close_session(state: RelayState, session_id: str) -> None:
    async with state.lock:
        session = state.sessions.get(session_id)
        if not session:
            return
        session.active = False
    message = {"type": "session.close", "sessionId": session_id}
    if session.device_writer is not None:
        try:
            await send_json(session.device_writer, session.device_lock, message)
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
        print(f"Device attached session={session_id} installId={install_id}", flush=True)
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
                await broadcast_session(session, {"type": "session.status", "sessionId": session.session_id, "state": "device disconnected"})
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
    print(f"Discovery UDP port {discovery_port}", flush=True)
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
