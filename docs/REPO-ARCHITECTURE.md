# Repository Architecture(仓库架构)

Mira 仓库按平台壳, 共享资源和原生底层分层。

## 顶层目录

```text
android/      Android APK(安卓安装包) 应用壳
ios/          iOS App(苹果移动应用) 应用壳
native/       C 原生能力, Android 和 iOS 共享
shared/       未来放跨平台 Web Terminal(网页终端), 协议和 toolbox(工具箱)
tools/        开发启动脚本和辅助工具
docs/         架构, 运行和协议文档
mira/         Python(脚本语言) Relay, MCP 和 CLI
apps/console/ 浏览器控制台前端
```

## 分层规则

1. `android/` 只放 Android 平台壳和打包配置。
2. `ios/` 只放 iOS 平台壳和 Xcode project(Xcode 项目)。
3. `native/` 放跨平台 PTY(伪终端) 和进程能力。
4. `shared/` 后续放 Android 和 iOS 都要使用的网页终端, 协议定义和工具箱资产。
5. `tools/` 放可以从仓库根目录调用的启动脚本。
6. 平台 UI(用户界面) 不直接访问 `native/src` 内部文件, 只能通过 `native/include` 和对应 bridge(桥接层)。

## 当前启动入口

```bash
./mira-web        # 公网 Relay, 默认走 cpolar(内网穿透服务)
./mira-local-web  # 局域网 Relay, 浏览器走 localhost, 手机走局域网 IP
./mira-ios        # 构建并启动 iOS Simulator 中的 Mira App
```

## iOS 与 Android 的关系

Android 已经有真实 Relay 和 PTY 闭环。iOS 当前先补应用壳和 UI, 让仓库结构稳定下来。

下一阶段再把 `native/src/posix` 中的 fork/exec 公共链路接到 iOS 的 `NativeBridge`。
