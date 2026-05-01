# 安装与使用

这份文档承接 README 中移出的安装, 构建, 连接和接入步骤, 方便把项目主页保持为产品化介绍页面.

## 1. 启动 Relay 和浏览器工作台

优先使用 Python 模块入口启动本地 Relay:

```bash
PYTHONPATH=. python3 -m mira.relay.server \
  --host 0.0.0.0 \
  --port 8765 \
  --advertise-url http://<电脑局域网IP>:8765
```

电脑浏览器打开:

```text
http://127.0.0.1:8765
```

Android 或 iOS 端填写电脑的局域网地址:

```text
http://<电脑局域网IP>:8765
```

不要在手机上填写 `127.0.0.1`. 对手机来说, `127.0.0.1` 指向的是手机自己, 不是你的电脑.

如果需要远程临时调试链路, 详见 `REMOTE-RELAY.md`. 仓库内也保留了 `./mira-local-web` 与 `./mira-web` 这类开发者快捷脚本, 但它们不是这里的主入口.

## 2. 构建并启动 Android App

fresh clone(全新克隆) 后先执行:

```bash
git submodule update --init --recursive
```

如果 Android 构建报 `third_party/... does not exist`, 基本表示子模块没有真正 checkout(检出) 完成, 先确认对应目录里不是只有 `.git` 占位.

```bash
./gradlew :mira-app:assembleDebug
adb install -r android/app/build/outputs/apk/debug/mira-app-debug.apk
adb shell am start -n com.vwww.mira/.MainActivity
```

如果要跑 Android 自动化链路, 可以直接使用:

```bash
MIRA_ANDROID_RELAY_URL="http://<host-ip>:8765" \
./mira-android
```

脚本会自动完成 build, adb install, 启动 App, 注入 Relay URL, 并自动触发连接.

Android shell 的默认路径和工作目录是:

```text
/data/user/0/com.vwww.mira/files/usr/bin/sh
/data/user/0/com.vwww.mira/files/home
```

原生 PTY 层已经整理为 Android 和 iOS 可共享的 POSIX(可移植操作系统接口) 架构, 详细边界见 `NATIVE-ARCHITECTURE.md`.

## 3. 构建并启动 iOS App

当前已验证的 iOS 链路是 **iOS 16.7.10 真机**. 这里优先按真机理解, 不把模拟器写成默认推荐路径.

iOS 侧已经接入 Relay, PTY, Mira App 自身 key window 画面上传, 设备指标采样, `/mira` app-view root 和 `/mira/proc` simulated process view(模拟进程视图). 详细说明见 `IOS-APP.md`.

如果要跑真机自动化链路, 推荐安装 `idb-companion` 与 `fb-idb`, 再通过环境变量把 Relay URL 注入到 App 启动环境:

```bash
git submodule update --init --recursive
bash ./tools/ios/build-frida-musl-devkit.sh
MIRA_IOS_AUTO_LAUNCH_DEVICE=1 \
MIRA_IOS_RELAY_URL="http://<电脑局域网IP>:8765" \
./mira-ios --device
```

当 `idb` 可用时, 真机启动会优先走 `idb launch`, App 会读取:

1. `MIRA_RELAY_URL`
2. `MIRA_AUTO_CONNECT`

从而在真机上自动填入并连接 Relay.

如果 fresh clone 后第一次跑真机链路, 还需要注意:

1. `./mira-ios --device` 会依赖 `third_party/ish` 和 Frida musl devkit(Frida musl 开发包).
2. 如果 Xcode 阶段卡在 `Prepare iSH RootFS`, 先检查宿主机 iSH host tools 是否已经生成.
3. 当前仓库已内置 `tools/ios/build-frida-musl-devkit.sh`, 跑完后会把 musl devkit 输出到 `build/frida/devkit/16.0.7/linux-x86-musl`.
4. 真机构建推荐固定清理宿主机 SDK 环境污染, 详见 `IOS-APP.md`.

如果你在仓库源码环境里开发, 也可以使用 `./mira-ios` 作为开发者快捷入口.

## 4. 连接移动端

1. 打开 Android 或 iOS 端 Mira App 首页.
2. 填写 Relay URL.
3. 点击 `Connect Relay`.
4. 回到浏览器等待设备列表出现.
5. 点击 `Open Terminal` 打开 App 沙盒会话.

服务端通过 control WebSocket(控制通道) 向设备发送 `session.open` 请求, 设备收到后才创建 PTY 并主动连接服务端.

## 5. MCP 接入

