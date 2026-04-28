<p align="center">
  <img src="./apps/console/app/icon.svg" alt="MIRA icon" width="88" />
</p>

<h1 align="center">MIRA</h1>

<p align="center">
  <img src="https://img.shields.io/badge/Android-iOS-2f855a?style=flat-square" alt="Android and iOS" />
  <img src="https://img.shields.io/badge/Built--in-MCP-0f172a?style=flat-square" alt="Built-in MCP" />
  <img src="https://img.shields.io/badge/Built--in-Frida-dc2626?style=flat-square" alt="Built-in Frida" />
  <img src="https://img.shields.io/badge/Relay-Ready-2563eb?style=flat-square" alt="Relay Ready" />
</p>

<p align="center">
  AI 时代移动安全防护策略快速产出工具
</p>

<p align="center">
  <a href="./docs/GETTING-STARTED.md">Getting Started</a> ·
  <a href="./docs/">Documentation</a> ·
  <a href="./docs/THIRD-PARTY-NOTICES.md">Third-Party Notices</a>
</p>



## 核心能力

### AI 运行时风险发现

展示运行时真实沙盒环境, 内置 Frida 实时执行 Java/Native 逻辑, 提供 mira-mcp 供 AI 实时分析环境风险.

![claude 分析算法助手的 hook 痕迹](./docs/Area.gif)


### Android / iOS 双端工作台

动态集成 busybox 命令集和 Frida gadget, 交互式分析进程视角 procfs (iOS 为 syscall 模拟实现).

<table>
  <tr>
    <th align="center">Android</th>
    <th align="center">iOS</th>
  </tr>
  <tr>
    <td><img src="./docs/android-remote-frida.png" alt="Android Remote Frida" /></td>
    <td><img src="./docs/ios-remote-frida.png" alt="iOS Remote Frida" /></td>
  </tr>
</table>

> 当前内置删减版 Frida gadget, 存在注入特征和偶发崩溃, 下个迭代会逐步去特征并优化稳定性. 欢迎提 issue / PR.


### 可选公网访问

配合 Relay, 可通过[脚本](./mira-web)将临时授权会话扩展到公网 —— 适用于云手机等不便 adb 的场景, 也方便快速邀请他人协助分析 🤝

![使用 cpolar 方案公网访问](./docs/public-deploy.png)


### 持续沉淀经验

每分析一个环境或框架, 沉淀三类资产: **文章**(分析过程与关键坑点)、**Case**(证据链与后续验证方向)、**Skill**(可被 AI 调用的检查流程). 如果你关注 AI 如何提升移动安全效率, 欢迎 star / watch.



## 授权研究边界

Mira 只面向授权研究和自有 App 分析:

1. 只观察和交互 Mira 宿主 App 自身沙盒
2. 不控制其他 App
3. 不提供系统级远控能力
4. 不提供 root / jailbreak 绕过或系统沙盒绕过能力
5. 不提供生产 SDK 或静默后台控制能力 (必须从 Mira App 内主动连接 Relay 后才存在)



## 致谢

- [lamda](https://github.com/firerpa/lamda): Web 控制台 UI 完全仿照 lamda 工作台.
- [Termux](https://github.com/termux/termux-app): Android 侧终端体验与可扩展终端生态.
- [iSH](https://github.com/ish-app/ish): iOS 侧 Linux shell 与 syscall 转换路径.



## 开源许可证

`GPL-3.0-only`. 第三方组件按各自上游许可证分发, 详见 `docs/THIRD-PARTY-NOTICES.md`.

详细文档见 `docs/`, 安装入口见 `docs/GETTING-STARTED.md`.
