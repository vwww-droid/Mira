"""Run remote screen-input routing against the real production handler on the host JVM."""
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "tests/java/com/vwww/mira/screen/input"
COMMAND_STUBS = ROOT / "tests/java/com/vwww/mira/command/stubs"


class RemoteInputHandlerTest(unittest.TestCase):
    def test_routing_validation_clamping_and_result_schema(self):
        with tempfile.TemporaryDirectory() as temporary:
            classes = Path(temporary) / "classes"
            classes.mkdir()
            sources = [
                COMMAND_STUBS / "android/util/Log.java",
                COMMAND_STUBS / "org/json/JSONException.java",
                COMMAND_STUBS / "org/json/JSONArray.java",
                COMMAND_STUBS / "org/json/JSONObject.java",
                FIXTURES / "stubs/com/vwww/mira/screen/AppScreenCapture.java",
                ROOT / "android/app/src/main/java/com/vwww/mira/screen/RemoteInputHandler.java",
                FIXTURES / "RemoteInputHandlerTest.java",
            ]
            subprocess.run(
                ["javac", "--release", "8", "-d", str(classes), *map(str, sources)],
                check=True,
                capture_output=True,
                text=True,
            )
            result = subprocess.run(
                ["java", "-cp", str(classes), "com.vwww.mira.screen.RemoteInputHandlerTest"],
                check=True,
                capture_output=True,
                text=True,
                timeout=20,
            )
            self.assertIn("PASS: remote input validation, routing, clamping, and result schema", result.stdout)


if __name__ == "__main__":
    unittest.main()
