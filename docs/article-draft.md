<p align="right">
  English | <a href="./article-draft.zh-CN.md">简体中文</a>
</p>

# Article Draft | Mira: Let AI Operate Android and iOS Runtimes Directly

---

## Background

**This is a defensive research tool.**

Runtime risk-environment detection in mobile security has several recurring problems. Tools are often closed-source or scattered. Once a path is published, it can be targeted quickly. Different hostile environments also hide themselves in different ways, so every new version can force analysts to redo the workflow from scratch.

AI is useful for analysis, but in most setups it cannot see the real runtime state of the device. It depends on copied logs, screenshots, and human retelling. Once information passes through manual forwarding, context is lost and both efficiency and accuracy drop.

Mira is not just trying to improve information display. It is trying to let AI actually enter the device runtime, observe the environment, execute logic, validate hypotheses, and then produce analysis conclusions.

In one sentence, **Mira is a dynamic analysis workbench that connects AI to Android and iOS runtimes.**

---

## Core capabilities

### Unified Android and iOS workbench

Mira provides a unified runtime operation entry on both Android and iOS. It ships a BusyBox command set and Frida Gadget, and exposes an interactive terminal. On Android, it can directly inspect procfs from the process perspective. On iOS, it simulates a close command-line experience through syscall translation.

| Android | iOS |
| --- | --- |
| ![](./android-remote-frida.png) | ![](./ios-remote-frida.png) |

### Built-in mira-mcp

Mira exposes device-side capabilities to AI through MCP. That means models such as Claude are no longer limited to reading forwarded logs. They can issue commands, execute in-process logic, and analyze runtime traits directly.

![](./mira-mcp-claude.png)

### Reusable knowledge accumulation

Each environment or framework analysis can produce three kinds of assets: articles, cases, and skills. If you care about how AI can improve mobile security efficiency, starring or watching the project helps you follow that accumulation.

---

## Demo scenarios

### Scenario 1: Use the Unix shell to quickly surface hidden traces

A frequently overlooked side channel comes from differences in `stat` results. For the same unreadable path, `No such file or directory` means the path does not exist at all, while `Permission denied` means the path exists but is inaccessible to the current process.

That means even a third-party app permission set can still infer the existence of sensitive directories by probing many candidate paths and comparing return states.

The image below shows AI using the Mira Unix shell to do that probing and surface Magisk installation traces on Android:

![](https://github.com/vwww-droid/Mira/releases/download/v1.1.2/stat-magisk.preview.gif)

[Download original GIF](https://github.com/vwww-droid/Mira/releases/download/v1.1.2/stat-magisk.gif)

The same idea also works on iOS. Mira exposes Android-like file and process views under `/mira/` on iOS so familiar command-line workflows can still be used there.

![](https://github.com/vwww-droid/Mira/releases/download/v1.1.2/cydia-ios.preview.gif)

[Download original GIF](https://github.com/vwww-droid/Mira/releases/download/v1.1.2/cydia-ios.gif)

The value of this scenario is that many steps that used to require manual experience can now be repeated by AI at low cost and scaled naturally.

### Scenario 2: Dynamically identify LSPosed injection traits

This scenario answers a common question: when you suspect LSPosed or a similar framework has injected into the target process, how do you validate runtime traits quickly instead of stopping at static guesses.

Mira ships Frida Gadget and an adapted frida-cli, so AI can use mira-mcp to run Java-side and native-side dynamic logic directly. That makes it easy to probe `ClassLoader`, enumerate classes, inspect stacks, and identify unusual loading chains.

![](https://github.com/vwww-droid/Mira/releases/download/v1.1.2/Area.preview.gif)

[Download original GIF](https://github.com/vwww-droid/Mira/releases/download/v1.1.2/Area.gif)

The value here is that many long validation paths can now become a fast loop of hypothesis and runtime verification.

### Scenario 3: Remote collaborative analysis

When the sample, device, or runtime environment is not physically with you, Mira can temporarily expose the authorized session through cpolar and later other tunnel providers. That lets another analyst join the same environment directly instead of relying on screenshots and verbal relays.

![](./public-deploy.png)

The core value is not only remote access. It is turning analysis from a highly local, experience-heavy activity into a shareable and reproducible workflow.

---

## Deployment

There are currently two main access modes:

- **Local mode**: direct local workflows for daily analysis and debugging
- **Public Relay mode**: extend the session to the public internet for cloud devices or remote collaboration

---

## Boundaries

Mira is for authorized research and analysis of your own app environment. It observes and interacts only with capabilities inside the Mira host app sandbox. It does not control unrelated apps, does not provide root or jailbreak bypass, and is not intended as a production SDK or a silent background control channel.

---

## Closing

If you work on Android or iOS risk-environment analysis, want AI to participate in dynamic verification, or have new samples and evasion cases worth turning into reusable knowledge, contributions are welcome.
