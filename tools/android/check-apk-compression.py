#!/usr/bin/env python3
"""Compare APK payloads without installing; fail closed on missing runtime inputs.

Usage: python3 tools/android/check-apk-compression.py BASELINE CANDIDATE
The baseline may use STORED libraries; the candidate must use DEFLATE.
Signatures and the merged Manifest are checked separately with Android SDK tools.
"""

import argparse
import hashlib
import json
from pathlib import Path
import sys
import zipfile

PREFIX = "assets/bootstrap/prefix/arm64-v8a/"
ABIS = {"arm64-v8a", "armeabi-v7a"}
REQUIRED = {
    PREFIX + name for name in (
        "SOURCE.txt", "bin/python3", "bin/pip", "bin/frida-official",
        "lib/python3.13/site-packages/frida/__init__.py",
        "lib/python3.13/site-packages/frida_tools/application.py",
        "lib/python3.13/site-packages/pip/__init__.py",
    )
} | {f"lib/{abi}/{name}" for abi in ABIS
     for name in ("libdynamic.so", "libdynamic.config.so")}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(archive, name):
    with archive.open(name) as stream:
        value = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
        return value.hexdigest()


def entries(archive):
    infos = archive.infolist()
    require(len({i.filename for i in infos}) == len(infos), "Duplicate ZIP entries")
    files = {i.filename: i for i in infos if not i.is_dir()}
    require(REQUIRED <= files.keys(), f"Missing required entries: {sorted(REQUIRED - files.keys())}")
    for name in REQUIRED:
        require(files[name].file_size > 0, f"Empty required entry: {name}")
    frida_extensions = [PREFIX + "lib/python3.13/site-packages/_frida.abi3.so",
                        PREFIX + "lib/python3.13/site-packages/frida/_frida.abi3.so"]
    require(any(name in files and files[name].file_size > 0 for name in frida_extensions),
            "Missing or empty Frida extension")
    require({n.split('/')[1] for n in files if n.startswith('lib/')} == ABIS,
            "Unexpected or missing JNI ABI")
    for abi in ABIS:
        config = json.loads(archive.read(f"lib/{abi}/libdynamic.config.so"))
        require(config["interaction"]["type"] == "listen", "Unexpected Gadget interaction")
        require(config["interaction"]["on_load"] == "resume", "Unexpected Gadget on_load")
    return files


def compare(baseline, candidate):
    with zipfile.ZipFile(baseline) as before, zipfile.ZipFile(candidate) as after:
        old, new = entries(before), entries(after)
        # Only signing metadata and the generated extraction flag may differ.
        def payload(names):
            return {n for n in names if n != "AndroidManifest.xml" and not (
                n.upper() == "META-INF/MANIFEST.MF" or
                (n.upper().startswith("META-INF/") and n.upper().endswith((".SF", ".RSA", ".DSA", ".EC"))))}
        names = payload(old)
        require(names == payload(new), "APK payload entry set changed")
        libraries = sorted(n for n in names if n.endswith(".so") and
                           (n.startswith("lib/") or n.startswith("assets/bootstrap/")))
        require(libraries, "No libraries checked")
        for name in libraries:
            require(new[name].compress_type == zipfile.ZIP_DEFLATED,
                    f"Expected DEFLATE: {name}")
        for name in sorted(names):
            require(digest(before, name) == digest(after, name), f"Payload changed: {name}")
        saved = baseline.stat().st_size - candidate.stat().st_size
        require(saved > 0, "Candidate did not shrink")
        return {
            "baseline_bytes": baseline.stat().st_size,
            "candidate_bytes": candidate.stat().st_size,
            "saved_bytes": saved,
            "saved_percent": saved / baseline.stat().st_size * 100,
            "identical_payload_entries": len(names),
            "compressed_library_entries": len(libraries),
            "libraries": [{"path": n, "bytes": new[n].file_size,
                           "baseline_method": old[n].compress_type,
                           "baseline_compressed_bytes": old[n].compress_size,
                           "candidate_method": new[n].compress_type,
                           "candidate_compressed_bytes": new[n].compress_size}
                          for n in libraries],
            "extracted_jni_bytes_by_abi": {
                abi: sum(i.file_size for n, i in new.items() if n.startswith(f"lib/{abi}/"))
                for abi in sorted(ABIS)},
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    args = parser.parse_args()
    try:
        result = compare(args.baseline, args.candidate)
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
