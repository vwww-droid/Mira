#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APP_ID="${MIRA_ANDROID_APP_ID:-com.vwww.mira}"
ACTIVITY="${MIRA_ANDROID_ACTIVITY:-com.vwww.mira/.MainActivity}"
APK_PATH="${MIRA_ANDROID_APK_PATH:-${ROOT_DIR}/android/app/build/outputs/apk/debug/mira-app-debug.apk}"
AUTO_LAUNCH="${MIRA_ANDROID_AUTO_LAUNCH:-1}"
AUTO_OPEN_RELAY_CONSOLE="${MIRA_ANDROID_AUTO_OPEN_RELAY_CONSOLE:-1}"
SETUP_DEVICE_FORWARD="${MIRA_ANDROID_SETUP_DEVICE_FORWARD:-1}"
AUTO_START_LOCAL_RELAY="${MIRA_ANDROID_AUTO_START_LOCAL_RELAY:-1}"
HOST_RELAY_PORT="${MIRA_ANDROID_HOST_RELAY_PORT:-8765}"
FORWARD_HOST_PORT="${MIRA_ANDROID_FORWARD_HOST_PORT:-8767}"
RELAY_URL="${MIRA_ANDROID_RELAY_URL:-}"
OPEN_URL="${MIRA_ANDROID_OPEN_URL:-}"
RELAY_LOG_PATH="${MIRA_ANDROID_RELAY_LOG_PATH:-${ROOT_DIR}/build/mira-local-relay.log}"

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  cat <<'MSG'
Usage: ./mira-android

Builds the Mira Android app, ensures a local Relay is running, installs it with adb,
launches it with a Relay URL that the phone can reach over LAN, and opens the Relay console on the computer.
Optional adb forward for the device-local web terminal is available for debug only.

Environment variables:
  MIRA_ANDROID_APP_ID              Android applicationId. Default com.vwww.mira
  MIRA_ANDROID_ACTIVITY            Launch activity. Default com.vwww.mira/.MainActivity
  MIRA_ANDROID_APK_PATH            Built APK path
  MIRA_ANDROID_AUTO_LAUNCH         1 to launch after install. Default 1
  MIRA_ANDROID_AUTO_OPEN_RELAY_CONSOLE  1 to open Relay console on computer. Default 1
  MIRA_ANDROID_SETUP_DEVICE_FORWARD 1 to also parse device-local terminal URL and adb forward it. Default 1
  MIRA_ANDROID_AUTO_START_LOCAL_RELAY 1 to auto-start ./mira-local-web when localhost relay is missing. Default 1
  MIRA_ANDROID_HOST_RELAY_PORT     Host Relay port. Default 8765
  MIRA_ANDROID_FORWARD_HOST_PORT   Host port used by adb forward. Default 8767
  MIRA_ANDROID_RELAY_URL           Relay URL injected into app extras.
                                   Default http://<host-lan-ip>:<host-relay-port>
  MIRA_ANDROID_OPEN_URL            Browser URL to open on computer.
                                   Default http://127.0.0.1:<host-relay-port>
  MIRA_ANDROID_RELAY_LOG_PATH      Log file for auto-started local relay
  MIRA_ANDROID_ADB_SERIAL          adb serial to target. If empty, use default adb device
MSG
  exit 0
fi

adb_cmd() {
  if [[ -n "${MIRA_ANDROID_ADB_SERIAL:-}" ]]; then
    adb -s "${MIRA_ANDROID_ADB_SERIAL}" "$@"
  else
    adb "$@"
  fi
}

open_url() {
  local url="$1"
  if [[ -z "${url}" ]]; then
    return 0
  fi
  if command -v open >/dev/null 2>&1; then
    open "${url}" >/dev/null 2>&1 || true
  elif command -v xdg-open >/dev/null 2>&1; then
    xdg-open "${url}" >/dev/null 2>&1 || true
  fi
}

require_cmd() {
  local name="$1"
  if ! command -v "${name}" >/dev/null 2>&1; then
    echo "${name} not found." >&2
    exit 1
  fi
}

