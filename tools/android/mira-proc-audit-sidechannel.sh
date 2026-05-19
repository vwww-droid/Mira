# Mira Android proc audit side-channel probe.
#
# Usage inside a Mira PTY:
#   1. Paste this file content into mira_run_command.
#   2. Or write it to the device and source it in the current shell:
#      . /data/data/com.vwww.mira/cache/mira-proc-audit-sidechannel.sh
#
# Do not run this probe with "sh mira-proc-audit-sidechannel.sh".
# The signal depends on the current Mira PTY shell process touching /proc/<pid>.
#
# Tunables:
#   START=1 END=10000 CHUNK=50 STEP=50 WAIT_SEC=1 COOLDOWN_SEC=2 LOG_TAIL=1000
#   MATCH='tcontext=u:r:magisk:s0|tcontext=u:r:su:s0|tcontext=u:r:magiskd:s0'
#   HIT_FILE=/data/data/com.vwww.mira/cache/audit_sidechannel_hit.txt

mira_proc_audit_sidechannel_probe() {
  START=${START:-1}
  END=${END:-10000}
  CHUNK=${CHUNK:-50}
  STEP=${STEP:-$CHUNK}
  WAIT_SEC=${WAIT_SEC:-1}
  COOLDOWN_SEC=${COOLDOWN_SEC:-2}
  LOG_TAIL=${LOG_TAIL:-1000}
  MATCH=${MATCH:-'tcontext=u:r:magisk:s0|tcontext=u:r:su:s0|tcontext=u:r:magiskd:s0'}
  HIT_FILE=${HIT_FILE:-/data/data/com.vwww.mira/cache/audit_sidechannel_hit.txt}

  s=$START
  rm -f "$HIT_FILE" >/dev/null 2>&1

  while [ "$s" -le "$END" ]; do
    e=$((s + CHUNK - 1))
    [ "$e" -gt "$END" ] && e=$END
    echo "[probe] scan pid=$s-$e"

    /system/bin/logcat -c -b all >/dev/null 2>&1

    p=$s
    while [ "$p" -le "$e" ]; do
      [ -d "/proc/$p" ] >/dev/null 2>&1
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
    sleep "$COOLDOWN_SEC"
  done

  echo "[probe] no hit"
  return 1
}

mira_proc_audit_sidechannel_probe
