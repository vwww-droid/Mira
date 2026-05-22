"""Termux/Web Terminal PTY 会话封装。

这里不执行一次性命令, 而是创建一个真实 PTY, 让 shell 持续运行在
PTY slave 侧, 服务端持有 PTY master 侧并与 WebSocket 转发字节流。
"""

from __future__ import annotations

import asyncio
import contextlib
import fcntl
import os
import pty
import signal
import struct
import subprocess
import termios
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Optional

OutputCallback = Callable[[bytes], None]
CloseCallback = Callable[[int | None], None]


DEFAULT_COLS = 80
DEFAULT_ROWS = 24


def resolve_shell() -> str:
    """选择当前环境中最合适的交互 shell。

    Termux 通常会设置 PREFIX, 常见 shell 位于 /data/data/com.termux/files/usr/bin。
    因此这里优先使用 PREFIX 下的 shell, 再使用 SHELL, 最后回退到系统常见路径。
    """

    candidates: list[str] = []

    prefix = os.environ.get("PREFIX")
    if prefix:
        candidates.extend(
            [
                str(Path(prefix) / "bin" / "bash"),
                str(Path(prefix) / "bin" / "zsh"),
                str(Path(prefix) / "bin" / "fish"),
                str(Path(prefix) / "bin" / "sh"),
            ]
        )

    env_shell = os.environ.get("SHELL")
    if env_shell:
        candidates.append(env_shell)

    candidates.extend(["/bin/bash", "/bin/zsh", "/bin/sh", "/system/bin/sh"])

    for candidate in candidates:
        if candidate and os.path.exists(candidate) and os.access(candidate, os.X_OK):
            return candidate

    return "/bin/sh"


@dataclass
class PtySession:
    """一个持久 PTY 会话。

    生命周期:
    1. open() 创建 PTY master/slave 并启动 shell。
    2. write() 把浏览器输入写入 master。
    3. on_output 回调把 master 输出交给 WebSocket。
    4. resize() 同步浏览器 cols/rows。
    5. close() 结束 shell 进程组并释放 fd。
    """

    shell: str = field(default_factory=resolve_shell)
    cwd: str | None = None
    cols: int = DEFAULT_COLS
    rows: int = DEFAULT_ROWS
    on_output: Optional[OutputCallback] = None
    on_close: Optional[CloseCallback] = None

    _master_fd: int | None = field(default=None, init=False)
    _slave_fd: int | None = field(default=None, init=False)
    _process: subprocess.Popen[bytes] | None = field(default=None, init=False)
    _wait_task: asyncio.Task[None] | None = field(default=None, init=False)
    _closed: bool = field(default=False, init=False)
    _close_notified: bool = field(default=False, init=False)

    @property
    def pid(self) -> int | None:
        return self._process.pid if self._process else None

    def open(self) -> None:
        if self._process is not None:
            raise RuntimeError("PTY session is already open")

        self._master_fd, self._slave_fd = pty.openpty()
        self._set_winsize(self.rows, self.cols)

        env = os.environ.copy()
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        env["MIRA_TERMINAL"] = "1"

        self._process = subprocess.Popen(
            [self.shell],
            cwd=self.cwd or os.getcwd(),
            env=env,
            stdin=self._slave_fd,
            stdout=self._slave_fd,
            stderr=self._slave_fd,
            preexec_fn=os.setsid,
            close_fds=True,
        )

        os.close(self._slave_fd)
        self._slave_fd = None
        os.set_blocking(self._master_fd, False)

        loop = asyncio.get_running_loop()
        loop.add_reader(self._master_fd, self._read_ready)
        self._wait_task = asyncio.create_task(self._wait_for_exit())

    def write(self, data: bytes) -> None:
        if self._closed or self._master_fd is None:
            return
        if not data:
            return
        try:
            os.write(self._master_fd, data)
        except OSError:
            self.close()

    def resize(self, cols: int, rows: int) -> None:
        if cols <= 0 or rows <= 0:
            return
        self.cols = cols
        self.rows = rows
        if self._master_fd is not None:
            self._set_winsize(rows, cols)

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True

        loop = asyncio.get_running_loop()
        if self._master_fd is not None:
            with contextlib.suppress(ValueError, RuntimeError):
                loop.remove_reader(self._master_fd)

        if self._process and self._process.poll() is None:
            try:
                os.killpg(os.getpgid(self._process.pid), signal.SIGHUP)
            except ProcessLookupError:
                self._process = None
            except PermissionError:
                self._process.terminate()

        if self._master_fd is not None:
            with contextlib.suppress(OSError):
                os.close(self._master_fd)
            self._master_fd = None

        if self._slave_fd is not None:
            with contextlib.suppress(OSError):
                os.close(self._slave_fd)
            self._slave_fd = None

        current_task = asyncio.current_task()
        if self._wait_task and not self._wait_task.done() and self._wait_task is not current_task:
            self._wait_task.cancel()

    def _set_winsize(self, rows: int, cols: int) -> None:
        fd = self._master_fd if self._master_fd is not None else self._slave_fd
        if fd is None:
            return
        winsize = struct.pack("HHHH", rows, cols, 0, 0)
        fcntl.ioctl(fd, termios.TIOCSWINSZ, winsize)

    def _read_ready(self) -> None:
        if self._master_fd is None:
            return
        while True:
            try:
                chunk = os.read(self._master_fd, 65536)
            except BlockingIOError:
                return
            except OSError:
                self._notify_close(self._process.poll() if self._process else None)
                self.close()
                return

            if not chunk:
                self._notify_close(self._process.poll() if self._process else None)
                self.close()
                return

            if self.on_output:
                self.on_output(chunk)

    def _notify_close(self, return_code: int | None) -> None:
        if self._close_notified:
            return
        self._close_notified = True
        if self.on_close:
            self.on_close(return_code)

    async def _wait_for_exit(self) -> None:
        if self._process is None:
            return
        try:
            return_code = await asyncio.to_thread(self._process.wait)
        except asyncio.CancelledError:
            return

        self._notify_close(return_code)
        if not self._closed:
            self.close()
