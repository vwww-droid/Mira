# Android APK MVP

## 当前闭环

当前 Mira 已经从纯 Python(服务端语言) 原型推进到 Android APK(安卓安装包) 闭环。

闭环链路是:

```text
Mira APK
  -> WebView(网页视图)
  -> 127.0.0.1 随机端口本地 HTTP(超文本传输协议) 服务
  -> WebSocket(网页长连接协议)
  -> Termux terminal-emulator(终端模拟器) JNI(本地接口)
  -> PTY(伪终端)
  -> /data/user/0/com.vwww.mira/files/usr/bin/sh
  -> Mira 应用私有沙盒目录
```

## 复用了 Termux 的什么

1. `third_party/termux-app` 已作为 submodule(代码子模块) 引入。
2. Android 构建直接依赖 Termux 的 `terminal-emulator` 模块。
3. PTY 创建使用 Termux 的 native(原生) `JNI.createSubprocess(...)` 能力。
4. Mira 自己只包了一层很薄的 `MiraPtyProcess`, 用于把 PTY 字节流转给 WebSocket。
5. Mira 新增最小 bootstrap(启动用户空间), 在私有沙盒里创建 `files/usr` 目录结构。

## 当前沙盒边界

当前 shell(命令解释器) 通过 Mira 自己的 bootstrap wrapper(启动包装器) 启动在 Android 应用沙盒里:

```text
/data/user/0/com.vwww.mira/files/usr/bin/sh
/data/user/0/com.vwww.mira/files/home
```

当前环境变量包含:

```text
PREFIX=/data/user/0/com.vwww.mira/files/usr
HOME=/data/user/0/com.vwww.mira/files/home
TMPDIR=/data/user/0/com.vwww.mira/cache/tmp
PATH=/data/user/0/com.vwww.mira/files/usr/bin:/system/bin:/system/xbin
TERM=xterm-256color
MIRA_SANDBOX=1
SHELL=/data/user/0/com.vwww.mira/files/usr/bin/sh
```

## 电脑浏览器调试

APK 内服务只监听设备内 `127.0.0.1`。如果要从电脑浏览器打开, 需要先做 adb forward(安卓调试桥端口转发):

```bash
adb logcat -d -s Mira:I
adb forward tcp:8765 tcp:<device_port>
open "http://127.0.0.1:8765/?token=<token>"
```

`<device_port>` 和 `<token>` 来自 debug(日志调试) 日志:

```text
Mira Web Terminal listening on http://127.0.0.1:<device_port>/?token=<token>
```

注意: 浏览器页面的 HTTP(超文本传输协议) 来源会变成 `http://127.0.0.1:8765`, 但设备内服务实际端口仍是随机端口。服务端会在 token 正确时允许 localhost 来源, 避免 WebSocket(网页长连接协议) 因端口不一致被拒绝。这个 token 是 Local Terminal(本地终端) 调试专用, 不参与 Remote Relay(远程中继) 协议。

## 当前明确限制

1. 当前还没有引入 Termux bootstrap(启动根文件系统) 和 apt(包管理器) 环境。
2. 当前 shell 使用 Android 系统自带 `/system/bin/sh`。
3. 远程 Relay(中继) session 会释放内置 BusyBox(单文件工具集) 到临时目录, Local Terminal(本地终端) 暂不接入。
4. 当前是单 WebSocket 连接对应单 PTY 会话。
5. 当前没有实现 Probe(检测探针), Agent(智能体) 分析, 多设备管理或任务编排。

## 为什么不直接复制 TermuxActivity

`TermuxActivity` 和 `TermuxService` 是完整 Termux 产品壳, 包含会话列表, 前台服务, 插件入口, 设置页, 通知和很多包名假设。

Mira 当前只需要先证明第三方 APK 内可以持有真实 PTY 并通过 Web Terminal 操作 shell。最小正确复用点是 Termux 的 `terminal-emulator`, 而不是把完整 Termux app 复制进来。

## 下一步

下一步最自然的是把最小 bootstrap 升级为可安装包的 Mira bootstrap:

1. 构建或准备以 `com.vwww.mira` 为 prefix(路径前缀) 的 bootstrap 包。
2. 将 `/data/user/0/com.vwww.mira/files/usr` 从目录骨架升级成真正的类 Termux 用户空间。
3. 启动 shell 时优先使用 `files/usr/bin/bash` 或真实 `files/usr/bin/sh`。
4. 按需补齐 BusyBox 的其他 ABI(应用二进制接口) 版本。
5. 再评估是否接入 apt 包管理能力。
6. 保留当前 WebSocket + PTY 通路不变。
