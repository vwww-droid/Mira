# Third Party Notices

## BusyBox

- Name: BusyBox
- Version: 1.37.0
- License: GPL-2.0
- Source: https://busybox.net/downloads/busybox-1.37.0.tar.bz2
- Build script: `tools/toolbox/build-busybox-android.sh`
- Packaged asset: `android/app/src/main/assets/toolbox/busybox/arm64-v8a/busybox`

Mira packages BusyBox as an APK asset and releases it into a temporary per-session toolbox directory when a remote terminal session starts.
