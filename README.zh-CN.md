<p align="right">
  <a href="./README.md">English</a> | 简体中文
</p>

<div align="center">
  <img src="./docs/mira-readme-icon-round.png" alt="Mira icon" width="138" />

# Mira

让 Android 与 iOS 运行时检查进入同一套 AI 原生防护工作流.

<p>
  <img src="https://img.shields.io/badge/analysis-AI--native-0f172a?style=flat-square" alt="AI-native analysis" />
  <img src="https://img.shields.io/badge/platform-Android%20%2B%20iOS-2f855a?style=flat-square" alt="Android and iOS" />
  <img src="https://img.shields.io/badge/execution-live%20logic-2563eb?style=flat-square" alt="Live logic execution" />
  <img src="https://img.shields.io/badge/workflow-Relay%20%2B%20MCP-7c3aed?style=flat-square" alt="Relay and MCP" />
</p>
</div>

---

<div align="center">
  <strong>检查真实移动端运行时状态, 动态执行逻辑, 再把原始信号转成可复用的防护证据.</strong>
</div>

## Features

- 🧩 **三方 App 沙箱 shell 工作台**: 直接进入目标 App 的真实权限沙箱, 并提供一致的 Android 与 iOS 体验.
- 🤖 **为 AI 构建**: 让 AI 如同用户一样在第三方权限沙箱内操作与探索风险.
- ⚡ **任意运行时逻辑执行**: 支持执行任意 Java 与 Native 层逻辑, 也可通过 JavaScript 构造对象并调用未导出方法.
- ♾️ **持续沉淀风险情报**: 把一次真实发现沉淀为可复用经验, 转化为可重复触发的后续发现能力.

## Getting Started

- **Relay**: `PYTHONPATH=. python3 -m mira.relay.server --host 0.0.0.0 --port 8765 --advertise-url http://<你的局域网IP>:8765`
- **浏览器**: 在电脑打开 `http://127.0.0.1:8765`
- **Android**: 从 [Releases](https://github.com/vwww-droid/Mira/releases) 下载 APK, 安装后在 App 中填写 `http://<你的局域网IP>:8765`
- **iOS**: 当前验证的是 iOS 16.7.10 真机. 详见 [`docs/GETTING-STARTED.md`](./docs/GETTING-STARTED.md)
- **AI 接入**: `PYTHONPATH=. python3 -m mira.mcp.server --relay http://127.0.0.1:8765`. MCP 配置见 [`docs/MCP.md`](./docs/MCP.md)

## Workflow

1. 启动 Relay 并打开 Mira 浏览器工作台.
2. 在 Android 或 iOS 上连接 Mira App.
3. 打开远程 PTY, 检查真实运行时状态.
4. 动态执行逻辑, 采集证据, 验证风险假设.
5. 将结果沉淀为可复用检查步骤和加固后续动作.

## Runtime Views

<table>
  <tr>
    <th align="center">Android</th>
    <th align="center">iOS</th>
  </tr>
  <tr>
    <td>
      <img src="./docs/android-remote-frida.png" alt="Android Remote Frida" />
      <div align="center"><sub>Android 侧远程 shell, 运行时检查与 Frida 动态执行视图.</sub></div>
    </td>
    <td>
      <img src="./docs/ios-remote-frida.png" alt="iOS Remote Frida" />
      <div align="center"><sub>iOS 侧对应的 PTY 与 Frida 工作流, 适配 iSH 兼容层.</sub></div>
    </td>
  </tr>
</table>

## Public Relay Access

![使用 cpolar 方案公网访问](./docs/public-deploy.png)

配合 Relay, 可将临时授权会话扩展到公网, 适用于云手机, 专家协作和实时证据交接.

## Research Boundaries

1. Mira 只观察和交互 Mira 宿主 App 自身沙盒.
2. Mira 不控制其他第三方 App.
3. Mira 不提供系统级远控能力.
4. Mira 不提供 root / jailbreak 绕过能力.
5. Mira 不提供生产 SDK 或静默后台控制链路.

## Documentation

- [`docs/README.md`](./docs/README.md): 文档总入口.
- [`docs/GETTING-STARTED.md`](./docs/GETTING-STARTED.md): 完整安装, 构建, 设备连接, MCP 和 CLI 说明.
- [`docs/MCP.md`](./docs/MCP.md): Codex 与 Claude 的 MCP 接入说明.
- [`docs/IOS-APP.md`](./docs/IOS-APP.md): iOS App 架构与设备侧说明.
- [`docs/NATIVE-ARCHITECTURE.md`](./docs/NATIVE-ARCHITECTURE.md): Android 与 iOS 共享 PTY 原生架构.
- [`docs/THIRD-PARTY-NOTICES.md`](./docs/THIRD-PARTY-NOTICES.md): 第三方许可证与来源说明.

## Acknowledgements

- [lamda](https://github.com/firerpa/lamda): Web 工作台交互设计参考.
- [Termux](https://github.com/termux/termux-app): Android 侧终端体验与可扩展终端生态.
- [iSH](https://github.com/ish-app/ish): iOS 侧 Linux shell 与 syscall 转换路径.

## License

`GPL-3.0-only`.
