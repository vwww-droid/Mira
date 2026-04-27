# Mira

> 让 AI 自主探索与整合 App 运行时风险

Mira 允许 Agent 在授权的 App 沙盒会话中直接读取运行时信号, 理解系统 API, 识别可疑特征, 解释风险来源, 串起证据链, 并给出下一步验证路径.

它不是 SDK, 不是远控框架, 也不是只会报点位的黑盒检测器. Mira 希望是把原本依赖经验和手工排查的移动安全分析流程, 变成 AI 可复用, 可验证, 可持续演进的运行时分析流程.

## 核心能力

### AI 主导运行时风险发现

Mira 提供 AI 通过公网进入 App 沙盒运行时的 MCP 入口, 从当前进程, 沙盒文件视图, PTY 输出, 运行时状态和 App 自身画面中收集/观察/分析可疑信号并使用 Agent 能力进一步探索.

### Android / iOS 双端沙盒工作台

Mira 在 Android 和 iOS 上提供一致的浏览器工作台体验: 设备列表, App 实时画面, 沙盒 PTY, 运行态指标, 内置 Frida, MCP 工具调用. Android 侧直接暴露当前进程可见的 procfs 视角, 重点覆盖 `/proc/self/maps`, `/proc/self/status`, `/proc/self/mountinfo`, `/proc/self/fd`; iOS 侧则在 `/mira/proc` 下重建贴近 procfs 的进程视图, 把 images, maps, fd, task 等运行时信息组织成统一分析面.

配合 Relay, Mira 还可以把一次临时授权会话快速扩展到异地或复杂网络环境中的样本调试链路, 方便在受控前提下复现实验室之外的风险现场.

这并非系统级远控桌面, Android 侧进入的是 Mira 自身 App 沙盒和 `/proc/self/*` 当前进程视角, iOS 侧则通过 iSH, hostfs 和 `/mira/proc` 把 App 自身可见的真实运行时状态投影给 AI.

### 进程内置 Frida C/S 双端

Mira 选择将 Frida 作为公开的进程动态分析入手点, 而不是直接暴露更底层的生产级检测方案, 如自定义 AOP, linker, xdl 等. 原因不是后者做不到, 恰恰相反, 后者往往会更强, 但这类实现一旦被完整开源, 很容易被针对性研究, 复现和对抗, 反而会把项目本身变成攻击者的训练样本. 因此 Mira 只公开可验证的观测能力和跨平台研究底座, 不直接摊开生产环境中的底层接口和完整判定链路.

当然, 这也带来一个很现实的代价: Frida 自身就会引入进程注入特征, 如何在使用 Frida 作为公开研究层的同时继续识别, 隔离和评估 Frida 痕迹, 也是 Mira 的目标之一.

为把 Frida 真正内置入双端运行时, Mira 将进程内 Gadget 和 client 链路一起做进了进程内部: Android iOS 共用原生 PTY, shell 生命周期, syscall 转换和交叉编译底座; iOS 侧进一步完成了 iSH 适配, 将 Frida client runtime 预制进 rootfs, 通过端口直连进程内 Gadget, 再结合 hostfs, 轻量 procfs, dyld images 和 `vm_region_recurse_64`, 将真实进程视图映射到 `/mira/proc`, 让进程模拟终端中的分析体验尽量接近电脑终端.

### 持续沉淀 Skill 与 Case

后续每分析一个环境或框架, 都会沉淀三类资产:

1. 文章: 记录分析过程, 判断路径和关键坑点.
2. Case: 保留具体环境里的证据链, 不确定性和后续验证方向.
3. Skill: 把可复用的方法整理成 AI 可调用的检查流程.

希望可以沉淀一套可复查, 可迁移, 可被 AI 反复调用的移动安全分析方法. 如果你也关注 AI 如何提升移动安全开发效率, 可以 star 或 watch 这个项目.

当前公开验证主要覆盖 Android 13 与 iPhone X, 更多设备和 Frida 版本组合会在后续持续补齐适配.

后续也会逐步引入 built-in agent(内置智能体) 模式, 支持按服务端策略触发一轮本地自动分析与结构化上报.

## 授权研究边界

Mira 只面向授权研究和自有 App 分析

1. 只观察和交互 Mira 宿主 App 自身沙盒
2. 不控制其他 App
3. 不提供系统级远控能力
4. 不提供 root, jailbreak 绕过或系统沙盒绕过能力
5. 不提供生产 SDK 或静默后台控制能力
6. 所有会话都必须从 Mira App 内主动连接 Relay 后才存在

## 致谢

Mira 的实现和产品表达, 受到了不少优秀开源项目的启发, 在这里特别致谢:

1. [lamda](https://github.com/firerpa/lamda): Web 控制台的界面设计参考了 lamda 的工作台气质.
2. [Termux](https://github.com/termux/termux-app): Android 侧终端体验与可扩展终端生态给了 Mira 很多基础思路.
3. [iSH](https://github.com/ish-app/ish): iOS 侧 Linux shell 体验, 用户态仿真与 syscall 转换路径为 Mira 的 iOS 运行时工作台提供了重要参考.

感谢这些开源项目和维护者持续推动移动端终端, 自动化与研究基础设施的演进.

## 开源许可证

Mira 使用 `GPL-3.0-only`, 第三方组件按各自上游许可证分发, 详见 `docs/THIRD-PARTY-NOTICES.md`.

## 文档

详细文档见 `docs/`, 安装与使用入口见 `docs/GETTING-STARTED.md`.
