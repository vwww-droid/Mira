<p align="right">
  English | <a href="./NATIVE-ARCHITECTURE.zh-CN.md">简体中文</a>
</p>

# Native Architecture

This document describes the Mira native-layer architecture. The current goal is to let Android and iOS share the core PTY process-lifecycle capabilities, especially the low-level fork and exec path, so the project does not drift into two hard-to-maintain implementations. See [Android Architecture](./ANDROID-ARCHITECTURE.md) for the Android application packages and the Java/C placement decision.

## Architecture principles

1. `native/include` only holds stable C APIs.
2. `native/src/pty` contains PTY lifecycle core logic and stays independent from JNI, Swift, or UI code.
3. `native/src/posix` contains shared POSIX behavior such as fork, setsid, dup2, chdir, execvp, read or write, resize, waitpid, and signal handling.
4. `native/src/platform/*` only holds platform differences, such as PTY pair creation and fd cleanup.
5. `native/bridge/*` only holds language bridges. Android uses JNI, while iOS uses a thin C shim for Swift.
6. Platform apps should only hold session abstractions such as `terminal.PtySession` instead of assembling C-layer details directly.

## Current layout

The current directory split separates stable headers, shared PTY lifecycle code, shared POSIX spawn logic, platform-specific PTY handling, and platform bridge code.

## Call flow

Typical flow:

1. UI or service layer requests a PTY session.
2. Platform bridge enters the shared native API.
3. Shared PTY core coordinates spawn, IO, resize, and shutdown.
4. Platform-specific code handles PTY pair creation and any host differences.

## Module boundaries

### `pty_core.c`

Owns the session lifecycle abstraction and the common state transitions.

### `pty_spawn_posix.c`

Owns the POSIX spawn path and shared child-process setup.

### `platform/android/pty_pair_android.c`

Owns Android-specific PTY pair behavior.

### `platform/darwin/pty_pair_darwin.c`

Owns Darwin-side PTY pair behavior for iOS and related Apple targets.

### `bridge/android/jni/mira_pty_jni.c`

Owns the JNI bridge from Android `terminal.NativePtyProcess` into the native PTY layer. Native registration uses `com/vwww/mira/terminal/NativePtyProcess`.

### `bridge/ios/mira_pty_ios_shim.*`

Owns the thin C shim consumed by Swift on iOS.

## CMake targets

The build should keep shared PTY logic reusable while exposing only the bridge surface each platform needs.

## Native placement boundary

Keep POSIX PTY lifecycle and platform PTY-pair differences in C. Keep Android framework lifecycle, views, `MediaCodec`, `Settings`, Relay JSON, and WebSocket coordination in Java, and keep the corresponding Apple framework work in Swift. The Android and iOS screen encoders currently share wire semantics but receive different encoded-buffer forms and use different parameter-set and timestamp policies; moving their small Annex B and `MHS1` helpers to C would introduce buffer-ownership and bridge contracts, with no measured runtime benefit today. The criteria for reconsidering that split are documented in [Android Architecture](./ANDROID-ARCHITECTURE.md#screen-codec-decision).

## Current Android validation result

Android has already validated the shared PTY path in the host app runtime and continues to be the faster iteration baseline.

## iOS integration order

On iOS, prefer stable session reuse and incremental bridge integration rather than repeatedly reopening sessions during runtime analysis.

## Future layout cleanup goals

1. Keep platform differences narrow.
2. Keep shared PTY lifecycle logic centralized.
3. Avoid leaking app-layer assumptions into the native core.
