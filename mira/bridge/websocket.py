"""最小 WebSocket 帧处理。

只实现 Web Terminal 需要的部分: 握手, 文本/二进制帧, close, ping/pong。
浏览器输入是已 mask 的客户端帧, 服务端输出按协议不 mask。
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import struct
from dataclasses import dataclass
from typing import Mapping

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
MAX_MESSAGE_SIZE = 8 * 1024 * 1024


class WebSocketClosed(Exception):
    """WebSocket 已关闭。"""


@dataclass
class WebSocketFrame:
    opcode: int
    payload: bytes
    fin: bool = True

    @property
    def is_text(self) -> bool:
        return self.opcode == 0x1

    @property
    def is_binary(self) -> bool:
        return self.opcode == 0x2

    @property
    def is_close(self) -> bool:
        return self.opcode == 0x8

    @property
    def is_ping(self) -> bool:
        return self.opcode == 0x9

    @property
    def is_pong(self) -> bool:
        return self.opcode == 0xA


def build_accept_key(client_key: str) -> str:
    sha1 = hashlib.sha1((client_key + GUID).encode("ascii")).digest()
    return base64.b64encode(sha1).decode("ascii")


def is_upgrade_request(method: str, headers: Mapping[str, str]) -> bool:
    connection = headers.get("connection", "").lower()
    upgrade = headers.get("upgrade", "").lower()
    return method.upper() == "GET" and "upgrade" in connection and upgrade == "websocket"


def handshake_response(headers: Mapping[str, str]) -> bytes:
    client_key = headers.get("sec-websocket-key")
    if not client_key:
        raise ValueError("Missing Sec-WebSocket-Key")

    accept_key = build_accept_key(client_key)
    return (
        "HTTP/1.1 101 Switching Protocols\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Accept: {accept_key}\r\n"
        "\r\n"
    ).encode("ascii")


async def read_frame(reader: asyncio.StreamReader) -> WebSocketFrame:
    first_frame = await read_raw_frame(reader)
    if first_frame.opcode in {0x8, 0x9, 0xA} or first_frame.fin:
        return first_frame
    if first_frame.opcode not in {0x1, 0x2}:
        raise WebSocketClosed("unexpected fragmented websocket frame")

    opcode = first_frame.opcode
    chunks = [first_frame.payload]
    total = len(first_frame.payload)
    while True:
        frame = await read_raw_frame(reader)
        if frame.opcode == 0x8:
            return frame
        if frame.opcode in {0x9, 0xA}:
            continue
        if frame.opcode != 0x0:
            raise WebSocketClosed("invalid websocket continuation")
        chunks.append(frame.payload)
        total += len(frame.payload)
        if total > MAX_MESSAGE_SIZE:
            raise WebSocketClosed("websocket message too large")
        if frame.fin:
            return WebSocketFrame(opcode=opcode, payload=b"".join(chunks), fin=True)


async def read_raw_frame(reader: asyncio.StreamReader) -> WebSocketFrame:
    header = await reader.readexactly(2)
    first, second = header
    fin = (first & 0x80) != 0
    opcode = first & 0x0F
    masked = (second & 0x80) != 0
    length = second & 0x7F

    if length == 126:
        length = struct.unpack("!H", await reader.readexactly(2))[0]
    elif length == 127:
        length = struct.unpack("!Q", await reader.readexactly(8))[0]

    mask_key = await reader.readexactly(4) if masked else b""
    payload = await reader.readexactly(length) if length else b""

    if masked:
        payload = bytes(byte ^ mask_key[index % 4] for index, byte in enumerate(payload))

    return WebSocketFrame(opcode=opcode, payload=payload, fin=fin)


def encode_frame(payload: bytes, opcode: int = 0x1) -> bytes:
    first = 0x80 | opcode
    length = len(payload)

    if length < 126:
        header = bytes([first, length])
    elif length <= 0xFFFF:
        header = bytes([first, 126]) + struct.pack("!H", length)
    else:
        header = bytes([first, 127]) + struct.pack("!Q", length)

    return header + payload


async def send_frame(
    writer: asyncio.StreamWriter,
    payload: bytes,
    opcode: int = 0x1,
    lock: asyncio.Lock | None = None,
) -> None:
    async def _send() -> None:
        writer.write(encode_frame(payload, opcode=opcode))
        await writer.drain()

    if lock:
        async with lock:
            await _send()
    else:
        await _send()
