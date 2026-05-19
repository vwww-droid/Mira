# Android proc audit side-channel detects Magisk SELinux context

## 1. detection object

Android app sandbox shell `/proc/<pid>` metadata access as an audit log side-channel for observing target process SELinux `tcontext`.

## 2. initial suspicion

The detection idea was to avoid direct Magisk process inspection from a third-party app and instead touch `/proc/<pid>` to trigger SELinux audit records, then search logcat for `tcontext=u:r:magisk:s0`.

## 3. topic candidate

`android-proc-audit-sidechannel-root-detection`

This candidate topic focuses on `/proc` access audit side-channels from an Android third-party app context. It is distinct from conventional root path checks or direct process-list checks.

## 4. confirmed topic

Not confirmed. This is captured as a single case. Create `knowledge/topics/android-proc-audit-sidechannel-root-detection/` only after more related cases accumulate.

## 5. smells

1. A single `[ -d /proc/1030 ]` probe can trigger `tcontext=u:r:magisk:s0`, but wide linear scans can miss it.
2. `sh script.sh` and the current Mira PTY shell are not equivalent for this signal.
3. Forking external `stat` or `getxattr` per PID creates unrelated audit noise.
4. Increasing `sleep` alone does not fix missed PIDs caused by audit rate limiting.

## 6. key clues

1. `[ -d /proc/1030 ]` produced an `{ getattr }` denial with `tcontext=u:r:magisk:s0`.
2. `1000-1049 wait=5` only emitted the first few `kernel` targets and did not emit `1030`, proving this was not just log latency.
3. `1030-1079 wait=5` reliably emitted Magisk contexts for `/proc/1030`, `/proc/1031`, `/proc/1032`, and `/proc/1051`.
4. `CHUNK=50` over `1000-1100` can hit `/proc/1051` in the `1050-1099` window.

## 7. validation actions

1. Opened an Android PTY through Mira MCP.
2. Ran `[ -d /proc/1030 ]` in the current shell and searched `/system/bin/logcat -d -b all` for `tcontext=u:r:magisk:s0`.
3. Compared `sh script.sh`, `source script`, inline loops, external `/system/bin/stat`, and shell builtins.
4. Compared `CHUNK=10`, `CHUNK=50`, `CHUNK=500`, and different wait durations.
5. Added the reusable script at `tools/android/mira-proc-audit-sidechannel.sh`.

## 8. script artifact and execution model

Script artifact:

```text
tools/android/mira-proc-audit-sidechannel.sh
```

Recommended invocation:

1. Paste the script content into `mira_run_command` so it runs in the current Mira PTY.
2. Or write it to the device cache path and source it with `. /data/data/com.vwww.mira/cache/mira-proc-audit-sidechannel.sh`.
3. Do not use `sh mira-proc-audit-sidechannel.sh`, because a child shell can change the signal behavior.

Known-good parameters:

```sh
START=1
END=10000
CHUNK=50
STEP=50
WAIT_SEC=1
COOLDOWN_SEC=2
LOG_TAIL=1000
MATCH='tcontext=u:r:magisk:s0|tcontext=u:r:su:s0|tcontext=u:r:magiskd:s0'
```

If the scan is unstable:

1. Lower `CHUNK=50` to `CHUNK=10`.
2. Use overlapping windows, for example `CHUNK=50 STEP=25`.
3. Keep `logcat -c -b all` before every window.
4. Do not only increase `sleep`, because the core failure mode is usually audit rate limiting.

## 9. result

The side-channel was confirmed on the target device. A representative hit:

```text
avc: denied { getattr } for comm="sh" path="/proc/1030" dev="proc" ... tcontext=u:r:magisk:s0 ... app=com.vwww.mira
```

Recommended parameters from this case are `CHUNK=50`, `WAIT_SEC=1-3`, `COOLDOWN_SEC=2-3`, and `LOG_TAIL=1000-2000`. If a full scan misses, reduce chunk size or use overlapping steps instead of only increasing wait time.

## 10. false-positive risk

1. Old logcat buffers can contain stale hits, so each decision window should clear logcat first.
2. `tcontext=u:r:magisk:s0` is a strong signal, but the supported conclusion is exposure of a Magisk-related SELinux context, not a complete root capability assessment.
3. Android version, ROM, SELinux policy, Magisk configuration, and logcat visibility can affect observability.
4. A wide-window miss is not evidence that Magisk is absent. It can be caused by audit rate limiting or window noise.

## 11. distilled judgment seeds

1. If a single audit side-channel probe hits but batch probing misses, suspect rate limiting and window noise first.
2. For Mira PTY scripts, current-shell execution and `sh file` are not equivalent until proven otherwise.
3. For `/proc` scans, keep the trigger window below the audit noise threshold and avoid placing the target PID too late in the window.
4. `logcat -c` isolates current-window evidence, but it does not replace chunk control.

## 12. suggested next checks

1. Validate `CHUNK=50` versus `CHUNK=10` across more Android versions and ROMs.
2. Validate overlapping windows, such as `CHUNK=50 STEP=25`, to reduce misses when the target lands late in a window.
3. Check non-Magisk root tools for different `tcontext` values, such as `u:r:su:s0` or vendor-specific domains.
4. Compare audit visibility under enforcing and permissive modes.
5. Decide whether enough cases exist to promote this into a topic and article draft.

## 13. linked articles

None yet. This remains a case record. A future article should derive boundaries, false-positive risks, and stable parameters from this case plus additional device samples.
