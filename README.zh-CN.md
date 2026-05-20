<p align="right">
  <a href="./README.md">English</a> | 简体中文
</p>

<div align="center">
  <img src="./docs/mira-readme-icon-round.png" alt="Mira icon" width="138" />

# Mira

面向 Android 与 iOS 的移动端运行时检测工作台.

<p>
  <img src="https://img.shields.io/badge/analysis-AI--native-0f172a?style=flat-square" alt="AI-native analysis" />
  <img src="https://img.shields.io/badge/platform-Android%20%2B%20iOS-2f855a?style=flat-square" alt="Android and iOS" />
  <img src="https://img.shields.io/badge/execution-live%20logic-2563eb?style=flat-square" alt="Live logic execution" />
  <img src="https://img.shields.io/badge/workflow-Relay%20%2B%20MCP-7c3aed?style=flat-square" alt="Relay and MCP" />
</p>
</div>

---

<div align="center">
  <strong>Mira 以公开演进的方式构建, 作为一个长期项目, 持续把真实运行时案例沉淀成可复用的检测知识, 分析工作流和跨平台工具能力.</strong>
</div>

## Why follow Mira

- Mira 的成长来自真实运行时工作, 不只是预先规划好的功能列表.
- 每一个一线案例, 都可能继续长成新的工作流, 工具能力或检测笔记.
- 这个项目的目标, 是随着时间持续积累真正有用的移动端运行时知识.
- 关注 Mira, 也是在关注这些知识如何一步步变成真正可用的工具.

## Research Updates

Mira 会以公开方式持续更新, 并把不同形态的内容分发到不同平台.

不同平台分别承载不同部分的工作:

- GitHub 作为项目主记录地
- 文章用于完整案例和长篇分析
- 案例记录: [Android 模拟器 proc audit 侧信道暴露 qemu SELinux 上下文](./knowledge/cases/zh/2026/2026-05-20-android-emulator-proc-audit-sidechannel.md)
- 社区帖子用于更聚焦的技术分享
- 短更新用于 demo, 进展和小型发现

> 等我过完 2026 年 5 月初这轮劳动节假期后, 就开始继续更新. 祝大家 51 劳动节快乐, 也祝所有正在过 International Workers' Day 的朋友节日开心.

## Features

- 🧩 **直达真实 App 沙箱**: 直接进入目标 App 的真实权限沙箱, 用同一套工作流覆盖 Android 与 iOS.
- 🤖 **为 AI 分析员而生**: 让 AI 像真正上手的分析员一样, 在活体运行时里观察, 操作, 推理风险路径.
- ⚡ **运行时逻辑即开即打**: 随时执行 Java, Native, Frida 逻辑, 不再只靠静态线索猜.
- 🚀 **几分钟出第一批结果**: 启动 Relay, 装上检测端, 连上就能拿到 shell, screen 和 runtime 证据.
- ♾️ **发现一次, 复用很多次**: 把一次真实发现沉淀成可复用的检测模式和持续增益的防护情报.

## Getting Started

- **Relay**: `PYTHONPATH=. python3 -m mira.relay.server --host 0.0.0.0 --port 8765 --advertise-url http://<你的局域网IP>:8765`
- **浏览器**: 在电脑打开 `http://127.0.0.1:8765`
- **Android**: 从 [Releases](https://github.com/vwww-droid/Mira/releases) 下载 APK, 安装后在 App 中填写 `http://<你的局域网IP>:8765`
- **iOS**: 当前验证的是 iOS 16.7.10 真机. 详见 [`docs/GETTING-STARTED.zh-CN.md`](./docs/GETTING-STARTED.zh-CN.md)
- **AI 接入**: `PYTHONPATH=. python3 -m mira.mcp.server --relay http://127.0.0.1:8765`. MCP 配置见 [`docs/MCP.zh-CN.md`](./docs/MCP.zh-CN.md)

## Live Discovery Examples

<table>
  <tr>
    <th align="center">Android Remote Frida</th>
    <th align="center">iOS Remote Frida</th>
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
  <tr>
    <th align="center">Android LSPosed Trace</th>
    <th align="center">iOS Jailbreak Trace</th>
  </tr>
  <tr>
    <td>
      <img src="./docs/Area.gif" alt="Android LSPosed Trace" />
      <div align="center"><sub>通过 Frida 围绕 App classloader 构造运行时路径, 进一步发现 LSPosed 痕迹.</sub></div>
    </td>
    <td>
      <img src="./docs/cydia-ios.gif" alt="iOS Jailbreak Trace" />
      <div align="center"><sub>一句话让 Claude 在实时终端中漫游, 自动发现设备环境里的越狱工具痕迹.</sub></div>
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

- [`docs/README.zh-CN.md`](./docs/README.zh-CN.md): 简体中文文档总入口.
- [`docs/GETTING-STARTED.zh-CN.md`](./docs/GETTING-STARTED.zh-CN.md): 完整安装, 构建, 设备连接, MCP 和 CLI 说明.
- [`docs/REMOTE-RELAY.zh-CN.md`](./docs/REMOTE-RELAY.zh-CN.md): 公网与局域网 Relay 启动方式.
- [`docs/MCP.zh-CN.md`](./docs/MCP.zh-CN.md): Codex 与 Claude 的 MCP 接入说明.
- [`docs/IOS-APP.zh-CN.md`](./docs/IOS-APP.zh-CN.md): iOS App 架构与设备侧说明.
- [`docs/NATIVE-ARCHITECTURE.zh-CN.md`](./docs/NATIVE-ARCHITECTURE.zh-CN.md): Android 与 iOS 共享 PTY 原生架构.
- [`docs/TOOLBOX.zh-CN.md`](./docs/TOOLBOX.zh-CN.md): Android 内置工具箱打包与释放流程.
- [`docs/REPO-ARCHITECTURE.zh-CN.md`](./docs/REPO-ARCHITECTURE.zh-CN.md): 仓库分层与入口布局说明.
- [`docs/THIRD-PARTY-NOTICES.zh-CN.md`](./docs/THIRD-PARTY-NOTICES.zh-CN.md): 第三方许可证与来源说明.

## Acknowledgements

- [lamda](https://github.com/firerpa/lamda): Web 工作台交互设计参考.
- [Termux](https://github.com/termux/termux-app): Android 侧终端体验与可扩展终端生态.
- [iSH](https://github.com/ish-app/ish): iOS 侧 Linux shell 与 syscall 转换路径.

## License

`GPL-3.0-only`.
