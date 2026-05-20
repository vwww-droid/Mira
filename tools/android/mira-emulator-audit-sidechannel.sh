# Mira Android emulator audit side-channel probe.
# Upstream: https://github.com/vwww-droid/Mira/blob/main/tools/android/mira-emulator-audit-sidechannel.sh
#
# Usage inside a Mira PTY:
#   1. Paste this file content into mira_run_command.
#   2. Or write it to the device and source it in the current shell:
#      . /data/data/com.vwww.mira/cache/mira-emulator-audit-sidechannel.sh
#
# Do not run this probe with "sh mira-emulator-audit-sidechannel.sh".
# The signal depends on the current Mira PTY shell process touching /proc/<pid>.
# Keep the trigger equivalent to the proven minimal probe:
#   [ -d /proc/<pid> ]
# Do not replace it with cat/readlink/timeout-heavy probing unless a new case
# proves the behavior is equivalent.
#
# Tunables:
#   START=1000 END=2500 CHUNK=10 STEP=10 WAIT_SEC=1 LOG_TAIL=400
#   For a single known qemu-props PID, use START=<pid> END=<pid> CHUNK=1 STEP=1.
#   MATCH='tcontext=u:r:qemu_props:s0|tcontext=u:r:[^ ]*(goldfish|ranchu|qemu)[^ ]*:s0'
#   HIT_FILE=/data/data/com.vwww.mira/cache/emulator_audit_sidechannel_hit.txt

mira_emulator_audit_sidechannel_probe() {
  START=${START:-1000}
  END=${END:-2500}
  CHUNK=${CHUNK:-10}
  STEP=${STEP:-$CHUNK}
  WAIT_SEC=${WAIT_SEC:-1}
  LOG_TAIL=${LOG_TAIL:-400}
  MATCH=${MATCH:-'tcontext=u:r:qemu_props:s0|tcontext=u:r:[^ ]*(goldfish|ranchu|qemu)[^ ]*:s0'}
  HIT_FILE=${HIT_FILE:-/data/data/com.vwww.mira/cache/emulator_audit_sidechannel_hit.txt}

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
  done

  echo "[probe] no hit"
  return 1
}

mira_emulator_audit_sidechannel_probe
