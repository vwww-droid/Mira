<p align="right">
  English | <a href="./README.zh-CN.md">简体中文</a>
</p>

<div align="center">
  <img src="./docs/mira-readme-icon-round.png" alt="Mira icon" width="138" />

# Mira

Mobile runtime detection workbench for iOS and Android.

<p>
  <img src="https://img.shields.io/badge/analysis-AI--native-0f172a?style=flat-square" alt="AI-native analysis" />
  <img src="https://img.shields.io/badge/platform-Android%20%2B%20iOS-2f855a?style=flat-square" alt="Android and iOS" />
  <img src="https://img.shields.io/badge/execution-live%20logic-2563eb?style=flat-square" alt="Live logic execution" />
  <img src="https://img.shields.io/badge/workflow-Relay%20%2B%20MCP-7c3aed?style=flat-square" alt="Relay and MCP" />
</p>
</div>

## Why follow Mira

Mira turns real runtime cases into reusable workflows, tools, and detection notes.

## Research Updates

- [260520] Article: [Detecting root, emulators, and scrcpy-like projection through the audit logcat side channel](./knowledge/articles/en/audit-logcat-sidechannel-detect-root-emulator-projection.md)
- [260520] Case: [Android high-PID shell proc audit side-channel hints at scrcpy projection](./knowledge/cases/en/2026/2026-05-20-android-high-pid-shell-audit-sidechannel-scrcpy.md)
- [260520] Case: [Android emulator proc audit side-channel exposes qemu SELinux context](./knowledge/cases/en/2026/2026-05-20-android-emulator-proc-audit-sidechannel.md)
- [260519] Case: [Android proc audit side-channel detects Magisk SELinux context](./knowledge/cases/en/2026/2026-05-19-android-proc-audit-magisk-sidechannel.md)

## Features

- **App sandbox tools**: Use shell, PTY, screen capture, and file tools inside the Mira host app sandbox on Android and iOS.
- **Frida tasks**: Run Java and native hooks with RPC for one-time runtime analysis.
- **Relay and MCP**: Connect devices through Relay and let AI work in the same sessions through MCP.

## Getting Started

- **Relay**: `PYTHONPATH=. python3 -m mira.relay.server --host 0.0.0.0 --port 8765 --advertise-url http://<your-lan-ip>:8765`
- **Browser**: Open `http://127.0.0.1:8765` on your desktop.
- **Android**: [Download the latest APK](https://github.com/vwww-droid/Mira/releases/latest/download/mira-app-debug.apk), install it, then enter `http://<your-lan-ip>:8765` in the app.
- **iOS**: Verified on a real device running iOS 16.7.10. See [`docs/GETTING-STARTED.md`](./docs/GETTING-STARTED.md).
- **AI**: `PYTHONPATH=. python3 -m mira.mcp.server --relay http://127.0.0.1:8765`. MCP config: [`docs/MCP.md`](./docs/MCP.md).

The APK's offline Python/Frida runtime requires arm64-v8a and supports Android 10 through 15. Android 16 Java hooks are not supported, and no confirmed official Frida fix is available. See the [compatibility notes](./docs/notes/android-frida-gadget-compatibility-2026-09-05.md).

## Architecture

- [Android application architecture and Java/C boundaries](./docs/ANDROID-ARCHITECTURE.md)
- [Shared native PTY architecture](./docs/NATIVE-ARCHITECTURE.md)
- [Repository layout](./docs/REPO-ARCHITECTURE.md)

## Contributing

Mira welcomes issues and pull requests from mobile security researchers, reverse engineers, Frida users, MCP users, and device testers.

- Read [`CONTRIBUTING.md`](./CONTRIBUTING.md) before opening a focused pull request.
- Use the issue templates for bugs, security hardening, detection ideas, and device compatibility reports.
- For security reports, read [`SECURITY.md`](./SECURITY.md) first.
- Scanner-generated hardening PRs are welcome when they include repository-specific reachability reasoning and verification.

Good starting points include native memory-safety review, Android and iOS device testing, Frida workflow examples, MCP client setup notes, and new reusable detection cases.

## Live Discovery Examples

<table>
  <tr>
    <th align="center">Android Remote Frida</th>
    <th align="center">iOS Remote Frida</th>
  </tr>
  <tr>
    <td>
      <img src="./docs/android-remote-frida.png" alt="Android Remote Frida" />
      <div align="center"><sub>Remote shell, runtime inspection, and live Frida execution on Android.</sub></div>
    </td>
    <td>
      <img src="./docs/ios-remote-frida.png" alt="iOS Remote Frida" />
      <div align="center"><sub>Equivalent PTY and Frida workflow adapted to the iOS iSH compatibility layer.</sub></div>
    </td>
  </tr>
  <tr>
    <th align="center">Android LSPosed Trace</th>
    <th align="center">iOS Jailbreak Trace</th>
  </tr>
  <tr>
    <td>
      <img src="https://github.com/vwww-droid/Mira/releases/download/v1.1.2/Area.preview.gif" alt="Android LSPosed Trace" />
      <div align="center"><sub><a href="https://github.com/vwww-droid/Mira/releases/download/v1.1.2/Area.gif">Download original GIF</a></sub></div>
      <div align="center"><sub>Construct a Frida path around the app classloader and surface LSPosed traces from runtime state.</sub></div>
    </td>
    <td>
      <img src="https://github.com/vwww-droid/Mira/releases/download/v1.1.2/cydia-ios.preview.gif" alt="iOS Jailbreak Trace" />
      <div align="center"><sub><a href="https://github.com/vwww-droid/Mira/releases/download/v1.1.2/cydia-ios.gif">Download original GIF</a></sub></div>
      <div align="center"><sub>Ask Claude to roam the live terminal and surface jailbreak-related traces in the device environment.</sub></div>
    </td>
  </tr>
</table>

## Public Relay Access

![Relay exposed through cpolar](./docs/public-deploy.png)

With Relay, you can temporarily expose an authorized session beyond the local network for cloud devices, expert review handoff, and fast evidence sharing.

## Research Boundaries

1. Mira observes and interacts with the Mira host app sandbox.
2. Mira does not control unrelated third-party apps.
3. Mira does not provide system-wide remote control.
4. Mira does not provide root or jailbreak bypass capabilities.
5. Mira is not a production SDK or a silent background control channel.

## Acknowledgements

- [lamda](https://github.com/firerpa/lamda): inspiration for the web workbench interaction model.
- [Termux](https://github.com/termux/termux-app): Android terminal UX and extensible shell ecosystem.
- [iSH](https://github.com/ish-app/ish): iOS-side Linux shell compatibility and syscall translation path.

## License

`GPL-3.0-only`.
