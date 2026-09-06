"""Run terminal launch-spec construction against the real production class on the host JVM."""
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
PRODUCTION = ROOT / "android/app/src/main/java/com/vwww/mira/terminal"
FIXTURES = ROOT / "tests/java/com/vwww/mira/terminal"


class TerminalLaunchSpecTest(unittest.TestCase):
    def test_dimensions_environment_and_defensive_copies(self):
        with tempfile.TemporaryDirectory() as temporary:
            classes = Path(temporary) / "classes"
            classes.mkdir()
            sources = [
                *sorted((FIXTURES / "stubs").rglob("*.java")),
                PRODUCTION / "PtyLaunchSpec.java",
                FIXTURES / "PtyLaunchSpecTest.java",
            ]
            subprocess.run(
                ["javac", "--release", "8", "-d", str(classes), *map(str, sources)],
                check=True,
                capture_output=True,
                text=True,
            )
            result = subprocess.run(
                [
                    "java", "-cp", str(classes),
                    "com.vwww.mira.terminal.PtyLaunchSpecTest",
                    str(Path(temporary) / "runtime"),
                ],
                check=True,
                capture_output=True,
                text=True,
                timeout=20,
            )
            self.assertIn("PASS: terminal dimensions, environment, toolbox, and defensive copies", result.stdout)


if __name__ == "__main__":
    unittest.main()
