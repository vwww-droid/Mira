#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PORT="${MIRA_RELAY_PORT:-8765}"
HOST="${MIRA_RELAY_HOST:-127.0.0.1}"
PYTHON_BIN="${PYTHON_BIN:-python3}"
CLOUDFLARED_BIN="${CLOUDFLARED_BIN:-cloudflared}"
CONSOLE_DIR="${ROOT_DIR}/apps/console"
LOG_FILE=""
CLOUDFLARED_PID=""

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  cat <<'MSG'
Usage: tools/relay/start-public-relay.sh

Starts a Mira relay server and a random Cloudflare quick tunnel.

Environment variables:
  MIRA_RELAY_PORT   Relay port, default 8765
  MIRA_RELAY_HOST   Relay bind host, default 127.0.0.1
  PYTHON_BIN        Python command, default python3
  CLOUDFLARED_BIN   cloudflared command, default cloudflared
  MIRA_SKIP_CONSOLE_BUILD
                   Set to 1 to skip building apps/console
MSG
  exit 0
fi

cleanup() {
  if [[ -n "${CLOUDFLARED_PID}" ]] && kill -0 "${CLOUDFLARED_PID}" 2>/dev/null; then
    kill "${CLOUDFLARED_PID}" 2>/dev/null || true
    wait "${CLOUDFLARED_PID}" 2>/dev/null || true
  fi
  if [[ -n "${LOG_FILE}" ]]; then
    rm -f "${LOG_FILE}"
  fi
}
trap cleanup EXIT INT TERM

LOG_FILE="$(mktemp -t mira-cloudflared.XXXXXX.log)"

if ! command -v "${CLOUDFLARED_BIN}" >/dev/null 2>&1; then
  echo "cloudflared not found. Install it first, then rerun this script." >&2
  echo "macOS: brew install cloudflare/cloudflare/cloudflared" >&2
  exit 1
fi

cd "${ROOT_DIR}"

if [[ "${MIRA_SKIP_CONSOLE_BUILD:-0}" != "1" && -f "${CONSOLE_DIR}/package.json" ]]; then
  if command -v npm >/dev/null 2>&1; then
    echo "Building Mira console ..."
    if [[ ! -d "${CONSOLE_DIR}/node_modules" ]]; then
      npm --prefix "${CONSOLE_DIR}" ci
    fi
    npm --prefix "${CONSOLE_DIR}" run build
  elif [[ ! -f "${CONSOLE_DIR}/out/index.html" ]]; then
    echo "npm not found and apps/console/out is missing. Build the console first." >&2
    exit 1
  else
    echo "npm not found. Using existing apps/console/out." >&2
  fi
fi

echo "Starting Cloudflare quick tunnel for http://127.0.0.1:${PORT} ..."
"${CLOUDFLARED_BIN}" tunnel \
  --edge-ip-version 4 \
  --protocol http2 \
  --url "http://127.0.0.1:${PORT}" \
  >"${LOG_FILE}" 2>&1 &
CLOUDFLARED_PID="$!"

PUBLIC_URL=""
for _ in $(seq 1 60); do
  if ! kill -0 "${CLOUDFLARED_PID}" 2>/dev/null; then
    cat "${LOG_FILE}" >&2 || true
    echo "cloudflared exited before publishing a URL." >&2
    exit 1
  fi
  PUBLIC_URL="$(grep -Eo 'https://[-a-z0-9]+\.trycloudflare\.com' "${LOG_FILE}" | head -n 1 || true)"
  if [[ -n "${PUBLIC_URL}" ]]; then
    break
  fi
  sleep 0.5
done

if [[ -z "${PUBLIC_URL}" ]]; then
  cat "${LOG_FILE}" >&2 || true
  echo "Timed out waiting for Cloudflare quick tunnel URL." >&2
  exit 1
fi

cat <<MSG

Mira Relay is starting.
Browser URL: ${PUBLIC_URL}
Android Relay URL: ${PUBLIC_URL}

Open the browser URL on your computer.
On the phone, open Mira APK, paste Android Relay URL, then tap Connect Relay.
Press Ctrl-C to stop both relay and tunnel.

MSG

"${PYTHON_BIN}" -m mira.relay.server \
  --host "${HOST}" \
  --port "${PORT}" \
  --advertise-url "${PUBLIC_URL}"
