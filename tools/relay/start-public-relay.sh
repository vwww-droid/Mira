#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PORT="${MIRA_RELAY_PORT:-8765}"
HOST="${MIRA_RELAY_HOST:-0.0.0.0}"
PYTHON_BIN="${PYTHON_BIN:-python3}"
CLOUDFLARED_BIN="${CLOUDFLARED_BIN:-cloudflared}"
CONSOLE_DIR="${ROOT_DIR}/apps/console"
LOG_FILE=""
CLOUDFLARED_PID=""
RELAY_PID=""
ADVERTISE_URL_FILE=""
LOCAL_ADVERTISE_URL="${MIRA_LOCAL_ADVERTISE_URL:-http://localhost:${PORT}}"
PUBLIC_URL="${MIRA_PUBLIC_URL:-}"
TUNNEL_ATTEMPTS="${MIRA_TUNNEL_ATTEMPTS:-0}"
TUNNEL_URL_TIMEOUT_SECONDS="${MIRA_TUNNEL_URL_TIMEOUT_SECONDS:-30}"
TUNNEL_DNS_TIMEOUT_SECONDS="${MIRA_TUNNEL_DNS_TIMEOUT_SECONDS:-45}"
TUNNEL_HTTP_TIMEOUT_SECONDS="${MIRA_TUNNEL_HTTP_TIMEOUT_SECONDS:-30}"
TUNNEL_STRICT_CHECK="${MIRA_TUNNEL_STRICT_CHECK:-1}"

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  cat <<'MSG'
Usage: tools/relay/start-public-relay.sh

Starts a Mira relay server and a public tunnel.

Environment variables:
  MIRA_RELAY_PORT   Relay port, default 8765
  MIRA_RELAY_HOST   Relay bind host, default 0.0.0.0
  PYTHON_BIN        Python command, default python3
  CLOUDFLARED_BIN   cloudflared command, default cloudflared
  MIRA_SKIP_CONSOLE_BUILD
                   Set to 1 to skip building apps/console
  MIRA_RELAY_AUTO_KILL_PORT_PROCESS
                   Set to 1 to terminate the process currently listening on
                   MIRA_RELAY_PORT without prompting
  MIRA_LOCAL_ADVERTISE_URL
                   Local browser URL before the public tunnel is ready,
                   default http://localhost:8765
  MIRA_PUBLIC_URL
                   Existing public tunnel URL from cpolar, frp, NATAPP, or
                   another provider. When set, Cloudflare quick tunnel is skipped.
  MIRA_LAN_RELAY_URL
                   LAN URL shown for Android devices on the same Wi-Fi,
                   auto-detected by default
  MIRA_TUNNEL_ATTEMPTS
                   Cloudflare quick tunnel creation attempts, default 0 means
                   keep retrying random hostnames until one is reachable
  MIRA_TUNNEL_URL_TIMEOUT_SECONDS
                   Seconds to wait for cloudflared to print a URL, default 30
  MIRA_TUNNEL_DNS_TIMEOUT_SECONDS
                   Seconds to wait for the printed URL to resolve in DNS, default 45
  MIRA_TUNNEL_HTTP_TIMEOUT_SECONDS
                   Seconds to wait for the printed URL to return HTTP, default 30
  MIRA_TUNNEL_STRICT_CHECK
                   Set to 0 to print the tunnel URL as soon as cloudflared publishes it.
                   Default 1 waits until DNS and direct HTTP checks pass, which is
                   safer for Android phones outside the local network.
MSG
  exit 0
fi

stop_cloudflared() {
  if [[ -n "${CLOUDFLARED_PID}" ]] && kill -0 "${CLOUDFLARED_PID}" 2>/dev/null; then
    kill "${CLOUDFLARED_PID}" 2>/dev/null || true
    wait "${CLOUDFLARED_PID}" 2>/dev/null || true
  fi
  CLOUDFLARED_PID=""
  if [[ -n "${LOG_FILE}" ]]; then
    rm -f "${LOG_FILE}"
  fi
  LOG_FILE=""
}

stop_relay() {
  if [[ -n "${RELAY_PID}" ]] && kill -0 "${RELAY_PID}" 2>/dev/null; then
    kill "${RELAY_PID}" 2>/dev/null || true
    wait "${RELAY_PID}" 2>/dev/null || true
  fi
  RELAY_PID=""
  if [[ -n "${ADVERTISE_URL_FILE}" ]]; then
    rm -f "${ADVERTISE_URL_FILE}"
  fi
  ADVERTISE_URL_FILE=""
}