is_local_relay_ready() {
  python3 - "$1" <<'PY'
import sys
import urllib.request

port = sys.argv[1]
for host in (f"http://127.0.0.1:{port}", f"http://localhost:{port}"):
    try:
        with urllib.request.urlopen(host + "/api/devices", timeout=1.5) as r:
            if r.status == 200:
                sys.exit(0)
    except Exception:
        pass
sys.exit(1)
PY
}

start_local_relay_if_needed() {
  if is_local_relay_ready "${HOST_RELAY_PORT}"; then
    return 0
  fi

  if [[ "${AUTO_START_LOCAL_RELAY}" != "1" ]]; then
    echo "Relay is not running on 127.0.0.1:${HOST_RELAY_PORT}. Start ./mira-local-web first or enable MIRA_ANDROID_AUTO_START_LOCAL_RELAY=1." >&2
    exit 1
  fi

  echo "Starting local relay via ./mira-local-web ..."
  mkdir -p "$(dirname "${RELAY_LOG_PATH}")"
  (
    cd "${ROOT_DIR}"
    nohup env MIRA_RELAY_PORT="${HOST_RELAY_PORT}" ./mira-local-web >"${RELAY_LOG_PATH}" 2>&1 &
  )

  local i
  for i in {1..60}; do
    if is_local_relay_ready "${HOST_RELAY_PORT}"; then
      echo "Local relay is ready on http://127.0.0.1:${HOST_RELAY_PORT}"
      return 0
    fi
    sleep 1
  done

  echo "Local relay did not become ready. Check ${RELAY_LOG_PATH}" >&2
  exit 1
}

detect_host_lan_ip() {
  local iface=""
  local ip=""

  if command -v route >/dev/null 2>&1; then
    iface="$(route get default 2>/dev/null | awk '/interface:/{print $2; exit}')"
  fi

  if [[ -n "${iface}" ]] && command -v ipconfig >/dev/null 2>&1; then
    ip="$(ipconfig getifaddr "${iface}" 2>/dev/null || true)"
    if [[ -n "${ip}" ]]; then
      printf '%s\n' "${ip}"
      return 0
    fi
  fi

  python3 - <<'PY'
import ipaddress
import socket
import subprocess

def valid(ip: str) -> bool:
    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return False
    return addr.version == 4 and not addr.is_loopback and not addr.is_link_local

ips = []
try:
    data = subprocess.run(["ifconfig"], capture_output=True, text=True, check=False).stdout
    for line in data.splitlines():
        line = line.strip()
        if not line.startswith("inet "):
            continue
        parts = line.split()
        if len(parts) >= 2 and valid(parts[1]):
            ips.append(parts[1])
except Exception:
    pass

private = []
for ip in ips:
    try:
        addr = ipaddress.ip_address(ip)
        if addr.is_private:
            private.append(ip)
    except ValueError:
        pass

if private:
    print(private[0])
elif ips:
    print(ips[0])
PY
}

wait_for_terminal_url() {
  python3 - "$@" <<'PY'
import re
import subprocess
import sys
import time

adb = sys.argv[1:]
pattern = re.compile(r"Mira Web Terminal listening on (http://127\.0\.0\.1:(\d+)/\?token=([A-Za-z0-9_\-+/=]+))")
deadline = time.time() + 25
while time.time() < deadline:
    proc = subprocess.run(adb + ["logcat", "-d", "-s", "MiraDiscovery:I"], capture_output=True, text=True)
    text = (proc.stdout or "") + "\n" + (proc.stderr or "")
    match = pattern.search(text)
    if match:
      print(match.group(1))
      print(match.group(2))
      print(match.group(3))
      sys.exit(0)
    time.sleep(1)
sys.exit(1)
PY
}

require_cmd adb
require_cmd python3

if [[ ! -x "${ROOT_DIR}/gradlew" ]]; then
  echo "Missing gradlew at ${ROOT_DIR}/gradlew" >&2
  exit 1
fi

