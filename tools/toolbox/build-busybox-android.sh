#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BUSYBOX_VERSION="${BUSYBOX_VERSION:-1.37.0}"
ANDROID_API="${ANDROID_API:-23}"
NDK_DIR="${NDK_DIR:-$HOME/Library/Android/sdk/ndk/29.0.14206865}"
NDK_BIN="$NDK_DIR/toolchains/llvm/prebuilt/darwin-x86_64/bin"
BUILD_DIR="${BUILD_DIR:-$ROOT_DIR/.mira/tmp/busybox-android}"
ASSET_DIR="$ROOT_DIR/android/app/src/main/assets/toolbox/busybox"
MANIFEST_PATH="$ROOT_DIR/android/app/src/main/assets/toolbox/manifest.json"
ABI_LIST="${ABI_LIST:-arm64-v8a armeabi-v7a x86 x86_64}"
ABI_ORDER="arm64-v8a armeabi-v7a x86 x86_64"
SOURCE_URL="https://busybox.net/downloads/busybox-$BUSYBOX_VERSION.tar.bz2"

clang_for_abi() {
  case "$1" in
    arm64-v8a) echo "aarch64-linux-android${ANDROID_API}-clang" ;;
    armeabi-v7a) echo "armv7a-linux-androideabi${ANDROID_API}-clang" ;;
    x86) echo "i686-linux-android${ANDROID_API}-clang" ;;
    x86_64) echo "x86_64-linux-android${ANDROID_API}-clang" ;;
    *) echo "unsupported ABI: $1" >&2; return 1 ;;
  esac
}

require_toolchain() {
  local abi="$1"
  local clang
  clang="$(clang_for_abi "$abi")"
  if [[ ! -x "$NDK_BIN/$clang" ]]; then
    echo "missing Android NDK clang for $abi: $NDK_BIN/$clang" >&2
    exit 1
  fi
}

patch_config() {
  python3 - <<'PY'
from pathlib import Path
import re
p = Path('.config')
s = p.read_text()

def setv(name, val):
    global s
    pat = re.compile(rf'^(# )?{name}(=.*| is not set)$', re.M)
    repl = f'{name}={val}' if val is not None else f'# {name} is not set'
    if pat.search(s):
        s = pat.sub(repl, s)
    else:
        s += '\n' + repl + '\n'

setv('CONFIG_CROSS_COMPILER_PREFIX', '""')
setv('CONFIG_SYSROOT', '""')
setv('CONFIG_EXTRA_CFLAGS', '"-DANDROID -D__ANDROID__"')
setv('CONFIG_EXTRA_LDFLAGS', '""')
setv('CONFIG_EXTRA_LDLIBS', '""')
for name in [
    'CONFIG_ASH', 'CONFIG_ASH_JOB_CONTROL', 'CONFIG_ASH_ALIAS', 'CONFIG_ASH_ECHO',
    'CONFIG_ASH_PRINTF', 'CONFIG_ASH_TEST', 'CONFIG_ASH_GETOPTS', 'CONFIG_ASH_HELP'
]:
    setv(name, 'y')
for name in [
    'CONFIG_LOADFONT', 'CONFIG_LOADKMAP', 'CONFIG_OPENVT', 'CONFIG_SELINUX',
    'CONFIG_FEATURE_MOUNT_LABEL', 'CONFIG_HOSTID', 'CONFIG_TC', 'CONFIG_SWAPON',
    'CONFIG_SWAPOFF', 'CONFIG_FEATURE_SWAPON_DISCARD', 'CONFIG_FEATURE_SWAPON_PRI',
    'CONFIG_SHA1_HWACCEL', 'CONFIG_SHA256_HWACCEL', 'CONFIG_SEEDRNG',
    'CONFIG_SSL_CLIENT', 'CONFIG_TLS', 'CONFIG_FEATURE_WGET_HTTPS',
    'CONFIG_FEATURE_WGET_OPENSSL'
]:
    setv(name, None)
p.write_text(s)
PY
}

write_android_compat_headers() {
  cat > include/byteswap.h <<'EOF_BYTESWAP'
#ifndef BUSYBOX_ANDROID_BYTESWAP_H
#define BUSYBOX_ANDROID_BYTESWAP_H
#define bswap_16(x) __builtin_bswap16(x)
#define bswap_32(x) __builtin_bswap32(x)
#define bswap_64(x) __builtin_bswap64(x)
#endif
EOF_BYTESWAP
}

