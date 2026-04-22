# iOS App(苹果移动应用)

本文记录 Mira iOS App 的当前状态和启动方式。本阶段目标只做 App shell(应用壳) 和首页 UI(用户界面), 参考 Android(安卓系统) 首页, 不在本阶段接 fork(派生进程) 和 PTY(伪终端) 进程链路。

## 当前范围

已完成:

1. `ios/Mira/Mira.xcodeproj` Xcode project(Xcode 项目)。
2. SwiftUI(Swift 声明式界面框架) 首页。
3. Relay URL(中继地址) 输入框。
4. `Connect Relay` 和 `Disconnect` 按钮。
5. `Status` 状态文本。
6. `NativeBridge` 目录占位, 给下一阶段 C bridge(C 语言桥接层) 接入使用。
7. 根目录 `./mira-ios` 启动入口。

暂不做:

1. 不连接 Relay Server(中继服务端)。
2. 不启动 PTY。
3. 不接 fork/exec(派生进程和替换进程镜像) 原生链路。
4. 不接 WebView(网页视图) 或 Web Terminal(网页终端)。

## 目录

```text
ios/
  Mira/
    Mira.xcodeproj/
    Mira/
      App/
        MiraApp.swift
        ContentView.swift
        MiraControlViewModel.swift
      NativeBridge/
        MiraNativeStatus.swift
      Resources/
        README.md
```

## 打开方式

### Xcode 打开

```bash
open ios/Mira/Mira.xcodeproj
```

在 Xcode 中选择:

```text
Mira -> iPhone 17 Pro -> Run
```

### 命令行启动

```bash
./mira-ios
```

脚本会执行:

1. 检查 Xcode 命令行工具。
2. 确保 iOS Simulator(iOS 模拟器) 已启动。
3. 使用 `xcodebuild` 构建 Debug(调试) 版本。
4. 使用 `simctl install` 安装到 booted simulator(已启动模拟器)。
5. 使用 `simctl launch` 打开 App。

可选环境变量:

```bash
MIRA_IOS_DEVICE="iPhone 17 Pro" ./mira-ios
MIRA_IOS_SCHEME="Mira" ./mira-ios
MIRA_IOS_BUNDLE_ID="com.vwww.mira.ios" ./mira-ios
```

## 首页 UI 对齐 Android

Android 首页当前是极简控制页:

1. 左侧大标题 `Mira`。
2. 右侧小字 `by vw2x`。
3. 中间 Relay URL 输入框。
4. `Connect Relay` 按钮。
5. `Disconnect` 按钮。
6. monospaced(等宽字体) 状态文本。

iOS 当前保持同样的信息架构, 但使用 SwiftUI 原生控件。

## 下一阶段接入点

下一阶段做 fork/exec 和 PTY 时, 建议从这里开始:

```text
ios/Mira/Mira/NativeBridge/
```

目标文件建议为:

```text
ios/Mira/Mira/NativeBridge/MiraPtySession.swift
ios/Mira/Mira/NativeBridge/MiraPtyProcess.swift
ios/Mira/Mira/NativeBridge/MiraPtyLaunchSpec.swift
```

Swift 层通过以下 C shim(C 语言薄适配层) 接入 native core(原生核心层):

```text
native/bridge/ios/mira_pty_ios_shim.h
native/bridge/ios/mira_pty_ios_shim.c
```
