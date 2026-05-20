# Mira Android high-PID shell audit side-channel probe.
# Upstream: https://github.com/vwww-droid/Mira/blob/main/tools/android/mira-high-pid-shell-audit-sidechannel.sh
#
# Usage inside a Mira PTY:
#   1. Paste this file content into mira_run_command.
#   2. Or write it to the device and source it in the current shell:
#      . /data/data/com.vwww.mira/cache/mira-high-pid-shell-audit-sidechannel.sh
#
# Do not run this probe with "sh mira-high-pid-shell-audit-sidechannel.sh" until
# proven equivalent. The signal depends on the current Mira PTY shell process
# touching /proc/<pid> and then reading the fresh audit log window.
#
# Tunables:
#   START=10000 END=32000 CHUNK=50 STEP=50 WAIT_SEC=1 LOG_TAIL=1200
#   MIN_PID=10000
#   MATCH='path="/proc/[1-9][0-9][0-9][0-9][0-9]".*tcontext=u:r:shell:s0'
#   HIT_FILE=/data/data/com.vwww.mira/cache/high_pid_shell_audit_hit.txt

mira_high_pid_shell_audit_sidechannel_probe() {
  START=${START:-10000}
  END=${END:-32000}
  CHUNK=${CHUNK:-50}
  STEP=${STEP:-$CHUNK}
  WAIT_SEC=${WAIT_SEC:-1}
  LOG_TAIL=${LOG_TAIL:-1200}
  MIN_PID=${MIN_PID:-10000}
  MATCH=${MATCH:-'path="/proc/[1-9][0-9][0-9][0-9][0-9]".*tcontext=u:r:shell:s0'}
  HIT_FILE=${HIT_FILE:-/data/data/com.vwww.mira/cache/high_pid_shell_audit_hit.txt}

  s=$START
  rm -f "$HIT_FILE" >/dev/null 2>&1

  while [ "$s" -le "$END" ]; do
    e=$((s + CHUNK - 1))
    [ "$e" -gt "$END" ] && e=$END
    echo "[probe] scan pid=$s-$e"

    /system/bin/logcat -c -b all >/dev/null 2>&1

    p=$s
    while [ "$p" -le "$e" ]; do
      [ "$p" -ge "$MIN_PID" ] && [ -d "/proc/$p" ] >/dev/null 2>&1
      p=$((p + 1))
    done

    sleep "$WAIT_SEC"

    /system/bin/logcat -d -b all -t "$LOG_TAIL" 2>/dev/null \
      | grep -E "$MATCH" \
      | tail -30 > "$HIT_FILE"

    if [ -s "$HIT_FILE" ]; then
      echo "[probe] hit pid=$s-$e"
      cat "$HIT_FILE"
      return 0
    fi

    s=$((s + STEP))
  done

  echo "[probe] no hit"
  return 1
}

mira_high_pid_shell_audit_sidechannel_probe