cleanup() {
  stop_cloudflared
  stop_relay
}
trap cleanup EXIT INT TERM

port_listener_pids() {
  if ! command -v lsof >/dev/null 2>&1; then
    return 0
  fi
  lsof -nP -tiTCP:"${PORT}" -sTCP:LISTEN 2>/dev/null | sort -u
}

print_port_listeners() {
  local pid process_name process_command

  echo "Port ${PORT} is already in use by:"
  printf '  %-8s %-24s %s\n' "PID" "Process name" "Command"
  for pid in "$@"; do
    process_name="$(ps -p "${pid}" -o comm= 2>/dev/null | xargs basename 2>/dev/null || true)"
    process_command="$(ps -p "${pid}" -o command= 2>/dev/null || true)"
    printf '  %-8s %-24s %s\n' "${pid}" "${process_name:-unknown}" "${process_command:-unknown}"
  done
}

terminate_port_listeners() {
  local pid

  for pid in "$@"; do
    if kill -0 "${pid}" 2>/dev/null; then
      kill "${pid}" 2>/dev/null || true
    fi
  done

  for _ in $(seq 1 20); do
    if [[ -z "$(port_listener_pids)" ]]; then
      echo "Port ${PORT} is free now."
      return 0
    fi
    sleep 0.2
  done

  echo "The process did not exit after SIGTERM. Please stop it manually, then rerun this script." >&2
  exit 1
}

ensure_port_available() {
  local listener_pids=()
  local answer=""

  if ! command -v lsof >/dev/null 2>&1; then
    echo "lsof not found. Skipping port owner check for ${PORT}." >&2
    return 0
  fi

  while IFS= read -r pid; do
    [[ -n "${pid}" ]] && listener_pids+=("${pid}")
  done < <(port_listener_pids)

  if [[ "${#listener_pids[@]}" -eq 0 ]]; then
    return 0
  fi

  print_port_listeners "${listener_pids[@]}" >&2

  if [[ "${MIRA_RELAY_AUTO_KILL_PORT_PROCESS:-0}" == "1" ]]; then
    echo "MIRA_RELAY_AUTO_KILL_PORT_PROCESS=1, terminating the listed process(es)." >&2
    terminate_port_listeners "${listener_pids[@]}"
    return 0
  fi

  if [[ ! -t 0 ]]; then
    echo "Cannot ask for confirmation because stdin is not interactive." >&2
    echo "Stop the process above manually, or rerun with MIRA_RELAY_AUTO_KILL_PORT_PROCESS=1." >&2
    exit 1
  fi

  read -r -p "Terminate the listed process(es) and continue? [y/N] " answer
  case "${answer}" in
    y | Y | yes | YES | Yes)
      terminate_port_listeners "${listener_pids[@]}"
      ;;
    *)
      echo "Canceled. Stop the process above or choose another port with MIRA_RELAY_PORT." >&2
      exit 1
      ;;
  esac
}

url_host() {
  local url host

  url="$1"
  host="${url#https://}"
  host="${host#http://}"
  host="${host%%/*}"
  echo "${host}"
}

detect_lan_ip() {
  local default_interface lan_ip

  default_interface="$(route -n get default 2>/dev/null | awk '/interface:/ {print $2; exit}' || true)"
  if [[ -n "${default_interface}" ]]; then
    lan_ip="$(ifconfig "${default_interface}" 2>/dev/null | awk '/inet / && $2 != "127.0.0.1" && $2 !~ /^198\\.18\\./ {print $2; exit}' || true)"
    if [[ -n "${lan_ip}" ]]; then
      echo "${lan_ip}"
      return 0
    fi
  fi

  ifconfig 2>/dev/null | awk '/inet / && $2 != "127.0.0.1" && $2 !~ /^198\\.18\\./ {print $2; exit}'
}

lan_relay_url() {
  local lan_ip

  if [[ -n "${MIRA_LAN_RELAY_URL:-}" ]]; then
    echo "${MIRA_LAN_RELAY_URL}"
    return 0
  fi
  lan_ip="$(detect_lan_ip)"
  if [[ -n "${lan_ip}" ]]; then
    echo "http://${lan_ip}:${PORT}"
  fi
}

