#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ISH_DIR="$ROOT_DIR/third_party/ish"
CACHE_DIR="${MIRA_ISH_ROOTFS_CACHE_DIR:-$ROOT_DIR/build/ios-ish-rootfs}"
HOST_TOOLS_DIR="${MIRA_ISH_HOST_TOOLS_DIR:-$ROOT_DIR/build/ios-ish-host-tools}"
ROOTFS_NAME="MiraISHRoot.fakefs"
FRIDA_RUNTIME_HELPER="$ROOT_DIR/tools/ios/prepare-ish-frida-runtime.py"
EXPECTED_FRIDA_RUNTIME_STAMP="frida_runtime=official-frida-tools-12.1.0-frida-16.0.7-v13"
ISH_HOST="$HOST_TOOLS_DIR/ish"

if [[ -n "${MIRA_ISH_ROOTFS_OUTPUT:-}" ]]; then
    OUTPUT_DIR="$MIRA_ISH_ROOTFS_OUTPUT"
elif [[ -n "${TARGET_BUILD_DIR:-}" && -n "${UNLOCALIZED_RESOURCES_FOLDER_PATH:-}" ]]; then
    OUTPUT_DIR="$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH/$ROOTFS_NAME"
else
    OUTPUT_DIR="$ROOT_DIR/ios/Mira/Mira/Resources/$ROOTFS_NAME"
fi

ROOTFS_URL="${MIRA_ISH_ROOTFS_URL:-}"
if [[ -z "$ROOTFS_URL" ]]; then
    ROOTFS_URL="$(awk -F= '/^[[:space:]]*ROOTFS_URL[[:space:]]*=/{gsub(/[[:space:]]/, "", $2); print $2; exit}' "$ISH_DIR/app/iSH.xcconfig")"
fi
if [[ "$ROOTFS_URL" != http://* && "$ROOTFS_URL" != https://* ]]; then
    ROOTFS_URL="https://$ROOTFS_URL"
fi

ARCHIVE="$CACHE_DIR/appstore-apk.tar.gz"
FAKEFSIFY="$HOST_TOOLS_DIR/tools/fakefsify"
STAMP="$OUTPUT_DIR/.mira-ish-rootfs.stamp"

cleanup_path() {
    local path="$1"
    local attempt
    for attempt in 1 2 3 4 5; do
        rm -rf "$path" 2>/dev/null || true
        if [[ ! -e "$path" ]]; then
            return 0
        fi
        sleep 1
    done
    rm -rf "$path"
}

ensure_host_tools_builddir() {
    if [[ ! -f "$HOST_TOOLS_DIR/build.ninja" ]]; then
        rm -rf "$HOST_TOOLS_DIR"
        meson setup "$HOST_TOOLS_DIR" "$ISH_DIR" --buildtype=release -Dlog_handler=dprintf -Dkernel=ish
    fi
}

if [[ -d "$OUTPUT_DIR/data" && -f "$OUTPUT_DIR/meta.db" && -f "$STAMP" ]] && grep -q "$EXPECTED_FRIDA_RUNTIME_STAMP" "$STAMP"; then
    echo "iSH rootfs already prepared: $OUTPUT_DIR"
    exit 0
fi

mkdir -p "$CACHE_DIR" "$(dirname "$OUTPUT_DIR")"

if [[ ! -s "$ARCHIVE" ]]; then
    echo "Downloading iSH rootfs: $ROOTFS_URL"
    curl -fL --retry 3 --connect-timeout 20 -o "$ARCHIVE.tmp" "$ROOTFS_URL"
    mv "$ARCHIVE.tmp" "$ARCHIVE"
fi

if [[ ! -x "$FAKEFSIFY" ]]; then
    if ! command -v meson >/dev/null 2>&1; then
        echo "meson is required to build iSH fakefsify." >&2
        exit 1
    fi
    if ! command -v ninja >/dev/null 2>&1; then
        echo "ninja is required to build iSH fakefsify." >&2
        exit 1
    fi
    if ! command -v pkg-config >/dev/null 2>&1 && [[ ! -d /opt/homebrew/opt/libarchive && ! -d /usr/local/opt/libarchive ]]; then
        echo "pkg-config or Homebrew libarchive is required to build iSH fakefsify." >&2
        exit 1
    fi
    ensure_host_tools_builddir
    ninja -C "$HOST_TOOLS_DIR" tools/fakefsify
fi

if [[ ! -x "$ISH_HOST" ]]; then
    ensure_host_tools_builddir
    ninja -C "$HOST_TOOLS_DIR" ish
fi

TMP_DIR="$CACHE_DIR/$ROOTFS_NAME.tmp"
STAGING_ROOT="$CACHE_DIR/$ROOTFS_NAME.rootfs"
PATCHED_ARCHIVE="$CACHE_DIR/$ROOTFS_NAME.patched.tar.gz"
STAGING_STAMP="$CACHE_DIR/$ROOTFS_NAME.stamp"
cleanup_path "$TMP_DIR"
cleanup_path "$STAGING_ROOT"
mkdir -p "$STAGING_ROOT"
tar -xzf "$ARCHIVE" -C "$STAGING_ROOT"
python3 "$FRIDA_RUNTIME_HELPER" --rootfs "$STAGING_ROOT" --cache-dir "$CACHE_DIR/frida-runtime" --stamp-path "$STAGING_STAMP"
tar -C "$STAGING_ROOT" -czf "$PATCHED_ARCHIVE" .
"$FAKEFSIFY" "$PATCHED_ARCHIVE" "$TMP_DIR"
"$ISH_HOST" -f "$TMP_DIR" /bin/sh -lc 'export PYTHONPATH=/opt/mira/frida-python/site-packages; /usr/bin/python3 -c "import compileall, sys; compileall.compile_dir(f\"/usr/lib/python{sys.version_info.major}.{sys.version_info.minor}\", quiet=1); compileall.compile_dir(\"/opt/mira/frida-python/site-packages\", quiet=1)"'
rm -rf "$OUTPUT_DIR"
ditto "$TMP_DIR" "$OUTPUT_DIR"
cleanup_path "$TMP_DIR"
cleanup_path "$STAGING_ROOT"
cp "$ISH_DIR/LICENSE.md" "$OUTPUT_DIR/iSH-LICENSE.md"
cp "$ISH_DIR/LICENSE.IOS" "$OUTPUT_DIR/iSH-LICENSE.IOS"
cp "$STAGING_STAMP" "$STAMP"

if [[ ! -f "$STAMP" ]]; then
    echo "prepare-ish-frida-runtime.py did not write stamp: $STAMP" >&2
    exit 1
fi

echo "Prepared iSH rootfs at $OUTPUT_DIR"
