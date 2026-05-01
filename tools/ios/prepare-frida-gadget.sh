#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CACHE_DIR="${MIRA_FRIDA_IOS_CACHE_DIR:-$ROOT_DIR/build/ios-frida-gadget}"
FRIDA_VERSION="${MIRA_FRIDA_VERSION:-16.0.7}"
DEFAULT_DEVICE_ARCHIVE_NAME="frida-gadget-${FRIDA_VERSION}-ios-universal.dylib.gz"
DEFAULT_SIM_ARCHIVE_NAME="frida-gadget-${FRIDA_VERSION}-ios-simulator-universal.dylib.xz"
DEVICE_ARCHIVE="${MIRA_FRIDA_IOS_DEVICE_ARCHIVE:-$HOME/Downloads/${DEFAULT_DEVICE_ARCHIVE_NAME}}"
SIM_ARCHIVE="${MIRA_FRIDA_IOS_SIM_ARCHIVE:-$HOME/Downloads/${DEFAULT_SIM_ARCHIVE_NAME}}"

if [[ -z "${TARGET_BUILD_DIR:-}" || -z "${WRAPPER_NAME:-}" ]]; then
    OUTPUT_DIR="${MIRA_FRIDA_IOS_OUTPUT_DIR:-$ROOT_DIR/ios/Mira/Mira/Resources}"
else
    OUTPUT_DIR="$TARGET_BUILD_DIR/$WRAPPER_NAME"
fi

FRAMEWORKS_DIR="${OUTPUT_DIR}/Frameworks"
STAMP_FILE="${OUTPUT_DIR}/.mira-frida-gadget.stamp"
TARGET_DYLIB="${FRAMEWORKS_DIR}/libdynamic.dylib"
TARGET_CONFIG="${OUTPUT_DIR}/libdynamic.config"

fetch_archive() {
    local url="$1"
    local destination="$2"
    mkdir -p "$(dirname "$destination")"
    curl \
        -fL \
        --retry 5 \
        --retry-all-errors \
        --retry-delay 2 \
        --connect-timeout 20 \
        --max-time 300 \
        -o "${destination}.tmp" \
        "$url"
    mv "${destination}.tmp" "$destination"
}

mkdir -p "$CACHE_DIR" "$FRAMEWORKS_DIR"

if [[ "${PLATFORM_NAME:-}" == "iphonesimulator" || "${SDK_NAME:-}" == iphonesimulator* ]]; then
    SOURCE_ARCHIVE="$SIM_ARCHIVE"
    CACHE_DYLIB="${CACHE_DIR}/libdynamic-simulator.dylib"
    CACHE_ARCHIVE="${CACHE_DIR}/${DEFAULT_SIM_ARCHIVE_NAME}"
    DEFAULT_ARCHIVE_URL="https://github.com/frida/frida/releases/download/${FRIDA_VERSION}/${DEFAULT_SIM_ARCHIVE_NAME}"
    EXTRACT_CMD=(xz -dc)
    PLATFORM_LABEL="iphonesimulator"
else
    SOURCE_ARCHIVE="$DEVICE_ARCHIVE"
    CACHE_DYLIB="${CACHE_DIR}/libdynamic-device.dylib"
    CACHE_ARCHIVE="${CACHE_DIR}/${DEFAULT_DEVICE_ARCHIVE_NAME}"
    DEFAULT_ARCHIVE_URL="https://github.com/frida/frida/releases/download/${FRIDA_VERSION}/${DEFAULT_DEVICE_ARCHIVE_NAME}"
    EXTRACT_CMD=(gzip -dc)
    PLATFORM_LABEL="iphoneos"
fi

if [[ ! -f "$SOURCE_ARCHIVE" ]]; then
    if [[ -f "$CACHE_ARCHIVE" ]]; then
        SOURCE_ARCHIVE="$CACHE_ARCHIVE"
    else
        echo "Frida Gadget archive not found locally, downloading: $DEFAULT_ARCHIVE_URL"
        fetch_archive "$DEFAULT_ARCHIVE_URL" "$CACHE_ARCHIVE"
        SOURCE_ARCHIVE="$CACHE_ARCHIVE"
    fi
fi

if [[ ! -s "$CACHE_DYLIB" || "$SOURCE_ARCHIVE" -nt "$CACHE_DYLIB" ]]; then
    "${EXTRACT_CMD[@]}" "$SOURCE_ARCHIVE" > "${CACHE_DYLIB}.tmp"
    chmod 0755 "${CACHE_DYLIB}.tmp"
    mv "${CACHE_DYLIB}.tmp" "$CACHE_DYLIB"
fi

cp "$CACHE_DYLIB" "$TARGET_DYLIB"
chmod 0755 "$TARGET_DYLIB"

cat > "$TARGET_CONFIG" <<'EOF'
{
  "interaction": {
    "type": "listen",
    "address": "127.0.0.1",
    "port": 27042,
    "on_load": "resume"
  },
  "teardown": "minimal",
  "runtime": "default",
  "code_signing": "required"
}
EOF

if [[ "${CODE_SIGNING_ALLOWED:-NO}" != "NO" && -n "${EXPANDED_CODE_SIGN_IDENTITY:-}" ]]; then
    /usr/bin/codesign --force --sign "$EXPANDED_CODE_SIGN_IDENTITY" --timestamp=none "$TARGET_DYLIB"
fi

printf '%s %s\n' "$PLATFORM_LABEL" "$SOURCE_ARCHIVE" > "$STAMP_FILE"
echo "Prepared Frida Gadget for $PLATFORM_LABEL at $TARGET_DYLIB"
