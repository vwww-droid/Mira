"""Fail-closed regression cases for the APK comparison gate."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location(
    "apk_check", Path(__file__).resolve().parents[1] / "tools/android/check-apk-compression.py")
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)


class ApkCompressionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.baseline = Path(self.temp.name) / "baseline.apk"
        self.candidate = Path(self.temp.name) / "candidate.apk"
        self.content = {name: b"payload" * 1024 for name in CHECK.REQUIRED}
        for suffix in ("frida/__init__.py", "frida_tools/application.py", "pip/__init__.py"):
            self.content[CHECK.PREFIX + "lib/python3.13/site-packages/" + suffix] = b"payload" * 1024
        self.content[CHECK.PREFIX + "lib/python3.13/zipfile/_path/__init__.py"] = b"payload" * 1024
        self.content[CHECK.PREFIX + "lib/python3.13/site-packages/_frida.abi3.so"] = b"payload" * 1024
        for name in self.content:
            if name.endswith(".config.so"):
                self.content[name] = json.dumps({"interaction": {
                    "type": "listen", "on_load": "resume"}}).encode()
        self.write(self.baseline, self.content, zipfile.ZIP_STORED)

    def write(self, path, content, compression=zipfile.ZIP_DEFLATED):
        with zipfile.ZipFile(path, "w", compression=compression) as archive:
            for name, data in content.items():
                archive.writestr(name, data)

    def test_identical_compressed_payload(self):
        self.write(self.candidate, self.content)
        result = CHECK.compare(self.baseline, self.candidate)
        self.assertEqual(result["identical_payload_entries"], len(self.content))
        self.assertGreater(result["compressed_library_entries"], 0)

    def test_accepts_python_314_runtime_layout(self):
        moved = {
            name.replace("lib/python3.13/", "lib/python3.14/"): data
            for name, data in self.content.items()
        }
        self.write(self.baseline, moved, zipfile.ZIP_STORED)
        self.write(self.candidate, moved)
        self.assertGreater(CHECK.compare(self.baseline, self.candidate)["compressed_library_entries"], 0)

    def test_reject_empty_archive_missing_empty_changed_and_stored(self):
        name = CHECK.PREFIX + "lib/python3.13/site-packages/_frida.abi3.so"
        cases = [({}, "Missing required"),
                 ({k: v for k, v in self.content.items() if k != name}, "Missing or empty Frida extension"),
                 (dict(self.content, **{name: b""}), "Missing or empty Frida extension"),
                 (dict(self.content, **{name: b"changed"}), "Payload changed")]
        for content, message in cases:
            with self.subTest(message=message):
                self.write(self.candidate, content)
                with self.assertRaisesRegex(ValueError, message):
                    CHECK.compare(self.baseline, self.candidate)
        self.write(self.candidate, self.content, zipfile.ZIP_STORED)
        with self.assertRaisesRegex(ValueError, "Expected DEFLATE"):
            CHECK.compare(self.baseline, self.candidate)


if __name__ == "__main__":
    unittest.main()
