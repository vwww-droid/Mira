#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROJECT_PATH="${ROOT_DIR}/ios/Mira/Mira.xcodeproj"
SCHEME="${MIRA_IOS_SCHEME:-Mira}"
DEVICE_NAME="${MIRA_IOS_DEVICE:-iPhone 17 Pro}"
BUNDLE_ID="${MIRA_IOS_BUNDLE_ID:-com.vwww.mira.ios}"
DERIVED_DATA="${ROOT_DIR}/build/ios/DerivedData"
APP_PATH="${DERIVED_DATA}/Build/Products/Debug-iphonesimulator/Mira.app"

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  cat <<'MSG'
Usage: ./mira-ios

Builds the Mira iOS app, installs it into the booted iOS Simulator, and launches it.

Environment variables:
  MIRA_IOS_DEVICE     Simulator device name, default iPhone 17 Pro
  MIRA_IOS_SCHEME     Xcode scheme, default Mira
  MIRA_IOS_BUNDLE_ID  Bundle identifier, default com.vwww.mira.ios
MSG
  exit 0
fi

if ! command -v xcodebuild >/dev/null 2>&1; then
  echo "xcodebuild not found. Install Xcode and run xcode-select first." >&2
  exit 1
fi
if ! command -v xcrun >/dev/null 2>&1; then
  echo "xcrun not found. Install Xcode and run xcode-select first." >&2
  exit 1
fi
if [[ ! -d "${PROJECT_PATH}" ]]; then
  echo "Missing iOS project: ${PROJECT_PATH}" >&2
  exit 1
fi

booted_device="$(xcrun simctl list devices booted | awk -F '[()]' '/Booted/ {print $2; exit}')"
if [[ -z "${booted_device}" ]]; then
  echo "Booting simulator: ${DEVICE_NAME}"
  xcrun simctl boot "${DEVICE_NAME}"
fi
open -a Simulator

env -u SDKROOT -u LIBRARY_PATH xcodebuild \
  -project "${PROJECT_PATH}" \
  -scheme "${SCHEME}" \
  -configuration Debug \
  -destination "platform=iOS Simulator,name=${DEVICE_NAME}" \
  -derivedDataPath "${DERIVED_DATA}" \
  CODE_SIGNING_ALLOWED=NO \
  build

if [[ ! -d "${APP_PATH}" ]]; then
  echo "Built app not found: ${APP_PATH}" >&2
  exit 1
fi

xcrun simctl install booted "${APP_PATH}"
xcrun simctl launch booted "${BUNDLE_ID}"

cat <<MSG

Mira iOS app launched.
Project: ${PROJECT_PATH}
Device: ${DEVICE_NAME}
Bundle: ${BUNDLE_ID}
App: ${APP_PATH}

MSG
