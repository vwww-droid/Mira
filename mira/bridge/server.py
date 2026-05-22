"""Mira 最小 Web Terminal 服务端。"""

from __future__ import annotations

import argparse
import asyncio
import json
import mimetypes
import os
import posixpath
from pathlib import Path
from urllib.parse import unquote, urlparse

from mira.bridge.termux import PtySession
from mira.bridge.websocket import (
    WebSocketClosed,
    handshake_response,
    is_upgrade_request,
    read_frame,
    send_frame,
)

ROOT_DIR = Path(__file__).resolve().parents[2]
WEB_DIR = ROOT_DIR / "web"
DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8765


def parse_headers(raw: bytes) -> tuple[str, str, str, dict[str, str]]:
    text = raw.decode("iso-8859-1")
    lines = text.split("\r\n")
    method, target, version = lines[0].split(" ", 2)
    headers: dict[str, str] = {}
    for line in lines[1:]:
        if not line or ":" not in line:
            continue
        key, value = line.split(":", 1)
        headers[key.strip().lower()] = value.strip()
    return method, target, version, headers


async def read_http_request(reader: asyncio.StreamReader) -> tuple[str, str, str, dict[str, str]]:
    raw = await reader.readuntil(b"\r\n\r\n")
    return parse_headers(raw)


def http_response(
    status: str,
    body: bytes,
    content_type: str = "text/plain; charset=utf-8",
    extra_headers: dict[str, str] | None = None,
) -> bytes:
    headers = {
        "Content-Length": str(len(body)),
        "Content-Type": content_type,
        "Connection": "close",
    }
    if extra_headers:
        headers.update(extra_headers)
    header_lines = "".join(f"{key}: {value}\r\n" for key, value in headers.items())
    return f"HTTP/1.1 {status}\r\n{header_lines}\r\n".encode("utf-8") + body


def resolve_static_path(target: str) -> Path | None:
    parsed = urlparse(target)
    request_path = unquote(parsed.path)
    if request_path == "/":
        request_path = "/index.html"

    normalized = posixpath.normpath(request_path).lstrip("/")
    candidate = (WEB_DIR / normalized).resolve()

    try:
        candidate.relative_to(WEB_DIR.resolve())
    except ValueError:
        return None

    if candidate.is_file():
        return candidate
    return None


async def serve_static(target: str, writer: asyncio.StreamWriter) -> None:
    file_path = resolve_static_path(target)
    if file_path is None:
        body = "Not Found\n".encode("utf-8")
        writer.write(http_response("404 Not Found", body))
        await writer.drain()
        return

    content_type = mimetypes.guess_type(file_path.name)[0] or "application/octet-stream"
    if content_type.startswith("text/") or file_path.suffix in {".js", ".css"}:
        content_type += "; charset=utf-8"

    writer.write(
        http_response(
            "200 OK",
            file_path.read_bytes(),
            content_type=content_type,
            extra_headers={"Cache-Control": "no-store"},
        )
    )
    await writer.drain()