hostname_resolves() {
  local host

  host="$1"
  "${PYTHON_BIN}" - "${host}" <<'PY' >/dev/null 2>&1
import random
import socket
import struct
import sys

host = sys.argv[1]


def system_dns_resolves() -> bool:
    try:
        socket.getaddrinfo(host, 443)
        return True
    except OSError:
        return False


def encode_name(value: str) -> bytes:
    return b"".join(bytes([len(part)]) + part.encode("ascii") for part in value.split(".")) + b"\0"


def public_dns_resolves(server: str, qtype: int) -> bool:
    query_id = random.randrange(0, 65536)
    packet = (
        struct.pack("!HHHHHH", query_id, 0x0100, 1, 0, 0, 0)
        + encode_name(host)
        + struct.pack("!HH", qtype, 1)
    )
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.settimeout(2)
        sock.sendto(packet, (server, 53))
        data, _ = sock.recvfrom(512)
    if len(data) < 12:
        return False
    response_id, _flags, _qdcount, ancount, _nscount, _arcount = struct.unpack("!HHHHHH", data[:12])
    return response_id == query_id and ancount > 0 and (data[3] & 0x0F) == 0


if system_dns_resolves():
    raise SystemExit(0)

for dns_server in ("1.1.1.1", "8.8.8.8"):
    for dns_type in (1, 28):
        try:
            if public_dns_resolves(dns_server, dns_type):
                raise SystemExit(0)
        except OSError:
            pass

raise SystemExit(1)
PY
}

http_get_succeeds() {
  local url

  url="$1"
  "${PYTHON_BIN}" - "${url}" <<'PY' >/dev/null 2>&1
import sys
import urllib.request

request = urllib.request.Request(sys.argv[1], headers={"User-Agent": "mira-startup-check"})
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
with opener.open(request, timeout=2) as response:
    if 200 <= response.status < 500:
        sys.exit(0)
    sys.exit(1)
PY
}

update_advertise_url() {
  local advertise_url

  advertise_url="$1"
  printf '%s\n' "${advertise_url}" >"${ADVERTISE_URL_FILE}"
}

wait_for_local_relay() {
  for _ in $(seq 1 60); do
    if [[ -n "${RELAY_PID}" ]] && ! kill -0 "${RELAY_PID}" 2>/dev/null; then
      echo "Mira Relay exited before ${LOCAL_ADVERTISE_URL} became reachable." >&2
      wait "${RELAY_PID}" 2>/dev/null || true
      exit 1
    fi
    if http_get_succeeds "${LOCAL_ADVERTISE_URL}"; then
      echo "Local browser URL is ready: ${LOCAL_ADVERTISE_URL}"
      return 0
    fi
    sleep 0.5
  done

  echo "Timed out waiting for local Mira Relay at ${LOCAL_ADVERTISE_URL}." >&2
  exit 1
}

start_relay_server() {
  ADVERTISE_URL_FILE="$(mktemp -t mira-advertise-url.XXXXXX)"
  update_advertise_url "${LOCAL_ADVERTISE_URL}"

  echo "Starting Mira Relay on ${LOCAL_ADVERTISE_URL} ..."
  "${PYTHON_BIN}" -m mira.relay.server \
    --host "${HOST}" \
    --port "${PORT}" \
    --advertise-url "${LOCAL_ADVERTISE_URL}" \
    --advertise-url-file "${ADVERTISE_URL_FILE}" &
  RELAY_PID="$!"

  wait_for_local_relay
}

wait_for_tunnel_dns() {
  local public_url host max_checks

  public_url="$1"
  host="$(url_host "${public_url}")"
  max_checks=$((TUNNEL_DNS_TIMEOUT_SECONDS * 2))

  if (( max_checks <= 0 )); then
    return 0
  fi

  echo "Waiting for Cloudflare DNS to resolve ${host} ..."
  for _ in $(seq 1 "${max_checks}"); do
    if hostname_resolves "${host}"; then
      echo "Cloudflare DNS is ready for ${host}."
      return 0
    fi
    if [[ -n "${CLOUDFLARED_PID}" ]] && ! kill -0 "${CLOUDFLARED_PID}" 2>/dev/null; then
      cat "${LOG_FILE}" >&2 || true
      echo "cloudflared exited before the tunnel hostname resolved." >&2
      return 1
    fi
    sleep 0.5
  done

  echo "Cloudflare DNS did not resolve ${host} within ${TUNNEL_DNS_TIMEOUT_SECONDS}s." >&2
  return 1
}

