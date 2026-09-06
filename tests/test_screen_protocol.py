"""Exercise the production screen protocol code on the host JVM, without Android stubs."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ScreenProtocolTest(unittest.TestCase):
    def test_avc_and_mhs1_wire_compatibility(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = ROOT / "android/app/src/main/java/com/vwww/mira/screen"
            subprocess.run([
                "javac", "--release", "8", "-d", temporary,
                str(source / "AvcBitstream.java"),
                str(source / "ScreenVideoPacket.java"),
                str(ROOT / "tests/java/com/vwww/mira/screen/ScreenProtocolTest.java"),
            ], check=True, capture_output=True, text=True)
            result = subprocess.run([
                "java", "-cp", temporary, "com.vwww.mira.screen.ScreenProtocolTest",
            ], check=True, capture_output=True, text=True)
            self.assertIn("PASS: AVC conversion, codec configuration, MHS1 wire compatibility", result.stdout)


if __name__ == "__main__":
    unittest.main()
