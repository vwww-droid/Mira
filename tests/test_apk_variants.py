import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location('variants', Path(__file__).resolve().parents[1] / 'tools/android/check-apk-variants.py')
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)


class ApkVariantsTest(unittest.TestCase):
    def test_payload_preservation_and_wrong_abi_rejection(self):
        content = {f'lib/{abi}/{name}': b'payload' for abi in CHECK.ABIS
                   for name in ('libdynamic.so', 'libdynamic.config.so', 'libmira_pty.so')}
        content.update({'assets/bootstrap/prefix/arm64-v8a/' + name: b'runtime'
                        for name in ('bin/python3', 'bin/pip', 'bin/frida-official',
                                     'lib/python3.13/site-packages/_frida.abi3.so')})
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            def write(name, entries):
                path = root / name
                with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
                    for key, value in entries.items():
                        archive.writestr(key, value)
                return path
            base = write('universal.apk', content)
            variants = {abi: write(abi + '.apk', {k: v for k, v in content.items()
                        if not k.startswith('lib/') or k.startswith(f'lib/{abi}/')}) for abi in CHECK.ABIS}
            self.assertEqual(set(CHECK.check(base, variants)), CHECK.ABIS)
            with self.assertRaises(ValueError):
                CHECK.check(base, {})
            variants['arm64-v8a'] = base
            with self.assertRaisesRegex(ValueError, 'entry set mismatch'):
                CHECK.check(base, variants)
            empty = write('empty.apk', {})
            with self.assertRaisesRegex(ValueError, 'Universal ABI'):
                CHECK.check(empty, variants)


if __name__ == '__main__':
    unittest.main()