build_one() {
  local abi="$1"
  local clang
  local work_dir
  local out_dir
  clang="$(clang_for_abi "$abi")"
  work_dir="$BUILD_DIR/build-$abi"
  out_dir="$ASSET_DIR/$abi"

  echo "==> Building BusyBox $BUSYBOX_VERSION for $abi"
  rm -rf "$work_dir"
  mkdir -p "$work_dir" "$out_dir"
  tar xf "$BUILD_DIR/busybox-$BUSYBOX_VERSION.tar.bz2" -C "$work_dir"
  cd "$work_dir/busybox-$BUSYBOX_VERSION"

  make android_ndk_defconfig >/dev/null
  patch_config
  write_android_compat_headers
  set +o pipefail
  yes '' | make CC="$NDK_BIN/$clang" HOSTCC=clang oldconfig >/dev/null
  local oldconfig_status="${PIPESTATUS[1]}"
  set -o pipefail
  if [[ "$oldconfig_status" -ne 0 ]]; then
    echo "oldconfig failed for $abi" >&2
    exit "$oldconfig_status"
  fi

  env -u SDKROOT -u CPPFLAGS make -j"$(sysctl -n hw.ncpu)" \
    CC="$NDK_BIN/$clang" \
    HOSTCC=clang \
    AR="$NDK_BIN/llvm-ar" \
    NM="$NDK_BIN/llvm-nm" \
    STRIP="$NDK_BIN/llvm-strip" \
    OBJCOPY="$NDK_BIN/llvm-objcopy" \
    OBJDUMP="$NDK_BIN/llvm-objdump" \
    busybox

  "$NDK_BIN/llvm-strip" busybox
  cp busybox "$out_dir/busybox"
  chmod 0644 "$out_dir/busybox"
  shasum -a 256 "$out_dir/busybox"
  file "$out_dir/busybox"
}

generate_metadata() {
  BUSYBOX_VERSION="$BUSYBOX_VERSION" \
  SOURCE_URL="$SOURCE_URL" \
  ASSET_DIR="$ASSET_DIR" \
  MANIFEST_PATH="$MANIFEST_PATH" \
  ABI_ORDER="$ABI_ORDER" \
  python3 - <<'PY'
from pathlib import Path
import hashlib
import json
import os

version = os.environ['BUSYBOX_VERSION']
source_url = os.environ['SOURCE_URL']
asset_dir = Path(os.environ['ASSET_DIR'])
manifest_path = Path(os.environ['MANIFEST_PATH'])
abi_order = os.environ['ABI_ORDER'].split()

abis = []
for abi in abi_order:
    busybox = asset_dir / abi / 'busybox'
    if not busybox.exists():
        continue
    data = busybox.read_bytes()
    sha = hashlib.sha256(data).hexdigest()
    asset_rel = f'toolbox/busybox/{abi}/busybox'
    abis.append({
        'abi': abi,
        'asset': asset_rel,
        'sha256': sha,
        'sizeBytes': len(data),
    })
    source_txt = asset_dir / abi / 'SOURCE.txt'
    source_txt.write_text(
        f'BusyBox {version}\n'
        f'ABI: {abi}\n'
        'License: GPL-2.0\n'
        f'Source: {source_url}\n'
        'Build script: tools/toolbox/build-busybox-android.sh\n'
        f'Asset: {asset_rel}\n'
        f'SHA256: {sha}\n',
        encoding='utf-8',
    )

manifest = {
    'schemaVersion': 1,
    'name': 'mira-toolbox',
    'packaging': 'apk-assets',
    'releaseMode': 'per-session-cache',
    'busybox': {
        'version': version,
        'license': 'GPL-2.0',
        'source': source_url,
        'buildScript': 'tools/toolbox/build-busybox-android.sh',
        'runtimeInstallMode': 'all applets from busybox --list',
        'installedAppletsSource': 'runtime busybox --list',
        'abis': abis,
    },
}
manifest_path.parent.mkdir(parents=True, exist_ok=True)
manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
PY
}

for abi in $ABI_LIST; do
  require_toolchain "$abi"
done

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR" "$ASSET_DIR"
cd "$BUILD_DIR"
curl -L --fail -o "busybox-$BUSYBOX_VERSION.tar.bz2" "$SOURCE_URL"

for abi in $ABI_LIST; do
  build_one "$abi"
done

generate_metadata
