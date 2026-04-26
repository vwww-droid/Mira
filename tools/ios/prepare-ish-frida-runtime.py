#!/usr/bin/env python3
from __future__ import annotations

import argparse
import os
import shutil
import stat
import tarfile
import urllib.request
from pathlib import Path

FRIDA_VERSION = "16.0.7"
FRIDA_TOOLS_VERSION = "12.1.0"
RUNTIME_VERSION = "official-frida-tools-12.1.0-frida-16.0.7-v2"
DEVKIT_URL = (
    "https://github.com/frida/frida/releases/download/"
    f"{FRIDA_VERSION}/frida-core-devkit-{FRIDA_VERSION}-linux-x86.tar.xz"
)


def download(url: str, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_suffix(target.suffix + ".tmp")
    with urllib.request.urlopen(url) as response, tmp.open("wb") as sink:
        shutil.copyfileobj(response, sink)
    tmp.replace(target)


def ensure_devkit(cache_dir: Path) -> Path:
    archive = cache_dir / Path(DEVKIT_URL).name
    if not archive.exists():
        print(f"Downloading Frida Linux x86 devkit: {DEVKIT_URL}")
        download(DEVKIT_URL, archive)
    return archive


def extract_devkit(archive: Path, destination: Path) -> None:
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, "r:xz") as tar:
        tar.extractall(destination)


def write_executable(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    current = path.stat().st_mode
    path.chmod(current | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rootfs", required=True, help="Path to MiraISHRoot.fakefs directory")
    parser.add_argument("--cache-dir", required=True, help="Cache directory for downloaded artifacts")
    parser.add_argument("--stamp-path", help="Optional explicit path for the rootfs stamp file")
    args = parser.parse_args()

    rootfs = Path(args.rootfs)
    data_root = rootfs / "data" if (rootfs / "data").is_dir() else rootfs
    if not data_root.is_dir():
        raise SystemExit(f"rootfs directory not found: {data_root}")
    stamp_path = Path(args.stamp_path) if args.stamp_path else rootfs / ".mira-ish-rootfs.stamp"

    cache_dir = Path(args.cache_dir)
    devkit_archive = ensure_devkit(cache_dir)
    extract_devkit(devkit_archive, data_root / "opt" / "mira" / "frida-devkit")

    frida_setup = f"""#!/bin/sh
set -eu

STATE_DIR="/opt/mira/frida-state"
STAMP="$STATE_DIR/{RUNTIME_VERSION}"
DEVKIT_DIR="/opt/mira/frida-devkit"
SITE_PACKAGES_DIR="/opt/mira/frida-python/site-packages"

mkdir -p "$STATE_DIR"
mkdir -p "$SITE_PACKAGES_DIR"

if [ -f "$STAMP" ]; then
    exit 0
fi

if ! command -v apk >/dev/null 2>&1; then
    echo "frida-setup: apk is not available" >&2
    exit 1
fi

echo "[mira] installing official frida-tools runtime for iSH"
apk update
apk add --no-cache python3 py3-pip python3-dev build-base

export FRIDA_CORE_DEVKIT="$DEVKIT_DIR"
export PIP_DISABLE_PIP_VERSION_CHECK=1
export PIP_ROOT_USER_ACTION=ignore

python3 -m pip install --no-cache-dir -U pip setuptools wheel
python3 -m pip install --no-cache-dir --upgrade --target "$SITE_PACKAGES_DIR" --no-binary frida \
    frida=={FRIDA_VERSION} frida-tools=={FRIDA_TOOLS_VERSION}

touch "$STAMP"
echo "[mira] official frida-tools runtime is ready"
"""

    frida_wrapper = """#!/bin/sh
set -eu

/usr/bin/frida-setup

export PYTHONUNBUFFERED=1
export PYTHONPATH="/opt/mira/frida-python/site-packages${PYTHONPATH:+:$PYTHONPATH}"

if [ "${1:-}" = "--status" ]; then
    shift
    exec python3 -c "import frida, json; dev=frida.get_device_manager().add_remote_device('127.0.0.1:27042'); ps=dev.enumerate_processes(); first=ps[0] if ps else None; print(json.dumps({'frida': frida.__version__, 'connected': True, 'processCount': len(ps), 'pid': getattr(first, 'pid', None), 'target': getattr(first, 'name', None)}, separators=(',', ':')))"
fi

mira_needs_default_target=1
for mira_arg in "$@"; do
    case "$mira_arg" in
        -h|--help|--version|-D|--device|-U|--usb|-R|--remote|-H|--host|-f|--file|-F|--attach-frontmost|-n|--attach-name|-N|--attach-identifier|-p|--attach-pid|-W|--await)
            mira_needs_default_target=0
            ;;
    esac
done

if [ "$mira_needs_default_target" = "1" ]; then
    exec python3 -m frida_tools.repl -H 127.0.0.1 -n Gadget "$@"
fi

exec python3 -m frida_tools.repl "$@"
"""

    write_executable(data_root / "usr" / "bin" / "frida-setup", frida_setup)
    write_executable(data_root / "usr" / "bin" / "frida", frida_wrapper)
    stamp_path.parent.mkdir(parents=True, exist_ok=True)
    stamp_path.write_text(
        "\n".join(
            [
                "rootfs=appstore-apk",
                f"frida_runtime={RUNTIME_VERSION}",
                f"frida_version={FRIDA_VERSION}",
                f"frida_tools_version={FRIDA_TOOLS_VERSION}",
            ]
        )
        + "\n",
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