async def terminal_websocket(
    reader: asyncio.StreamReader,
    writer: asyncio.StreamWriter,
    headers: dict[str, str],
    cwd: str,
) -> None:
    send_lock = asyncio.Lock()
    output_queue: asyncio.Queue[bytes | None] = asyncio.Queue()

    def enqueue_output(data: bytes) -> None:
        output_queue.put_nowait(data)

    def enqueue_close(_return_code: int | None) -> None:
        output_queue.put_nowait(None)

    session = PtySession(cwd=cwd, on_output=enqueue_output, on_close=enqueue_close)
    tasks: list[asyncio.Task[None]] = []
    try:
        session.open()

        writer.write(handshake_response(headers))
        await writer.drain()

        async def pump_pty_to_ws() -> None:
            while True:
                chunk = await output_queue.get()
                if chunk is None:
                    raise WebSocketClosed()
                await send_frame(writer, chunk, opcode=0x2, lock=send_lock)

        async def pump_ws_to_pty() -> None:
            while True:
                frame = await read_frame(reader)
                if frame.is_close:
                    await send_frame(writer, b"", opcode=0x8, lock=send_lock)
                    raise WebSocketClosed()
                if frame.is_ping:
                    await send_frame(writer, frame.payload, opcode=0xA, lock=send_lock)
                    continue
                if frame.is_pong:
                    continue

                if frame.is_binary:
                    session.write(frame.payload)
                    continue

                if not frame.is_text:
                    continue

                try:
                    message = json.loads(frame.payload.decode("utf-8"))
                except (UnicodeDecodeError, json.JSONDecodeError):
                    continue

                message_type = message.get("type")
                if message_type == "input":
                    data = message.get("data", "")
                    if isinstance(data, str):
                        session.write(data.encode("utf-8"))
                elif message_type == "resize":
                    try:
                        cols = int(message.get("cols", 0))
                        rows = int(message.get("rows", 0))
                    except (TypeError, ValueError):
                        continue
                    session.resize(cols=cols, rows=rows)

        tasks = [asyncio.create_task(pump_pty_to_ws()), asyncio.create_task(pump_ws_to_pty())]
        done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_EXCEPTION)
        for task in done:
            exc = task.exception()
            if exc and not isinstance(exc, (WebSocketClosed, asyncio.IncompleteReadError, ConnectionResetError)):
                raise exc
        for task in pending:
            task.cancel()
    finally:
        session.close()
        writer.close()
        await writer.wait_closed()


async def handle_client(
    reader: asyncio.StreamReader,
    writer: asyncio.StreamWriter,
    cwd: str,
) -> None:
    try:
        method, target, _version, headers = await read_http_request(reader)
        parsed_path = urlparse(target).path
        if parsed_path == "/ws/terminal" and is_upgrade_request(method, headers):
            await terminal_websocket(reader, writer, headers, cwd=cwd)
            return

        if method.upper() != "GET":
            writer.write(http_response("405 Method Not Allowed", b"Method Not Allowed\n"))
            await writer.drain()
            return

        await serve_static(target, writer)
    except (asyncio.IncompleteReadError, ConnectionResetError, BrokenPipeError):
        return
    except Exception as exc:  # noqa: BLE001 - 最小服务端需要把错误落到 stderr。
        body = f"Internal Server Error: {exc}\n".encode("utf-8")
        writer.write(http_response("500 Internal Server Error", body))
        await writer.drain()
    finally:
        if not writer.is_closing():
            writer.close()
            await writer.wait_closed()


async def run_server(host: str, port: int, cwd: str) -> None:
    server = await asyncio.start_server(
        lambda reader, writer: handle_client(reader, writer, cwd=cwd),
        host,
        port,
    )
    addresses = ", ".join(str(sock.getsockname()) for sock in server.sockets or [])
    print(f"Mira Web Terminal listening on {addresses}", flush=True)
    print(f"Open http://{host}:{port}/", flush=True)

    async with server:
        await server.serve_forever()


def main() -> None:
    parser = argparse.ArgumentParser(description="Run Mira Web Terminal MVP server")
    parser.add_argument("--host", default=os.environ.get("MIRA_HOST", DEFAULT_HOST))
    parser.add_argument("--port", type=int, default=int(os.environ.get("MIRA_PORT", DEFAULT_PORT)))
    parser.add_argument(
        "--cwd",
        default=os.environ.get("MIRA_SHELL_CWD", str(ROOT_DIR)),
        help="PTY shell 启动目录",
    )
    args = parser.parse_args()
    cwd = Path(args.cwd).expanduser().resolve()
    if not cwd.is_dir():
        raise SystemExit(f"PTY shell 启动目录不存在: {cwd}")

    asyncio.run(run_server(args.host, args.port, str(cwd)))


if __name__ == "__main__":
    main()