wait_for_tunnel_http() {
  local public_url max_checks

  public_url="$1"
  max_checks=$((TUNNEL_HTTP_TIMEOUT_SECONDS * 2))

  if (( max_checks <= 0 )); then
    return 0
  fi

  echo "Waiting for Cloudflare tunnel HTTP to reach Mira Relay ..."
  for _ in $(seq 1 "${max_checks}"); do
    if http_get_succeeds "${public_url}"; then
      echo "Cloudflare tunnel HTTP is ready."
      return 0
    fi
    if [[ -n "${CLOUDFLARED_PID}" ]] && ! kill -0 "${CLOUDFLARED_PID}" 2>/dev/null; then
      cat "${LOG_FILE}" >&2 || true
      echo "cloudflared exited before the tunnel became reachable." >&2
      return 1
    fi
    sleep 0.5
  done

  echo "Cloudflare tunnel did not return HTTP within ${TUNNEL_HTTP_TIMEOUT_SECONDS}s." >&2
  return 1
}

wait_for_public_url() {
  local public_url max_checks

  public_url="$1"
  max_checks=$((TUNNEL_HTTP_TIMEOUT_SECONDS * 2))

  if (( max_checks <= 0 )); then
    return 0
  fi

  echo "Waiting for public tunnel HTTP to reach Mira Relay at ${public_url} ..."
  for _ in $(seq 1 "${max_checks}"); do
    if http_get_succeeds "${public_url}"; then
      echo "Public tunnel HTTP is ready."
      return 0
    fi
    sleep 0.5
  done

  echo "Public tunnel did not return HTTP within ${TUNNEL_HTTP_TIMEOUT_SECONDS}s: ${public_url}" >&2
  return 1
}

wait_for_tunnel_url() {
  local max_checks

  max_checks=$((TUNNEL_URL_TIMEOUT_SECONDS * 2))
  PUBLIC_URL=""

  if (( max_checks <= 0 )); then
    max_checks=1
  fi

  for _ in $(seq 1 "${max_checks}"); do
    if ! kill -0 "${CLOUDFLARED_PID}" 2>/dev/null; then
      cat "${LOG_FILE}" >&2 || true
      echo "cloudflared exited before publishing a URL." >&2
      return 1
    fi
    PUBLIC_URL="$(grep -Eo 'https://[-a-z0-9]+\.trycloudflare\.com' "${LOG_FILE}" | head -n 1 || true)"
    if [[ -n "${PUBLIC_URL}" ]]; then
      return 0
    fi
    sleep 0.5
  done

  cat "${LOG_FILE}" >&2 || true
  echo "Timed out waiting for Cloudflare quick tunnel URL." >&2
  return 1
}

start_cloudflare_tunnel() {
  local attempt
  local attempt_label

  if ! command -v "${CLOUDFLARED_BIN}" >/dev/null 2>&1; then
    echo "cloudflared not found. Continuing with local browser access only." >&2
    echo "macOS: brew install cloudflare/cloudflare/cloudflared" >&2
    return 1
  fi

  attempt=1
  while true; do
    if (( TUNNEL_ATTEMPTS > 0 && attempt > TUNNEL_ATTEMPTS )); then
      break
    fi
    if (( TUNNEL_ATTEMPTS > 0 )); then
      attempt_label="${attempt}/${TUNNEL_ATTEMPTS}"
    else
      attempt_label="${attempt}/until-reachable"
    fi

    LOG_FILE="$(mktemp -t mira-cloudflared.XXXXXX.log)"
    echo "Starting Cloudflare quick tunnel for http://127.0.0.1:${PORT} ... attempt ${attempt_label}"
    "${CLOUDFLARED_BIN}" tunnel \
      --edge-ip-version 4 \
      --protocol http2 \
      --url "http://127.0.0.1:${PORT}" \
      >"${LOG_FILE}" 2>&1 &
    CLOUDFLARED_PID="$!"

    if wait_for_tunnel_url; then
      if [[ "${TUNNEL_STRICT_CHECK}" != "1" ]]; then
        echo "Cloudflare quick tunnel URL is published: ${PUBLIC_URL}"
        echo "Skipping public DNS and direct HTTP checks because MIRA_TUNNEL_STRICT_CHECK=0."
        return 0
      fi

      if wait_for_tunnel_dns "${PUBLIC_URL}" && wait_for_tunnel_http "${PUBLIC_URL}"; then
        echo "Cloudflare quick tunnel is reachable: ${PUBLIC_URL}"
        return 0
      fi
    fi

    stop_cloudflared
    if (( TUNNEL_ATTEMPTS == 0 || attempt < TUNNEL_ATTEMPTS )); then
      echo "Retrying Cloudflare quick tunnel with a new hostname ..." >&2
    fi
    attempt=$((attempt + 1))
  done

  echo "Failed to create a reachable Cloudflare quick tunnel after ${TUNNEL_ATTEMPTS} attempt(s)." >&2
  return 1
}

