# Mira risk surfaces

Use this reference after `$mira-risk-collect` triggers.
Pick only checks supported by the current device and connector.

## Android baseline surfaces

1. Runtime identity: SDK, ABI, build fingerprint, manufacturer, model, security patch, boot state.
2. App context: package, UID, process list, debuggable state, SELinux domain, granted permissions.
3. Root or Magisk clues: `su` paths, Magisk files, mount overlays, Zygisk traces, suspicious domains, audit side-channel hits.
4. Emulator clues: hardware properties, sensors, telephony gaps, qemu/ranchu/goldfish strings, virtualization files.
5. Hooking clues: Frida ports or processes, suspicious maps, injected libraries, ptrace state, linker namespace anomalies.
6. Input/UI clues: focused window, accessibility overlays, MotionEvent logs, synthetic tap acceptance, coordinate mismatch.
7. Logs: `logcat` audit/avc, crash, permission denial, input dispatcher, ActivityTaskManager events.
8. File-system clues: unexpected writable executable paths, xattrs, proc visibility, app data contamination.

## iOS baseline surfaces

1. Runtime identity: iOS version, device model, architecture, app bundle, entitlements where available.
2. Jailbreak clues: writable protected paths, package managers, substrate or tweak dylibs, suspicious loaded images.
3. Frida context: server or gadget availability, ObjC class enumeration stability, RPC size behavior.
4. UI clues: screen outline, visible app, input path, coordinate transform mismatch.
5. Session stability: iSH latency, PTY reuse, syscall translation failures, reconnect churn.

## Judgment format

For each signal, write:

1. `observed`: exact command result, tool result, or log pattern.
2. `implies`: what risk it may indicate.
3. `needsVerification`: what would raise or lower confidence.
4. `falsePositiveRisk`: why this could be benign.

## Case-worthy threshold

Create or propose a case when at least one condition holds:

1. A signal is repeatable and differentiates risky from normal environments.
2. A command sequence reveals a non-obvious side channel.
3. A false positive pattern is important enough to prevent future misreads.
4. A Mira primitive behaves differently across Android, iOS, emulator, root, jailbreak, or hooked contexts.
