import base64
import contextlib
import io
import os
import json
import re
import subprocess
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

from mira.mcp.server import MAX_BUFFER_BYTES, MiraMcpServer, TerminalSession, ToolError


class FridaRegressionTests(unittest.TestCase):
    def source(self, **extra):
        return MiraMcpServer.build_frida_python_source(dict(mode='run_script',
            scriptBase64=base64.b64encode(b'rpc.exports = {};').decode(), rpcMethod='ping',
            waitSeconds=0, operationTimeout=2, **extra))

    def run_fake(self, legacy=False, fail=False):
        calls = []
        exports = types.SimpleNamespace(ping=lambda: 'pong')
        class Script:
            def on(self, *args): pass
            def load(self):
                if fail: raise RuntimeError('load failed')
            def unload(self): calls.append('unload')
        script = Script()
        setattr(script, 'exports' if legacy else 'exports_sync', exports)
        session = types.SimpleNamespace(create_script=lambda _: script, detach=lambda: calls.append('detach'))
        device = types.SimpleNamespace(attach=lambda _: session)
        frida = types.SimpleNamespace(get_device_manager=lambda: types.SimpleNamespace(add_remote_device=lambda _: device))
        output = io.StringIO()
        with patch.dict(sys.modules, frida=frida), contextlib.redirect_stdout(output):
            with self.assertRaises(SystemExit): exec(self.source(), {})
        return json.loads(output.getvalue()), calls

    def test_legacy_and_current_rpc(self):
        for legacy in (True, False):
            result, calls = self.run_fake(legacy=legacy)
            self.assertEqual(result['rpcResult'], 'pong')
            self.assertEqual(calls, ['unload', 'detach'])

    def test_load_failure_reports_phase_and_cleans_up(self):
        result, calls = self.run_fake(fail=True)
        self.assertFalse(result['ok'])
        self.assertEqual(result['phase'], 'load')
        self.assertIn('traceback', result)
        self.assertEqual(calls, ['unload', 'detach'])

    def test_hung_native_call_has_process_deadline(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, 'frida.py').write_text('''import time
class Device:
 def attach(self, target): time.sleep(60)
class Manager:
 def add_remote_device(self, host): return Device()
def get_device_manager(): return Manager()
''')
            result = subprocess.run([sys.executable, '-c', self.source()], cwd=directory,
                text=True, capture_output=True, timeout=5)
            self.assertEqual(result.returncode, 124)
            self.assertEqual(json.loads(result.stdout)['phase'], 'attach')

    def test_deadline_survives_blocked_or_closed_stdout(self):
        for blocked in (True, False):
            with self.subTest(blocked=blocked), tempfile.TemporaryDirectory() as directory:
                Path(directory, 'frida.py').write_text("import os,time\n"
                    "class Device:\n def attach(self, target):\n"
                    + ("  os.write(1, b'x' * 1048576)\n" if blocked else "")
                    + "  time.sleep(60)\nclass Manager:\n def add_remote_device(self, host): return Device()\n"
                    "def get_device_manager(): return Manager()\n")
                child = subprocess.Popen([sys.executable, '-c', self.source()], cwd=directory,
                                         stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                try:
                    if not blocked:
                        child.stdout.close()
                    self.assertEqual(child.wait(timeout=5), 124)
                finally:
                    if child.poll() is None:
                        child.kill(); child.wait()
                    child.stdout.close(); child.stderr.close()

    def test_watchdog_restores_inherited_stdout_flags(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, 'frida.py').write_text("import time\n"
                "class Device:\n def attach(self, target): time.sleep(60)\n"
                "class Manager:\n def add_remote_device(self, host): return Device()\n"
                "def get_device_manager(): return Manager()\n")
            read_fd, write_fd = os.pipe()
            child = subprocess.Popen([sys.executable, '-c', self.source()], cwd=directory, stdout=write_fd)
            try:
                self.assertEqual(child.wait(timeout=5), 124)
                self.assertTrue(os.get_blocking(write_fd), 'watchdog changed the parent shell stdout flags')
            finally:
                if child.poll() is None: child.kill(); child.wait()
                os.close(read_fd); os.close(write_fd)


class TerminalRegressionTests(unittest.TestCase):
    def make_session(self):
        return TerminalSession('session', 'device', None, None, status='active')

    def test_disconnect_returns_immediately(self):
        session = self.make_session()
        session._set_status('device disconnected')
        with self.assertRaisesRegex(ToolError, 'session ended'):
            session.wait_for_text('missing', 60)

    def test_multibyte_history_and_ring_rollover(self):
        for history in ('中文'.encode(), b'x' * MAX_BUFFER_BYTES):
            session = self.make_session()
            session._append_output(history)
            def send(command):
                marker = re.search(r'__MIRA_MCP_DONE_[a-f0-9]+__', command).group()
                session._append_output(('结果\n' + marker + ':0\n').encode())
            session.send_input = send
            result = MiraMcpServer('http://localhost', '').execute_command(session, 'echo result', 1)
            self.assertEqual(result['exitCode'], 0)
            self.assertIn('结果', result['output'])

    def test_pty_repeated_carriage_returns(self):
        block = MiraMcpServer.extract_marked_block('prompt\r\r\nBEGIN\r\r\n{"ok":true}\r\r\nEND\r\r\n', 'BEGIN', 'END', 'test')
        self.assertEqual(json.loads(block), {'ok': True})

    def test_timeout_prevents_command_overlap(self):
        session = self.make_session()
        session.send_input = lambda _: None
        session.wait_for_text = lambda *_: (_ for _ in ()).throw(ToolError('timeout'))
        server = MiraMcpServer('http://localhost', '')
        with self.assertRaisesRegex(ToolError, 'timeout'):
            server.execute_command(session, 'sleep 60', 1)
        with self.assertRaisesRegex(ToolError, 'previous command'):
            server.execute_command(session, 'echo next', 1)


if __name__ == '__main__': unittest.main()
