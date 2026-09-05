#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FRIDA_VERSION="${MIRA_FRIDA_VERSION:-16.0.7}"
CACHE_DIR="${ROOT_DIR}/build/frida/android-gadget/${FRIDA_VERSION}"
JNI_DIR="${ROOT_DIR}/android/app/src/main/jniLibs"

prepare_abi() {
  local abi="$1"
  local release_arch="$2"
  local archive_sha256="$3"
  local library_sha256="$4"
  local abi_cache="${CACHE_DIR}/${abi}"
  local archive="${abi_cache}/gadget.so.xz"
  local library="${abi_cache}/gadget.so"
  local url="https://github.com/frida/frida/releases/download/${FRIDA_VERSION}/frida-gadget-${FRIDA_VERSION}-android-${release_arch}.so.xz"

  mkdir -p "${abi_cache}" "${JNI_DIR}/${abi}"
  if [[ ! -s "${archive}" ]] || [[ "$(shasum -a 256 "${archive}" | awk '{print $1}')" != "${archive_sha256}" ]]; then
    rm -f "${archive}"
    curl -L --fail --retry 5 --retry-all-errors --retry-delay 2 \
      --connect-timeout 20 --max-time 300 -o "${archive}" "${url}"
  fi
  [[ "$(shasum -a 256 "${archive}" | awk '{print $1}')" == "${archive_sha256}" ]]

  xz -dkf "${archive}"
  [[ "$(shasum -a 256 "${library}" | awk '{print $1}')" == "${library_sha256}" ]]
  cp "${library}" "${JNI_DIR}/${abi}/libdynamic.so"
  chmod 0644 "${JNI_DIR}/${abi}/libdynamic.so"
}

case "${FRIDA_VERSION}" in
  16.0.7)
    prepare_abi arm64-v8a arm64 \
      2edee134607f260899693349d51f6a24941458fd4c1075967cadffc9c131c377 \
      82bddf78d855e48a62c8b63c7809d68271b33f2c6278098645dd8f6a4a266654
    prepare_abi armeabi-v7a arm \
      868fbc7de50e736f37c3ef4802b44005592a346bc1d68d9c08d5eff62d890a3f \
      e73939ccfd2b5cddf788ae523ba593f0ba48996604eb9d551fc7a65efc0b9e34
    ;;
  16.7.19)
    prepare_abi arm64-v8a arm64 \
      8cee315d38b7c67925219082df6ae0b72e7d2e337113eb26a4d3c466d4b520fe \
      61be4bbeae285bb19164dc9eebee58669f6ad2ab4b4d64422ea0a779731b0898
    prepare_abi armeabi-v7a arm \
      85604595503d8742efb9749542eab797dc20caa9c09856b04344ca4e56addb40 \
      ffa00a56ca8e3eafd3642acec1e569e3c1e0f6a30383344d2b44d1364eacc544
    ;;
  *) echo "Unsupported pinned Frida version: ${FRIDA_VERSION}" >&2; exit 2 ;;
esac


echo "Prepared Frida ${FRIDA_VERSION} Android Gadget."
