# Mira Android proc audit side-channel(进程审计侧信道)探测片段.
#
# 在 Mira PTY(伪终端)中的用法:
#   1. 把整个文件内容粘贴给 mira_run_command 执行.
#   2. 或者先推送到设备后用 source(在当前 shell 中加载执行)方式运行:
#      . /data/data/com.vwww.mira/cache/mira-proc-audit-sidechannel.sh
#
# 不要用 "sh mira-proc-audit-sidechannel.sh" 运行这个侧信道探测.
# 这个探测依赖当前 Mira PTY shell(命令解释器)进程触碰 /proc/<pid>.
#
# 可调参数:
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
