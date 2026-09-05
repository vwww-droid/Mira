# Android Frida Gadget compatibility, 2026-09-05

## Current result

The unreleased compatibility build uses Frida 16.7.19 and Mira's single-runner MCP path. It passed the tested Java and native workflows on Android 10 through 15. Android 16 Java hooks remain unsupported because the tested Frida Java bridges crash ART when a method implementation is installed.

This is a local compatibility result, not a claim about every device. The matrix used arm64 AVDs with 4 KB pages. Android 10 through 13 received short regressions; Android 14 and 15 received the full repeated-task and survival run. The published GitHub APK does not yet contain these changes.

## Compatibility build

- APK: `build/frida-binary-runner-2026-09-05/final-apk/mira-app-android14-15-frida-16.7.19-single-runner-binary-debug.apk`
- Size: `77,941,648` bytes
- SHA-256: `6e3a13ffec7658de32e3879d26a9b32afd18c14215d2024ce6900f90c55effd2`
- Agent SHA-256: `05e8b3125405e9e2aed485106f780ddf07c2409ad1a56f8bac8e68652b5696a7`
- Debug certificate SHA-256: `ebdeb4e49f3473be712b47bf4fcfc6369672bb3d08dff9136479959f82191c89`
- Build command: `tools/android/build-frida-16.7-compat-apk.sh`

The APK contains matching 16.7.19 Gadgets for arm64-v8a and armeabi-v7a, plus the arm64 Android Python binding. The offline Python runtime requires arm64-v8a. Native libraries are compressed, signatures v1, v2, and v3 verify, and `zipalign -P 16 -c 4` passes. Bootstrap state 17 forces a covering install to refresh the packaged agent without uninstalling or clearing app data.

## MCP task model

`mira_frida_run_script` keeps one device-side Frida session and one runner Script alive. Each request runs in a new local function scope, invokes the export named by `rpcMethod`, and calls the idempotent export named by `cleanupMethod` in `finally`. Mira then releases its references to that task's source and exports. It does not cache each source as a separate Script, unload Gadget, or restart Mira between tasks.

The AI writes both exports. Cleanup must restore Java implementations, detach native listeners, cancel timers, and release task-owned objects. Mira reports cleanup failure, but cannot undo arbitrary global state, native allocations, background callbacks, or external side effects left by a script. Long-running listener management is not implemented.

Frida operations handled by one MCP server are serialized. Concurrent calls are rejected and timed-out requests are not replayed because execution may already have started.

On the Android single-runner path, `ArrayBuffer`, `DataView`, and typed-array values become:

```json
{ "type": "bytes", "encoding": "base64", "dataBase64": "..." }
```

Nested values and typed-array offsets are preserved. Ordinary JSON remains unchanged. Unsupported values, circular references, and non-finite numbers fail with a path-specific error, and cleanup still runs.

## Tested Android versions

| Android | API | Result | Coverage |
| --- | ---: | --- | --- |
| 10 | 29 | Pass | Short JSON, binary, Java reference hook with original call, native hook, error recovery, PID and PTY check |
| 11 | 30 | Pass | Same short regression |
| 12 | 31 | Pass | Same short regression |
| 12L | 32 | Pass | Same short regression |
| 13 | 33 | Pass | Same short regression |
| 14 | 34 | Pass | Same state-17 short regression |
| 15 | 35 | Pass | Same state-17 short regression |
| 16 | 36 | Fail | Native and read-only Java operations pass; Java method replacement crashes ART |

The state-17 passing Java probe replaced `String.startsWith(String)`, received a reference argument, and called the original method. The native probe attached to and called `getpid`.

The 12-task and 60-second API 34/35 lifecycle runs used the earlier state-15 build, before binary normalization was added. Those runs kept one framework Script, the same Mira PID, PTY session, and shell process throughout. Syntax, RPC, and cleanup failures were reported, and later valid requests still passed. The final state-17 binary APK received the shorter per-API regression shown above; the two stages are separate evidence, not one APK completing both suites.

## Android 16 diagnosis

Frida 16.7.19 passed native Interceptor on API 36, then crashed at Java bridge initialization before the callback inside `Java.performNow()` ran.

A separate candidate paired Frida 17.16.4 with `frida-java-bridge` 7.0.13, the latest bridge release at the time of testing. It passed native interception, `Java.performNow()`, and `Java.use()`. Installing the first `String.length()` implementation caused a JIT-thread SIGSEGV followed by CheckJNI errors for an invalid transition-frame `jobject`. Replacing Mira's own static `MiraCommandProtocol.encode(String)` failed with the same class of invalid `jstring`. Calling `Java.deoptimizeEverything()` before replacement did not help. CheckJNI stayed enabled.

This matches the open upstream reports [frida#3700](https://github.com/frida/frida/issues/3700) and [frida#3745](https://github.com/frida/frida/issues/3745). No linked merged fix addresses this method-replacement boundary. The open bridge PR [#394](https://github.com/frida/frida-java-bridge/pull/394) concerns a different Android 16 GC-hook installation failure.

The 17.16.4 candidate is retained only for reproduction:

- APK: `build/frida-android16-diagnosis-2026-09-05/frida17.16.4-single-runner-v3/mira-app-android16-frida-17.16.4-single-runner-debug.apk`
- SHA-256: `d77a0f3f8b799c68eb5b5cdc3d7f082d78ea75b8a25867ecbf6854a8d96df137`
- Evidence: `build/frida-android16-diagnosis-2026-09-05/frida17.16.4-isolation-v3/`, `frida17.16.4-app-method-v3/`, and `frida17.16.4-deopt/`

## Gadget unload experiment

Frida's [Gadget documentation](https://frida.re/docs/gadget/) describes `teardown: "full"` for library unload. A temporary JNI loader therefore owned a real `dlopen` handle and tested `dlclose` followed by `dlopen` on API 34.

The idle cycle removed all four `libdynamic.so` mappings and loaded four new mappings while Mira and its PTY stayed alive. After four real Java, native, and RPC tasks, the same unload path terminated Mira with signal 11 before the post-unload checkpoint. This tested combination cannot safely reset a used Gadget by unloading its shared object. The experimental loader was removed from the production source.

Evidence: `build/frida-so-reload-2026-09-05/api34-lowlevel-reload.json`, `api34-script-reload-exit-info.txt`, and `api34-full-logcat-after-script-reload.txt`.

## Evidence index

- Cross-version binary matrix: `build/frida-binary-runner-2026-09-05/compatibility-matrix-state17.json`
- Per-version results: `build/frida-binary-runner-2026-09-05/api29/` through `api36/`
- API 34 and 35 repeated-task results: `build/frida-single-runner-2026-09-05/api34-state15-product.json` and `api35-state15-product.json`
- Regressions: `tools/android/frida-binary-runner-regression.py` and `tools/android/frida-single-runner-regression.py`
- Earlier 16.0.7 baseline: `build/android-compat-2026-09-05/summary.json`

Every AVD was started read-only without snapshots. Installs used `mira-android` with `adb install -r`; no app data was cleared. The physical device was not used. These tests do not cover 16 KB page images, every vendor ART build, every ABI, external frida-server injection, or indefinite workloads.

Official references: [Gadget](https://frida.re/docs/gadget/), [Frida 16.1.9 Android 14 support](https://frida.re/news/2023/12/20/frida-16-1-9-released/), [Frida 16.4.6 Android 15 ART fix](https://frida.re/news/2024/07/22/frida-16-4-6-released/), and [Frida 17.1.4 Android 16 bridge support](https://frida.re/news/2025/06/10/frida-17-1-4-released/).