watch_children() {
  while true; do
    if [[ -n "${RELAY_PID}" ]] && ! kill -0 "${RELAY_PID}" 2>/dev/null; then
      echo "Mira Relay stopped." >&2
      wait "${RELAY_PID}" 2>/dev/null || true
      exit 1
    fi
    if [[ -n "${CLOUDFLARED_PID}" ]] && ! kill -0 "${CLOUDFLARED_PID}" 2>/dev/null; then
      echo "cloudflared stopped." >&2
      wait "${CLOUDFLARED_PID}" 2>/dev/null || true
      exit 1
    fi
    sleep 1
  done
}

if ! command -v "${PYTHON_BIN}" >/dev/null 2>&1; then
  echo "Python not found: ${PYTHON_BIN}" >&2
  exit 1
fi

cd "${ROOT_DIR}"
ensure_port_available

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

start_relay_server

if [[ -n "${MIRA_PUBLIC_URL:-}" ]]; then
  PUBLIC_URL="${MIRA_PUBLIC_URL%/}"
  if wait_for_public_url "${PUBLIC_URL}"; then
    update_advertise_url "${PUBLIC_URL}"
    LAN_RELAY_URL="$(lan_relay_url)"

    cat <<MSG

Mira Relay is ready.
Local Browser URL: ${LOCAL_ADVERTISE_URL}
LAN Android Relay URL: ${LAN_RELAY_URL:-unavailable}
Browser URL: ${PUBLIC_URL}
Android Relay URL: ${PUBLIC_URL}

Using external public tunnel from MIRA_PUBLIC_URL.
Keep your tunnel provider client, such as cpolar or frp, running.
Press Ctrl-C to stop the local relay.

MSG
  else
    LAN_RELAY_URL="$(lan_relay_url)"
    cat <<MSG

Mira Relay is ready for local browser access.
Local Browser URL: ${LOCAL_ADVERTISE_URL}
LAN Android Relay URL: ${LAN_RELAY_URL:-unavailable}

MIRA_PUBLIC_URL is set but unreachable: ${PUBLIC_URL}
Start or fix the external tunnel, then rerun this command.
Press Ctrl-C to stop the local relay.

MSG
  fi
elif start_cloudflare_tunnel; then
  update_advertise_url "${PUBLIC_URL}"
  LAN_RELAY_URL="$(lan_relay_url)"

  cat <<MSG

Mira Relay is ready.
Local Browser URL: ${LOCAL_ADVERTISE_URL}
LAN Android Relay URL: ${LAN_RELAY_URL:-unavailable}
Browser URL: ${PUBLIC_URL}
Android Relay URL: ${PUBLIC_URL}

Open either browser URL on your computer.
On the phone, prefer LAN Android Relay URL when the phone is on the same Wi-Fi.
If LAN is unavailable, paste Android Relay URL, then tap Connect Relay.
Press Ctrl-C to stop both relay and tunnel.

MSG
else
  LAN_RELAY_URL="$(lan_relay_url)"
  cat <<MSG

Mira Relay is ready for local browser access.
Local Browser URL: ${LOCAL_ADVERTISE_URL}
LAN Android Relay URL: ${LAN_RELAY_URL:-unavailable}

Cloudflare quick tunnel is unavailable, so no Android Relay URL was created.
On the phone, use LAN Android Relay URL if the phone is on the same Wi-Fi.
Press Ctrl-C to stop the local relay.

MSG
fi

watch_children
