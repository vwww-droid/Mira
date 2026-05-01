<p align="right">
  English | <a href="./README.zh-CN.md">简体中文</a>
</p>

<div align="center">
  <img src="./docs/mira-readme-icon-round.png" alt="Mira icon" width="138" />

# Mira

Turn Android and iOS runtime inspection into one AI-native defensive workflow.

<p>
  <img src="https://img.shields.io/badge/analysis-AI--native-0f172a?style=flat-square" alt="AI-native analysis" />
  <img src="https://img.shields.io/badge/platform-Android%20%2B%20iOS-2f855a?style=flat-square" alt="Android and iOS" />
  <img src="https://img.shields.io/badge/execution-live%20logic-2563eb?style=flat-square" alt="Live logic execution" />
  <img src="https://img.shields.io/badge/workflow-Relay%20%2B%20MCP-7c3aed?style=flat-square" alt="Relay and MCP" />
</p>
</div>

---

<div align="center">
  <strong>Inspect real mobile runtime state, execute live logic, and turn raw signals into repeatable hardening evidence.</strong>
</div>

## Features

- 🧩 **Third-party app sandbox shell**: Enter the real permission sandbox of target apps with a consistent Android and iOS experience.
- 🤖 **Built for AI**: Let AI operate like a user inside the third-party permission sandbox to explore risk paths.
- ⚡ **Arbitrary runtime execution**: Execute arbitrary Java and Native logic, or use JavaScript to construct objects and call non-exported methods.
- ♾️ **Persistent risk intelligence**: Turn one real finding into reusable knowledge and repeatable future discovery.

## Getting Started

- **Relay**: `PYTHONPATH=. python3 -m mira.relay.server --host 0.0.0.0 --port 8765 --advertise-url http://<your-lan-ip>:8765`
- **Browser**: Open `http://127.0.0.1:8765` on your desktop.
- **Android**: Download the APK from [Releases](https://github.com/vwww-droid/Mira/releases), install it, then enter `http://<your-lan-ip>:8765` in the app.
- **iOS**: Verified on a real device running iOS 16.7.10. See [`docs/GETTING-STARTED.md`](./docs/GETTING-STARTED.md).
- **AI**: `PYTHONPATH=. python3 -m mira.mcp.server --relay http://127.0.0.1:8765`. MCP config: [`docs/MCP.md`](./docs/MCP.md).

## Runtime Views

<table>
  <tr>
    <th align="center">Android</th>
    <th align="center">iOS</th>
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

- [`docs/README.md`](./docs/README.md): documentation hub.
- [`docs/GETTING-STARTED.md`](./docs/GETTING-STARTED.md): full setup, build, device connect, MCP, and CLI.
- [`docs/MCP.md`](./docs/MCP.md): Codex and Claude MCP integration.
- [`docs/IOS-APP.md`](./docs/IOS-APP.md): iOS app architecture and device notes.
- [`docs/NATIVE-ARCHITECTURE.md`](./docs/NATIVE-ARCHITECTURE.md): shared PTY native architecture.
- [`docs/THIRD-PARTY-NOTICES.md`](./docs/THIRD-PARTY-NOTICES.md): third-party notices.

## Acknowledgements

- [lamda](https://github.com/firerpa/lamda): inspiration for the web workbench interaction model.
- [Termux](https://github.com/termux/termux-app): Android terminal UX and extensible shell ecosystem.
- [iSH](https://github.com/ish-app/ish): iOS-side Linux shell compatibility and syscall translation path.

## License

`GPL-3.0-only`.