启动 Relay Server 后, MCP client 以 stdio(标准输入输出) 方式启动:

```bash
PYTHONPATH=. python3 -m mira.mcp.server \
  --relay http://127.0.0.1:8765
```

核心工具包括:

1. `mira_list_devices`: 读取已连接 Relay 的设备.
2. `mira_open_terminal`: 打开远程 PTY session(会话).
3. `mira_run_command`: 在同一个 PTY 中执行命令并读取输出.
4. `mira_collect_snapshot`: 采集第一轮 Android 分析快照.
5. `mira_close_terminal`: 关闭会话并清理设备侧临时状态.

如果要让 Codex 做移动风险分析, 可以让它读取 `skills/mira-mobile-risk-review`, 再针对当前授权会话生成观察步骤和 Case. 完整配置见 `MCP.md`.

## 6. CLI

```bash
PYTHONPATH=. python3 -m mira.cli devices
PYTHONPATH=. python3 -m mira.cli run 'pwd'
PYTHONPATH=. python3 -m mira.cli shell
```

`mira-cli` 直接使用 Relay HTTP 和 WebSocket, 不经过 MCP. 默认连接 `http://127.0.0.1:8765`, 也可以指定 Relay:

```bash
PYTHONPATH=. python3 -m mira.cli --relay https://example.invalid devices
PYTHONPATH=. python3 -m mira.cli run 'echo hello' --relay https://example.invalid
```

`shell` 会进入交互式远程 PTY, 按 `Ctrl-]` 退出本地 CLI 会话并关闭远程 session.

## 7. 统一构建与打包

仓库现在提供一个统一构建入口, 用来收口桌面控制端 Python package, Android APK 和 iOS device archive. 这里优先展示 Python 模块入口.

先确保本地已经有:

1. `python3`
2. Android `adb` 与 Gradle 依赖
3. Xcode 命令行工具
4. iOS 真机构建时需要可用的 device UDID

统一构建示例:

```bash
PYTHONPATH=. python3 -m mira.release
```

只构建 Python package 和 Android APK:

```bash
PYTHONPATH=. python3 -m mira.release --target python --target android
```

只构建 iOS 真机产物并显式指定设备:

```bash
PYTHONPATH=. python3 -m mira.release --target ios --ios-device-id <device-udid>
```

如果是给 CI 或本机无真机环境使用, 可以显式走 `generic iphoneos(通用 iPhoneOS 目标)`:

```bash
PYTHONPATH=. python3 -m mira.release --target ios --ios-destination-mode generic
```

如果你在仓库源码环境里开发, 也可以继续使用 `./mira-build` 作为快捷入口.

产物会统一落到:

```text
dist/
  python/
    mira-<version>.tar.gz
    mira-<version>-py3-none-any.whl
  android/
    mira-app-debug.apk
  ios/
    Mira-unsigned.ipa
    Mira.app.zip
  release-manifest.json
```

其中 iOS 流程会先做真机 `xcodebuild`, 再把 `Debug-iphoneos/Mira.app` 归档成:

1. `Mira-unsigned.ipa`
2. `Mira.app.zip`

`Mira.app.zip` 是保底归档物, 方便在 IPA 流程排障时保留原始 device `.app`.

## 8. 使用须知

使用 Mira 即表示你确认:

1. 只在自己拥有或已获得明确授权的 App, 设备和运行环境中使用 Mira.
2. 不使用 Mira 控制其他 App, 绕过平台保护, 访问未授权数据, 或执行系统级远程控制.
3. Mira 不提供生产 SDK, 静默后台控制, root, jailbreak 绕过, 或跨 App 自动化能力.
4. 所有会话都必须从 Mira App 内主动发起, 且仅限 Mira 自身 App 沙盒和普通第三方 App 权限范围.
5. 使用者需自行遵守适用法律, 平台规则和内部安全规范.

## 9. 重新 clone 后的恢复清单

如果本地目录丢失后重新 clone, 推荐按下面顺序恢复:

```bash
git submodule update --init --recursive
./gradlew :mira-app:assembleDebug
MIRA_ANDROID_RELAY_URL="http://<host-ip>:8765" ./mira-android
bash ./tools/ios/build-frida-musl-devkit.sh
MIRA_IOS_AUTO_LAUNCH_DEVICE=1 ./mira-ios --device
```

排障优先级建议:

1. 先看子模块是否完整.
2. 再看 iSH 相关静态库和 host tools 是否已经生成.
3. 最后再看 Frida musl devkit 是否存在.
