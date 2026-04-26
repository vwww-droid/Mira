#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_ROOT="${1:-${ROOT_DIR}/android/app/build/generated/mira-toolbox-assets}"
# Must match the packaged Gadget major/minor line under android/app/src/main/jniLibs/.
FRIDA_VERSION="${MIRA_FRIDA_VERSION:-16.0.7}"
ANDROID_API="${MIRA_ANDROID_API:-23}"
NDK_VERSION="${MIRA_NDK_VERSION:-29.0.14206865}"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${HOME}/Library/Android/sdk}"
ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-${ANDROID_SDK_ROOT}/ndk/${NDK_VERSION}}"
HOST_TAG="darwin-x86_64"
TOOLCHAIN="${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/${HOST_TAG}/bin"
DEVKIT_DIR="${ROOT_DIR}/build/frida/devkit/${FRIDA_VERSION}/android-arm64"
DEVKIT_TAR="${DEVKIT_DIR}/frida-core-devkit-${FRIDA_VERSION}-android-arm64.tar.xz"
DEVKIT_URL="https://github.com/frida/frida/releases/download/${FRIDA_VERSION}/frida-core-devkit-${FRIDA_VERSION}-android-arm64.tar.xz"
SRC="${ROOT_DIR}/native/src/frida/frida_cli.c"
ABI="arm64-v8a"
ASSET_DIR="${OUT_ROOT}/toolbox/frida/${ABI}"
BIN_OUT="${ASSET_DIR}/frida"

mkdir -p "${DEVKIT_DIR}" "${ASSET_DIR}"

if [[ ! -f "${DEVKIT_DIR}/frida-core.h" || ! -f "${DEVKIT_DIR}/libfrida-core.a" ]]; then
  echo "[mira] fetching frida-core devkit ${FRIDA_VERSION} for android-arm64"
  curl -L --fail --retry 3 -o "${DEVKIT_TAR}" "${DEVKIT_URL}"
  tar -xJf "${DEVKIT_TAR}" -C "${DEVKIT_DIR}"
fi

CLANG="${TOOLCHAIN}/aarch64-linux-android${ANDROID_API}-clang"
STRIP="${TOOLCHAIN}/llvm-strip"

"${CLANG}" \
  -DANDROID \
  -ffunction-sections \
  -fdata-sections \
  -fPIE \
  -pie \
  -I"${DEVKIT_DIR}" \
  "${SRC}" \
  -o "${BIN_OUT}" \
  -L"${DEVKIT_DIR}" \
  -lfrida-core \
  -llog \
  -ldl \
  -lm \
  -pthread \
  -Wl,--gc-sections \
  -Wl,--export-dynamic

"${STRIP}" "${BIN_OUT}"

cat > "${ASSET_DIR}/SOURCE.txt" <<EOF
frida-core devkit ${FRIDA_VERSION}
source: ${DEVKIT_URL}
build-api: ${ANDROID_API}
EOF