if [[ -z "${RELAY_URL}" ]]; then
  HOST_LAN_IP="$(detect_host_lan_ip)"
  if [[ -z "${HOST_LAN_IP}" ]]; then
    echo "Unable to detect host LAN IP. Set MIRA_ANDROID_RELAY_URL manually." >&2
    exit 1
  fi
  RELAY_URL="http://${HOST_LAN_IP}:${HOST_RELAY_PORT}"
else
  HOST_LAN_IP=""
fi

if [[ -z "${OPEN_URL}" ]]; then
  OPEN_URL="http://127.0.0.1:${HOST_RELAY_PORT}"
fi

start_local_relay_if_needed

echo "Building Mira Android debug APK ..."
(cd "${ROOT_DIR}" && ./gradlew :mira-app:assembleDebug)

if [[ ! -f "${APK_PATH}" ]]; then
  echo "Built APK not found: ${APK_PATH}" >&2
  exit 1
fi

echo "Installing Mira Android APK ..."
adb_cmd install -r "${APK_PATH}"

if [[ "${AUTO_LAUNCH}" != "1" ]]; then
  cat <<MSG

Mira Android app installed.
APK: ${APK_PATH}
Package: ${APP_ID}
Relay: ${RELAY_URL}

MSG
  exit 0
fi

echo "Clearing relevant logcat buffers ..."
adb_cmd logcat -c || true

echo "Launching Mira Android app with relay ${RELAY_URL} ..."
adb_cmd shell am force-stop "${APP_ID}" >/dev/null 2>&1 || true
adb_cmd shell am start \
  -n "${ACTIVITY}" \
  -a android.intent.action.MAIN \
  -c android.intent.category.LAUNCHER \
  --es mira_relay_url "${RELAY_URL}" \
  --ez mira_auto_connect true

if [[ "${AUTO_OPEN_RELAY_CONSOLE}" == "1" ]]; then
  echo "Opening Relay console ${OPEN_URL} ..."
  open_url "${OPEN_URL}"
fi

DEVICE_LOCAL_URL=""
DEVICE_LOCAL_PORT=""
DEVICE_LOCAL_TOKEN=""

if [[ "${SETUP_DEVICE_FORWARD}" == "1" ]]; then
  echo "Waiting for device-local terminal URL from logcat ..."
  if [[ -n "${MIRA_ANDROID_ADB_SERIAL:-}" ]]; then
    mapfile -t TERMINAL_INFO < <(wait_for_terminal_url adb -s "${MIRA_ANDROID_ADB_SERIAL}")
  else
    mapfile -t TERMINAL_INFO < <(wait_for_terminal_url adb)
  fi

  if [[ "${#TERMINAL_INFO[@]}" -lt 3 ]]; then
    echo "Unable to parse device-local terminal URL from logcat." >&2
    echo "Expected MiraDiscovery log line like: Mira Web Terminal listening on http://127.0.0.1:<port>/?token=<token>" >&2
    exit 1
  fi

  DEVICE_LOCAL_URL="${TERMINAL_INFO[0]}"
  DEVICE_LOCAL_PORT="${TERMINAL_INFO[1]}"
  DEVICE_LOCAL_TOKEN="${TERMINAL_INFO[2]}"

  echo "Configuring adb forward tcp:${FORWARD_HOST_PORT} -> tcp:${DEVICE_LOCAL_PORT} ..."
  adb_cmd forward "tcp:${FORWARD_HOST_PORT}" "tcp:${DEVICE_LOCAL_PORT}"
fi

cat <<MSG

Mira Android app installed and launched.
APK: ${APK_PATH}
Package: ${APP_ID}
Relay for phone: ${RELAY_URL}
Relay console on computer: ${OPEN_URL}
Device local page: ${DEVICE_LOCAL_URL:-<disabled>}
adb forward: $( [[ "${SETUP_DEVICE_FORWARD}" == "1" ]] && echo "tcp:${FORWARD_HOST_PORT} -> tcp:${DEVICE_LOCAL_PORT}" || echo "<disabled>" )

MSG
