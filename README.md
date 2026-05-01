<p align="right">
  English | <a href="./README.zh-CN.md">简体中文</a>
</p>

<div align="center">
  <img src="./apps/console/app/icon-round.png" alt="Mira icon" width="180" style="border-radius: 50%;" />

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

- **Cross-platform runtime workspace**: Use one Relay-based workbench across Android and iOS instead of splitting research into separate toolchains.
- **AI-assisted analysis**: Connect Codex or Claude through `mira-mcp` so AI can inspect the same live device session as the researcher.
- **Live Java and Native execution**: Run Frida-backed logic directly inside the target app runtime and validate hypotheses without leaving the session.
- **Defense-oriented evidence collection**: Surface hook traces, environment fingerprints, process state, PTY output, and verification signals relevant to mobile hardening.

## Getting Started

- **Local Relay**: Start the local browser console with `./mira-local-web`.
- **Android**: Use `MIRA_ANDROID_RELAY_URL="http://<host-ip>:8765" ./mira-android` for build, install, and auto-connect.
- **iOS**: Use `./mira-ios` for simulator work, or follow the real-device flow in [`docs/GETTING-STARTED.md`](./docs/GETTING-STARTED.md).
- **AI integration**: Start `python3 -m mira.mcp.server --relay http://127.0.0.1:8765` and connect from Codex or Claude.

## Workflow

1. Launch Relay and open the Mira browser console.
2. Connect the Mira app on Android or iOS.
3. Open the remote PTY and inspect the real runtime state.
4. Execute live logic, collect evidence, and validate risk hypotheses.
5. Convert findings into repeatable review steps and hardening follow-ups.

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

- [`docs/GETTING-STARTED.md`](./docs/GETTING-STARTED.md)
- [`docs/MCP.md`](./docs/MCP.md)
- [`docs/IOS-APP.md`](./docs/IOS-APP.md)
- [`docs/NATIVE-ARCHITECTURE.md`](./docs/NATIVE-ARCHITECTURE.md)
- [`docs/THIRD-PARTY-NOTICES.md`](./docs/THIRD-PARTY-NOTICES.md)

## Acknowledgements

- [lamda](https://github.com/firerpa/lamda): inspiration for the web workbench interaction model.
- [Termux](https://github.com/termux/termux-app): Android terminal UX and extensible shell ecosystem.
- [iSH](https://github.com/ish-app/ish): iOS-side Linux shell compatibility and syscall translation path.

## License

`GPL-3.0-only`.
