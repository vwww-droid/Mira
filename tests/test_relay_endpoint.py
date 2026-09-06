"""Run relay URL normalization and endpoint construction on the host JVM."""
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


class RelayEndpointTest(unittest.TestCase):
    def test_golden_url_compatibility(self):
        with tempfile.TemporaryDirectory() as temporary:
            classes = Path(temporary) / "classes"
            classes.mkdir()
            subprocess.run(
                [
                    "javac", "--release", "8", "-d", str(classes),
                    str(ROOT / "android/app/src/main/java/com/vwww/mira/relay/RelayEndpoint.java"),
                    str(ROOT / "tests/java/com/vwww/mira/relay/RelayEndpointTest.java"),
                ],
                check=True,
                capture_output=True,
                text=True,
            )
            result = subprocess.run(
                ["java", "-cp", str(classes), "com.vwww.mira.relay.RelayEndpointTest"],
                check=True,
                capture_output=True,
                text=True,
                timeout=20,
            )
            self.assertIn("PASS: relay URL normalization and endpoint golden cases", result.stdout)


if __name__ == "__main__":
    unittest.main()
