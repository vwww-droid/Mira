# Mira Android shell-domain PID cluster audit side-channel probe.
#
# Purpose:
#   Detect high-PID u:r:shell:s0 clusters from an App sandbox by touching
#   /proc/<pid> and reading fresh SELinux audit lines from logcat.
#
# This does NOT recover cmdline or parent-child relations by itself.  It only
# reports App-observable shell-domain PID clusters.  Use controlled start/stop
# baselines to correlate the cluster with adb automation or projection tools.
#
# Recommended invocation inside a Mira PTY:
#   . /data/data/com.vwww.mira/cache/mira-shell-domain-cluster-sidechannel.sh
#
# Tunables:
#   START=10000 END=32000 CHUNK=25 STEP=25 WAIT_SEC=1 LOG_TAIL=1200
#   GAP=16 MIN_CLUSTER=2

mira_shell_domain_cluster_sidechannel_probe() {
  START=${START:-10000}
  END=${END:-32000}
  CHUNK=${CHUNK:-25}
  STEP=${STEP:-$CHUNK}
  WAIT_SEC=${WAIT_SEC:-1}
  LOG_TAIL=${LOG_TAIL:-1200}
  GAP=${GAP:-16}
  MIN_CLUSTER=${MIN_CLUSTER:-2}
  TMP=${TMP:-/data/data/com.vwww.mira/cache/shell_domain_cluster_pids.txt}

  mira_extract_shell_domain_pids() {
    /system/bin/timeout 3 /system/bin/logcat -d -b all -t "$LOG_TAIL" 2>/dev/null \
      | grep 'tcontext=u:r:shell:s0' \
      | sed -n 's/.*path="\/proc\/\([0-9][0-9]*\)".*/\1/p; s/.*name="\([0-9][0-9]*\)".*/\1/p' \
      | awk -v s="$1" -v e="$2" '$1>=s && $1<=e' \
      | sort -n -u
  }

  mira_report_shell_domain_clusters() {
    awk -v gap="$GAP" -v min="$MIN_CLUSTER" '
      function flush() {
        if (n >= min) {
          printf("[cluster] count=%d span=%d-%d pids=", n, first, last);
          for (i = 1; i <= n; i++) printf("%s%s", a[i], i == n ? "\n" : ",");
        }
      }
      NF {
        p = $1 + 0;
        if (n == 0) { first = p; last = p; n = 1; a[1] = p; next; }
        if (p - last <= gap) { n++; a[n] = p; last = p; next; }
        flush(); delete a; first = p; last = p; n = 1; a[1] = p;
      }
      END { flush(); }
    '
  }

  s=$START
  rm -f "$TMP" >/dev/null 2>&1

  while [ "$s" -le "$END" ]; do
    e=$((s + CHUNK - 1))
    [ "$e" -gt "$END" ] && e=$END
    echo "[probe] window=$s-$e"

    /system/bin/logcat -c -b all >/dev/null 2>&1

    p=$s
    while [ "$p" -le "$e" ]; do
      [ -d "/proc/$p" ] >/dev/null 2>&1
      p=$((p + 1))
    done

    sleep "$WAIT_SEC"

    mira_extract_shell_domain_pids "$s" "$e" > "$TMP"
    if [ -s "$TMP" ]; then
      echo "[hit] shell_domain_pids=$(tr '\n' ',' < "$TMP" | sed 's/,$//')"
      mira_report_shell_domain_clusters < "$TMP"
    fi

    s=$((s + STEP))
  done
}

mira_shell_domain_cluster_sidechannel_probe
