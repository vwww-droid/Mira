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

---

<div align="center">
  <strong>Mira is built in the open as a long-term project for turning real runtime cases into reusable detection knowledge, analysis workflows, and cross-platform tooling.</strong>
</div>

## Why follow Mira

- Mira grows with real runtime work, not just planned features.
- Each field case can become a new workflow, tool capability, or detection note.
- The project is designed to accumulate practical mobile runtime knowledge over time.
- Following Mira means following how that knowledge turns into working tooling.

## Research Updates

Mira is developed in public and updated across multiple channels.

Different platforms carry different parts of the work:

- GitHub for the main project record
- Articles for full case write-ups
- Case record: [Android emulator proc audit side-channel exposes qemu SELinux context](./knowledge/cases/en/2026/2026-05-20-android-emulator-proc-audit-sidechannel.md)
- Community posts for focused technical sharing
- Short updates for demos, progress, and smaller discoveries

> Next updates will resume after the early May 2026 holiday break. Happy International Workers' Day to everyone celebrating.

## Features

- 🧩 **Real app sandbox access**: Drop directly into the true permission sandbox of target apps with one consistent Android and iOS workflow.
- 🤖 **Built for AI operators**: Let AI inspect, navigate, and reason inside the live app runtime like a hands-on analyst.
- ⚡ **Live runtime execution**: Run Java, Native, and Frida-driven logic on demand to verify signals instead of guessing from static traces.
- 🚀 **Fast to first result**: Start Relay, install the app, and get to shell, screen, and runtime evidence in minutes.
- ♾️ **Compounding detection intelligence**: Turn one real finding into reusable detection patterns and repeatable hardening wins.

## Getting Started

- **Relay**: `PYTHONPATH=. python3 -m mira.relay.server --host 0.0.0.0 --port 8765 --advertise-url http://<your-lan-ip>:8765`
- **Browser**: Open `http://127.0.0.1:8765` on your desktop.
- **Android**: Download the APK from [Releases](https://github.com/vwww-droid/Mira/releases), install it, then enter `http://<your-lan-ip>:8765` in the app.
- **iOS**: Verified on a real device running iOS 16.7.10. See [`docs/GETTING-STARTED.md`](./docs/GETTING-STARTED.md).
- **AI**: `PYTHONPATH=. python3 -m mira.mcp.server --relay http://127.0.0.1:8765`. MCP config: [`docs/MCP.md`](./docs/MCP.md).

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
      <img src="./docs/Area.gif" alt="Android LSPosed Trace" />
      <div align="center"><sub>Construct a Frida path around the app classloader and surface LSPosed traces from runtime state.</sub></div>
    </td>
    <td>
      <img src="./docs/cydia-ios.gif" alt="iOS Jailbreak Trace" />
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

## Documentation

- [`docs/README.md`](./docs/README.md): English documentation hub.
- [`docs/GETTING-STARTED.md`](./docs/GETTING-STARTED.md): full setup, build, device connect, MCP, and CLI.
- [`docs/REMOTE-RELAY.md`](./docs/REMOTE-RELAY.md): public and LAN Relay startup flows.
- [`docs/MCP.md`](./docs/MCP.md): Codex and Claude MCP integration.
- [`docs/IOS-APP.md`](./docs/IOS-APP.md): iOS app architecture and device notes.
- [`docs/NATIVE-ARCHITECTURE.md`](./docs/NATIVE-ARCHITECTURE.md): shared PTY native architecture.
- [`docs/TOOLBOX.md`](./docs/TOOLBOX.md): Android toolbox packaging and runtime release flow.
- [`docs/REPO-ARCHITECTURE.md`](./docs/REPO-ARCHITECTURE.md): repository layering and entry-point layout.
- [`docs/THIRD-PARTY-NOTICES.md`](./docs/THIRD-PARTY-NOTICES.md): third-party notices.

## Acknowledgements

- [lamda](https://github.com/firerpa/lamda): inspiration for the web workbench interaction model.
- [Termux](https://github.com/termux/termux-app): Android terminal UX and extensible shell ecosystem.
- [iSH](https://github.com/ish-app/ish): iOS-side Linux shell compatibility and syscall translation path.

## License

`GPL-3.0-only`.
