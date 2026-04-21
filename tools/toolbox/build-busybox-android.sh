#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BUSYBOX_VERSION="${BUSYBOX_VERSION:-1.37.0}"
ANDROID_API="${ANDROID_API:-23}"
NDK_DIR="${NDK_DIR:-$HOME/Library/Android/sdk/ndk/29.0.14206865}"
NDK_BIN="$NDK_DIR/toolchains/llvm/prebuilt/darwin-x86_64/bin"
BUILD_DIR="${BUILD_DIR:-$ROOT_DIR/.mira/tmp/busybox-android}"
OUT_DIR="$ROOT_DIR/android/app/src/main/assets/toolbox/busybox/arm64-v8a"

if [[ ! -x "$NDK_BIN/aarch64-linux-android${ANDROID_API}-clang" ]]; then
  echo "missing Android NDK clang: $NDK_BIN/aarch64-linux-android${ANDROID_API}-clang" >&2
  exit 1
fi

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR" "$OUT_DIR"
cd "$BUILD_DIR"

curl -L --fail -o "busybox-$BUSYBOX_VERSION.tar.bz2" "https://busybox.net/downloads/busybox-$BUSYBOX_VERSION.tar.bz2"
tar xf "busybox-$BUSYBOX_VERSION.tar.bz2"
cd "busybox-$BUSYBOX_VERSION"

make android_ndk_defconfig >/dev/null

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
    'CONFIG_SHA1_HWACCEL', 'CONFIG_SHA256_HWACCEL', 'CONFIG_SEEDRNG'
]:
    setv(name, None)
p.write_text(s)
PY

cat > include/byteswap.h <<'EOF_BYTESWAP'
#ifndef BUSYBOX_ANDROID_BYTESWAP_H
#define BUSYBOX_ANDROID_BYTESWAP_H
#define bswap_16(x) __builtin_bswap16(x)
#define bswap_32(x) __builtin_bswap32(x)
#define bswap_64(x) __builtin_bswap64(x)
#endif
EOF_BYTESWAP

yes '' | make oldconfig >/dev/null

env -u SDKROOT -u CPPFLAGS make -j"$(sysctl -n hw.ncpu)" \
  CC="$NDK_BIN/aarch64-linux-android${ANDROID_API}-clang" \
  HOSTCC=clang \
  AR="$NDK_BIN/llvm-ar" \
  NM="$NDK_BIN/llvm-nm" \
  STRIP="$NDK_BIN/llvm-strip" \
  OBJCOPY="$NDK_BIN/llvm-objcopy" \
  OBJDUMP="$NDK_BIN/llvm-objdump" \
  busybox

"$NDK_BIN/llvm-strip" busybox
cp busybox "$OUT_DIR/busybox"
chmod 0644 "$OUT_DIR/busybox"
shasum -a 256 "$OUT_DIR/busybox"
file "$OUT_DIR/busybox"
