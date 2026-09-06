"""Run the Android LAN discovery implementation on the host JVM."""
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


class AndroidDiscoveryTest(unittest.TestCase):
    def test_http_protocol_and_socket_lifecycle(self):
        with tempfile.TemporaryDirectory() as temporary:
            classes = Path(temporary) / "classes"
            classes.mkdir()
            source = ROOT / "android/app/src/main/java/com/vwww/mira/discovery"
            fixtures = ROOT / "tests/java/com/vwww/mira/discovery"
            sources = [
                *sorted(str(path) for path in (fixtures / "stubs").rglob("*.java")),
                str(source / "HttpRequestParser.java"),
                str(source / "LanDiscoveryServer.java"),
                str(fixtures / "DiscoveryProtocolTest.java"),
            ]
            subprocess.run(
                ["javac", "--release", "8", "-d", str(classes), *sources],
                check=True,
                capture_output=True,
                text=True,
            )
            result = subprocess.run(
                ["java", "-cp", str(classes), "com.vwww.mira.discovery.DiscoveryProtocolTest"],
                check=True,
                capture_output=True,
                text=True,
                timeout=20,
            )
            self.assertIn("PASS: HTTP protocol and LAN discovery lifecycle", result.stdout)


if __name__ == "__main__":
    unittest.main()
