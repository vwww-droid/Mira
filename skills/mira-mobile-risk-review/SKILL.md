---
name: mira-mobile-risk-review
description: Use when Codex analyzes a Mira mobile risk case through Mira MCP, iOS or Android sandbox evidence, app-view filesystem mounts, /mira virtual filesystem, Android /proc/self maps, third-party risk labels, emulator or tamper suspicion, and needs to produce a local cases directory report or draft reusable skill notes without shipping detection code.
---

# Mira Mobile Risk Review

## 核心定位

使用这个 skill 分析 Mira MCP(Model Context Protocol, 模型上下文协议) 暴露的移动端风险案例。Mira 不提供黑盒检测代码, 只沉淀风险判断方法, 权限边界, 证据分级, 误报来源和案例报告。

默认输出本地 case(案例), 不默认提交 PR(Pull Request, 代码合并请求), 不默认修改线上策略。

## 工作流

1. 读取当前风险触发来源: 三方风险标签, 情报线索, userId(用户标识), installId(安装标识)或人工问题。
2. 确认平台和权限边界: Android app sandbox(Android 应用沙盒), iOS app sandbox(iOS 应用沙盒)或 Mira virtual filesystem(Mira 虚拟文件系统)。
3. 读取 `references/platform-boundaries.md`, 只采集该平台允许解释的证据。
4. 读取 `references/risk-reasoning.md`, 用 observation-first(现象优先)方式记录所有不对劲行为, 不做 1/2/3 或 high/medium/low 分级压制。
5. 如需写报告, 读取 `references/case-report-template.md`, 在 `cases/<githubName>-<yyyymmdd>-<topic>/` 生成脱敏报告。
6. 只在用户明确要求时, 才把 `skill-notes.md` 合并回 skill 或准备贡献变更。

## 平台采集原则

- iOS: 将 `/mira` 视为 Mira 提供的应用视角文件系统投影, 不把它等同系统真实根目录。将 `/mira/proc` 视为 Mira 模拟的 process view(进程视图), 不把它等同 kernel procfs(内核进程文件系统)。
- Android: 优先分析当前 Mira app 进程和 shell 可见范围, 包括 `/proc/self/maps`, `/proc/self/status`, `/proc/self/mountinfo`, `/proc/self/fd` 和系统公开信息。
- UI upload(界面上传): 只描述 Mira App 自己的 key window(主窗口)画面, 不描述为系统全屏采集。

## 输出约束

- 所有可能不对劲的行为都要写出来, 哪怕只是单一信号, 暂时无法解释或最后可能是误报。
- 每条观察必须区分 `看到了什么` 和 `这可能意味着什么`, 不用 1/2/3, high/medium/low 或 risk score(风险分数)做定级。
- 结论必须写明 evidence source(证据来源), permission boundary(权限边界), possible_explanations(可能解释) 和 next_check(下一步检查)。
- 不输出真实 userId, deviceId(设备标识), token(令牌), IP(网络地址), cookie(会话凭据) 或完整私有路径。
- 不建议绕过 iOS 或 Android 权限模型。
- 不默认提交 PR, 不默认发布 case。

## 何时读取 references

- 需要解释 iOS `/mira`, `/mira/proc`, UI upload 或 Android `/proc/self/maps` 时, 读取 `references/platform-boundaries.md`。
- 需要判断模拟器, 云手机, Hook(运行时劫持), 改机或运行时异常时, 读取 `references/risk-reasoning.md`。
- 需要生成 `cases/` 报告时, 读取 `references/case-report-template.md`。
