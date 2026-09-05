#!/usr/bin/env python3
"""Verify standalone ABI APKs against a universal APK from the same build."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

ABIS = {"arm64-v8a", "armeabi-v7a"}


def payload(archive):
    entries = archive.infolist()
    if len(entries) != len({i.filename for i in entries}):
        raise ValueError("Duplicate ZIP entries")
    return {i.filename: i for i in entries if not i.is_dir() and
            i.filename != "AndroidManifest.xml" and not (
                i.filename.upper() == "META-INF/MANIFEST.MF" or
                (i.filename.upper().startswith("META-INF/") and
                 i.filename.upper().endswith((".SF", ".RSA", ".DSA", ".EC"))))}


def sha(archive, name):
    digest = hashlib.sha256()
    with archive.open(name) as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def check(universal, variants):
    if set(variants) != ABIS:
        raise ValueError("Both ABI variants are required")
    report = {}
    with zipfile.ZipFile(universal) as base:
        entries = payload(base)
        if {n.split('/')[1] for n in entries if n.startswith('lib/')} != ABIS:
            raise ValueError("Universal ABI set mismatch")
        for abi in ABIS:
            for name in ("libdynamic.so", "libdynamic.config.so", "libmira_pty.so"):
                path = f"lib/{abi}/{name}"
                if path not in entries or entries[path].file_size == 0:
                    raise ValueError(f"Missing/empty JNI payload: {path}")
        prefix = 'assets/bootstrap/prefix/arm64-v8a/'
        for name in ('bin/python3', 'bin/pip', 'bin/frida-official'):
            path = prefix + name
            if path not in entries or entries[path].file_size == 0:
                raise ValueError(f"Missing/empty bootstrap: {path}")
        frida_extensions = [name for name in entries if name.startswith(prefix + 'lib/python')
                            and name.endswith(('/site-packages/_frida.abi3.so',
                                               '/site-packages/frida/_frida.abi3.so'))]
        if len(frida_extensions) != 1 or entries[frida_extensions[0]].file_size == 0:
            raise ValueError('Missing or ambiguous Frida extension')
        hashes = {n: sha(base, n) for n in entries}
        for abi, path in variants.items():
            with zipfile.ZipFile(path) as candidate:
                actual = payload(candidate)
                expected = {n for n in entries if not n.startswith('lib/') or n.startswith(f'lib/{abi}/')}
                if set(actual) != expected:
                    raise ValueError(f"{abi}: payload entry set mismatch")
                for name, info in actual.items():
                    if sha(candidate, name) != hashes[name]:
                        raise ValueError(f"{abi}: changed payload: {name}")
                    if name.endswith('.so') and info.compress_type != zipfile.ZIP_DEFLATED:
                        raise ValueError(f"{abi}: not compressed: {name}")
                report[abi] = {'bytes': path.stat().st_size, 'identical_entries': len(actual)}
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('universal', type=Path)
    parser.add_argument('arm64', type=Path)
    parser.add_argument('arm32', type=Path)
    args = parser.parse_args()
    print(json.dumps(check(args.universal, {'arm64-v8a': args.arm64, 'armeabi-v7a': args.arm32}), indent=2))


if __name__ == '__main__':
    main()
