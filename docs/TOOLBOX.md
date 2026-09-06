<p align="right">
  English | <a href="./TOOLBOX.zh-CN.md">简体中文</a>
</p>

# Mira Toolbox

## Goal

Mira Toolbox is the first-stage replacement for the Termux package repository path.

This phase does not use `apt`, does not maintain a package index, and does not install tools from the Termux repository. Instead, the APK packages BusyBox directly. Every remote terminal session releases BusyBox into a session directory under the app cache and removes it when the session closes.

## Current packaged contents

Mira currently ships BusyBox binaries for four Android ABIs:

| ABI | Asset path | Size |
| --- | --- | --- |
| arm64-v8a | `android/app/src/main/assets/toolbox/busybox/arm64-v8a/busybox` | 1342824 bytes |
| armeabi-v7a | `android/app/src/main/assets/toolbox/busybox/armeabi-v7a/busybox` | 1001940 bytes |
| x86 | `android/app/src/main/assets/toolbox/busybox/x86/busybox` | 1525984 bytes |
| x86_64 | `android/app/src/main/assets/toolbox/busybox/x86_64/busybox` | 1523248 bytes |

At runtime, the app picks the first available asset according to `Build.SUPPORTED_ABIS`.

## Machine-readable manifest

The top-level manifest lives at:

```text
android/app/src/main/assets/toolbox/manifest.json
```

Each ABI directory also keeps a `SOURCE.txt` file with the version, source, build script, and SHA256.

## Build source

BusyBox currently comes from:

```text
LAMDA v9.25 release busybox assets
```

Source:

```text
https://github.com/firerpa/lamda/releases/tag/v9.25
```

To re-download and verify all ABI assets:

```bash
tools/toolbox/download-lamda-busybox.sh
```

The download script also fetches the matching `.sha256sum` files, validates them, and writes the APK asset directory, `SOURCE.txt`, and `manifest.json`.

The repository still keeps `tools/toolbox/build-busybox-android.sh` as a self-build experiment script, but the APK currently ships the LAMDA release binaries by default. The switch happened because the self-built `wget` could trigger a segmentation fault on Android devices, while the LAMDA build exposes a more complete applet set.

## Session release flow

```text
Open Terminal
  -> relay.TerminalRelayClient
  -> terminal.SessionToolbox.prepare(sessionId)
  -> Select BusyBox asset by device ABI
  -> /data/user/0/com.vwww.mira/cache/mira-sessions/<sessionId>/bin/busybox
  -> Run busybox --list to get the real supported applets
  -> Create symlinks for all actually supported applets
  -> Copy manifest.json to the session root
  -> terminal.PtyFactory prepends the session bin directory to PATH
  -> Create PTY
  -> Remove mira-sessions/<sessionId> after session.close
```

## Runtime environment variables

Remote PTY sessions add:

```text
MIRA_TOOLBOX_BIN=/data/user/0/com.vwww.mira/cache/mira-sessions/<sessionId>/bin
MIRA_BUSYBOX=/data/user/0/com.vwww.mira/cache/mira-sessions/<sessionId>/bin/busybox
MIRA_BUSYBOX_ABI=<selected-abi>
MIRA_BUSYBOX_ASSET=toolbox/busybox/<selected-abi>/busybox
MIRA_TOOLBOX_MANIFEST=/data/user/0/com.vwww.mira/cache/mira-sessions/<sessionId>/toolbox-manifest.json
MIRA_PATH_PREFIX=$MIRA_TOOLBOX_BIN
PATH=$MIRA_TOOLBOX_BIN:$PREFIX/bin:/system/bin:/system/xbin
```

## Validation commands

```sh
echo "$MIRA_BUSYBOX_ABI"
echo "$MIRA_TOOLBOX_MANIFEST"
command -v busybox
busybox echo busybox-ok
command -v ls
ls /proc/self | head -3
```

`busybox` and `ls` are expected to resolve from `cache/mira-sessions/<sessionId>/bin`.

Mira no longer uses a handwritten applet allowlist. Whatever commands the BusyBox binary actually supports are what the current session exposes. If the binary does not support a system command, Mira does not create that entry point and therefore does not shadow the Android system command.

## Current boundaries

1. The toolbox is released only inside remote Relay sessions. Local Terminal is not yet wired in.
2. The session directory is a temporary tool copy and is removed when the session closes.
3. The BusyBox assets inside the APK are not deleted. They are the basis of the direct-packaging path.
4. BusyBox uses GPL-2.0, so source and license notices must stay with redistribution.
5. The LAMDA v9.25 BusyBox assets do not include the `wget` applet, so Mira does not create a `wget` command entry today.
6. The current toolbox does not implement `apt`, package indexes, or dynamic tool delivery.
