"""Run the Android command protocol and remote handler on the host JVM."""
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
PRODUCTION = ROOT / "android/app/src/main/java/com/vwww/mira/command"
FIXTURES = ROOT / "tests/java/com/vwww/mira/command"


def compile_and_run(sources, main_class):
    with tempfile.TemporaryDirectory() as temporary:
        classes = Path(temporary) / "classes"
        classes.mkdir()
        subprocess.run(
            ["javac", "--release", "8", "-d", str(classes), *map(str, sources)],
            check=True,
            capture_output=True,
            text=True,
        )
        return subprocess.run(
            ["java", "-cp", str(classes), main_class],
            check=True,
            capture_output=True,
            text=True,
            timeout=20,
        ).stdout


class AndroidCommandsTest(unittest.TestCase):
    def test_protocol_result_and_process_runner(self):
        sources = [
            FIXTURES / "stubs/android/util/Base64.java",
            FIXTURES / "stubs/android/os/SystemClock.java",
            FIXTURES / "stubs/org/json/JSONException.java",
            FIXTURES / "stubs/org/json/JSONArray.java",
            FIXTURES / "stubs/org/json/JSONObject.java",
            PRODUCTION / "CommandResult.java",
            PRODUCTION / "LocalCommandProtocol.java",
            PRODUCTION / "ProcessCommandRunner.java",
            FIXTURES / "CommandProtocolProcessTest.java",
        ]
        output = compile_and_run(sources, "com.vwww.mira.command.CommandProtocolProcessTest")
        self.assertIn("PASS: command framing, text encoding, result schema, and process output", output)

    def test_remote_command_routing_and_response_schema(self):
        sources = [
            FIXTURES / "stubs/android/content/Context.java",
            FIXTURES / "stubs/android/os/SystemClock.java",
            FIXTURES / "stubs/android/util/Log.java",
            FIXTURES / "stubs/org/json/JSONException.java",
            FIXTURES / "stubs/org/json/JSONArray.java",
            FIXTURES / "stubs/org/json/JSONObject.java",
            PRODUCTION / "CommandResult.java",
            FIXTURES / "stubs/com/vwww/mira/command/CommandDispatcher.java",
            PRODUCTION / "RemoteCommandHandler.java",
            FIXTURES / "RemoteCommandHandlerTest.java",
        ]
        output = compile_and_run(sources, "com.vwww.mira.command.RemoteCommandHandlerTest")
        self.assertIn("PASS: remote command identity, validation, dispatch, and result schema", output)


if __name__ == "__main__":
    unittest.main()
