# Android Architecture

The Android app keeps framework lifecycle code in three root entry classes and groups reusable work into seven responsibility packages. This boundary reduces coupling to the Android service.

## Entry points and packages

The root package contains only [`MainActivity`](../android/app/src/main/java/com/vwww/mira/MainActivity.java), [`MiraApplication`](../android/app/src/main/java/com/vwww/mira/MiraApplication.java), and [`MiraRuntimeService`](../android/app/src/main/java/com/vwww/mira/MiraRuntimeService.java). They own Android entry-point lifecycle and assemble the components below.

| Package | Responsibility | Mira package dependencies |
| --- | --- | --- |
| `command` | Local command socket, remote command validation, process execution, and wire results | None |
| `device` | Persistent device identity and runtime metrics | None |
| `discovery` | LAN discovery and bounded HTTP wake requests | None; root behavior enters through callbacks |
| `relay` | Control, terminal relay, WebSocket transport, and relay endpoint construction | `device`, `runtime`, `terminal` |
| `runtime` | APK runtime installation, managed scripts, and verified extraction | Generated `BuildConfig` only |
| `screen` | Host-app capture, AVC encoding, screen packets, input routing, outlines, and motion logging | `device`, `relay`, generated `BuildConfig` |
| `terminal` | PTY launch environment, native PTY session, toolbox, and local web terminal | `command`, `runtime` |

Package-private parsers, protocol values, codec helpers, launch specifications, and native process implementations stay behind their package entry points. Feature packages do not import `MiraRuntimeService`, `MiraApplication`, or `MainActivity`. `MiraApplication` injects `MiraRuntimeService::requestOutlineUpload` into [`AppScreenCapture`](../android/app/src/main/java/com/vwww/mira/screen/AppScreenCapture.java) and [`ViewOutlineCollector`](../android/app/src/main/java/com/vwww/mira/screen/ViewOutlineCollector.java); their default and null-reset callback is a no-op. `MiraRuntimeService` similarly injects callbacks for discovery, commands, and remote screen-input results. The screen-input result callback resolves the current control client when it sends, so it retains queued control-channel delivery.

## Java and C boundary

The PTY is native because process groups, `fork`/`exec`, file descriptors, terminal resize, signals, and `waitpid` are shared POSIX concerns. [`mira/pty.h`](../native/include/mira/pty.h) defines the stable byte-stream and process-lifecycle API; [`pty_core.c`](../native/src/pty/pty_core.c) and [`pty_spawn_posix.c`](../native/src/posix/pty_spawn_posix.c) hold the shared implementation. Android's [`mira_pty_jni.c`](../native/bridge/android/jni/mira_pty_jni.c) registers methods on `com/vwww/mira/terminal/NativePtyProcess`, while [`mira_pty_ios_shim.h`](../native/bridge/ios/mira_pty_ios_shim.h) is the thin Swift-facing bridge. WebSocket, JSON, and app lifecycle assumptions do not enter the PTY core.

Android framework work remains Java: `Application`/`Service`/`Activity` lifecycle, `View` capture and input, `MediaCodec`, `Settings`, identity storage, Relay coordination, and runtime asset installation. Detection policy also remains outside the app base; concrete root, emulator, projection, and side-channel checks belong in scripts and reproducible cases.

### Screen codec decision

Android keeps [`AvcBitstream`](../android/app/src/main/java/com/vwww/mira/screen/AvcBitstream.java) and [`ScreenVideoPacket`](../android/app/src/main/java/com/vwww/mira/screen/ScreenVideoPacket.java) in Java. The iOS comparison is [`annexBData`](../ios/Mira/Mira/NativeBridge/MiraRemoteServices.swift), `h264NalLengthSize`, and `videoPacket` in the Swift screen service.

Both platforms emit Annex B NAL units and the 20-byte `MHS1` header, but their inputs and policy are not identical:

- Android accepts an existing Annex B stream, four-byte or two-byte length prefixes, and a raw-NAL fallback. It adds cached codec configuration to a key frame only when the payload has no SPS. Its packet timestamp is synthesized as `sequence * framePeriodMs * 1000`, using the profile's integer millisecond frame period.
- iOS obtains the NAL length size from the `CMFormatDescription`, reads AVCC sample data, and prepends format-description parameter sets on key frames. Its packet timestamp is also synthesized from sequence, using `sequence * (1_000_000 / frameRate)` with integer division.
- Android uses a `long` sequence and saturates only its upper wire value at `Integer.MAX_VALUE`; iOS starts with `UInt32`. Both write the header integers in big-endian order.

Moving these small helpers to C now would add JNI and Swift/Data buffer-ownership contracts, may require additional copies depending on the bridge design, and would require a cross-platform malformed-input contract without removing the platform-specific encoder work. Reconsider a shared codec module if profiling finds a copy or CPU problem, the shared parsing rules grow, both platforms repeatedly need the same defect fix, or one protocol corpus would materially reduce duplicated tests. Before extracting it, both callers should agree on the input format, NAL-length size, parameter-set policy, sequence range, presentation timestamp, and bridge ownership, then exercise one golden corpus through both language bridges. That corpus should include malformed lengths, start-code variants, SPS/PPS duplication, integer boundaries, and exact `MHS1` bytes.

## Regression commands

Run host tests for package boundaries and pure protocol behavior:

```bash
python3 -m unittest discover -s tests -p 'test_*.py' -v
python3 -m unittest tests.test_android_discovery tests.test_android_commands -v
python3 -m unittest tests.test_runtime_extraction tests.test_terminal_launch_spec -v
python3 -m unittest tests.test_relay_endpoint tests.test_remote_input_handler -v
python3 -m unittest tests.test_screen_encoder_selection tests.test_screen_protocol -v
```

Build and exercise the real Android integration with overwrite installation:

```bash
MIRA_ANDROID_RELAY_URL="http://<host-ip>:8765" ./mira-android
```

The host suites cover bounded discovery, command routing, runtime extraction recovery, terminal environment construction, relay URL compatibility, seven remote-input kinds and result schema, encoder candidate selection, AVC conversion, and exact `MHS1` packet bytes. The device run covers Android framework integration, JNI loading, native PTY I/O/resize, wrapper commands, control and screen channels, and outline/input behavior. Record the device, OS, ABI, commands, and observed result when using that run as release evidence.

For the refactor series ending at `2d2c67a` (`53dd4f8`, `ff5e19a`, `b704ae7`, `cfcd802`, `ab0e9ae`, `2d2c67a`), the full host suite completed 39 tests with no skips in 31.539 seconds. A Pixel 4 on API 33 then exercised local terminal token rejection/acceptance, WebSocket PTY I/O and `31x93` resize, control-relay PTY, wrapper commands, remote logcat, all seven screen-input acknowledgements plus visible UI/outline changes, and 20 `MHS1` H.264 frames. Runtime reinstall recovery completed in about 5.5 seconds, the managed state content and seven script hashes remained stable, and the identity preferences hash did not change. Final LAN discovery, HTTP wake, and PTY were also exercised; after stop, TCP refused connections and UDP was silent. The restored three Relay channels passed, and the crash buffer remained empty.

That evidence is sufficient for acceptance of the current refactor on the recorded configuration. It does not cover TLS transport, a broad Android API/device matrix, indefinite runtime behavior, or a measured performance comparison between Java/Swift and a hypothetical C codec implementation.
