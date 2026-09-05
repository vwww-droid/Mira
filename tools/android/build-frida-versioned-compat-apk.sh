#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -x /usr/libexec/java_home ]]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 21)"
  else
    JAVA_BIN="$(command -v java)"
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "${JAVA_BIN}")")")"
  fi
fi
FRIDA_VERSION="${MIRA_FRIDA_VERSION:?MIRA_FRIDA_VERSION is required}"
STATE_VERSION="${MIRA_BOOTSTRAP_STATE_VERSION:?MIRA_BOOTSTRAP_STATE_VERSION is required}"
GENERATED_ASSETS="${ROOT_DIR}/android/app/build/generated/mira-toolbox-assets"
OUTPUT_DIR="${MIRA_FRIDA_COMPAT_OUTPUT_DIR:?MIRA_FRIDA_COMPAT_OUTPUT_DIR is required}"
OUTPUT_APK="${MIRA_FRIDA_COMPAT_OUTPUT_APK:?MIRA_FRIDA_COMPAT_OUTPUT_APK is required}"
UNSIGNED_APK="${ROOT_DIR}/android/app/build/outputs/apk/debug/mira-app-debug-unsigned.apk"
REPACKED_APK="${OUTPUT_DIR}/mira-app-${FRIDA_VERSION}-repacked-unsigned.apk"
ALIGNED_APK="${OUTPUT_DIR}/mira-app-${FRIDA_VERSION}-aligned-unsigned.apk"
BUILD_TOOLS="${ANDROID_SDK_ROOT:-${HOME}/Library/Android/sdk}/build-tools/36.1.0"
RESTORE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/mira-frida-versioned.XXXXXX")"
JNI_DIR="${ROOT_DIR}/android/app/src/main/jniLibs"

cp -a "${JNI_DIR}" "${RESTORE_DIR}/jniLibs"
if [[ -d "${GENERATED_ASSETS}" ]]; then cp -a "${GENERATED_ASSETS}" "${RESTORE_DIR}/generated-assets"; fi
(cd "${RESTORE_DIR}/jniLibs" && find . -type f -exec shasum -a 256 {} \; | sort) >"${RESTORE_DIR}/jni.sha256"
restore_baseline() {
  rm -rf "${JNI_DIR}"
  cp -a "${RESTORE_DIR}/jniLibs" "${JNI_DIR}"
  (cd "${JNI_DIR}" && find . -type f -exec shasum -a 256 {} \; | sort) >"${RESTORE_DIR}/jni-restored.sha256"
  cmp "${RESTORE_DIR}/jni.sha256" "${RESTORE_DIR}/jni-restored.sha256"
  rm -rf "${GENERATED_ASSETS}"
  if [[ -d "${RESTORE_DIR}/generated-assets" ]]; then cp -a "${RESTORE_DIR}/generated-assets" "${GENERATED_ASSETS}"; fi
  rm -rf "${RESTORE_DIR}"
}
trap restore_baseline EXIT

mkdir -p "${OUTPUT_DIR}"
MIRA_FRIDA_VERSION="${FRIDA_VERSION}" "${ROOT_DIR}/tools/android/prepare-frida-gadget.sh"
env JAVA_HOME="${JAVA_HOME}" MIRA_FRIDA_VERSION="${FRIDA_VERSION}" \
  "${ROOT_DIR}/gradlew" -p "${ROOT_DIR}" :mira-app:assembleDebug \
  -PmiraBootstrapStateVersion="${STATE_VERSION}" -PmiraUnsignedDebug=true
python3 "${ROOT_DIR}/tools/android/repack-apk.py" "${UNSIGNED_APK}" "${REPACKED_APK}"
"${BUILD_TOOLS}/zipalign" -f -P 16 4 "${REPACKED_APK}" "${ALIGNED_APK}"
"${BUILD_TOOLS}/apksigner" sign --ks "${HOME}/.android/debug.keystore" \
  --ks-pass pass:android --key-pass pass:android --out "${OUTPUT_APK}" "${ALIGNED_APK}"
"${BUILD_TOOLS}/zipalign" -c -P 16 4 "${OUTPUT_APK}"
"${BUILD_TOOLS}/apksigner" verify --verbose --print-certs "${OUTPUT_APK}"
shasum -a 256 "${OUTPUT_APK}"
echo "Built ${OUTPUT_APK}"
