# Remote On-Demand Terminal

## 目标

Remote On-Demand Terminal(按需远程终端) 现在采用手机主动连接 Relay Server(中继服务端) 的模式。

主路径不再依赖 Scan LAN(局域网扫描), 也不做二维码扫码。电脑启动 Relay 后生成一个 URL, Android App(安卓应用) 首页只需要填写这个 URL 并点击 Connect Relay(连接中继)。浏览器只有在用户点击 Open Terminal(打开终端) 后才会触发手机创建 PTY(伪终端)。

本阶段继续保持最小闭环:

1. Android 只维护轻量 control WebSocket(控制长连接), 不提前创建 PTY。
2. 浏览器打开 Relay 页面, 自动看到已经连接的设备。
3. 用户点击 Open Terminal 后, Relay 通过 control 通道下发 session.open。
4. Android 创建真实 PTY, 启动 shell(命令解释器), 再连接 `/ws/device`。
5. 浏览器连接 `/ws/browser`, 输入和输出都经过 Relay 转发。
6. Close Session 后关闭 PTY 和 device WebSocket, control 通道继续保持 idle(空闲) 状态。

## 一键公网启动

本机需要安装 cloudflared(Cloudflare 隧道命令行):

```bash
brew install cloudflare/cloudflare/cloudflared
```

启动:

```bash
./tools/relay/start-public-relay.sh
```

脚本会自动:

1. 启动 Cloudflare Quick Tunnel(随机公网隧道)。
2. 拿到随机 `https://*.trycloudflare.com` 地址。
3. 用这个地址启动 Mira Relay。
4. 打印 Browser URL 和 Android Relay URL。

手机端填写脚本打印的 Android Relay URL, 不需要 Scan LAN, 不需要二维码。

可选环境变量:

```bash
MIRA_RELAY_PORT=8765 ./tools/relay/start-public-relay.sh
MIRA_RELAY_HOST=127.0.0.1 ./tools/relay/start-public-relay.sh
```

## 局域网启动

如果只在局域网测试, 可以直接启动 Relay:

```bash
python3 -m mira.relay.server \
  --host 0.0.0.0 \
  --port 8765 \
  --advertise-url http://<电脑局域网IP>:8765
```

浏览器打开:

```text
http://<电脑局域网IP>:8765
```

手机端填写同一个地址:

```text
http://<电脑局域网IP>:8765
```

## Android 端使用

1. 安装并打开 Mira APK(安卓安装包)。
2. 首页填写:
   ```text
   Relay URL: Relay 页面显示的地址
   ```
3. 点击 `Connect Relay`。
4. 浏览器设备列表出现手机后, 点击 `Open Terminal`。
5. 需要退出时点击 `Close Session`, 或在手机上点击 `Disconnect`。

手机首页不再展示 Local Terminal(本地终端) 入口, 当前主路径只保留远程 Relay 连接。

## 协议概览

### 设备注册

Android 连接:

```text
/ws/control
```

首帧发送:

```json
{
  "type": "device.register",
  "protocol": 1,
  "installId": "uuid",
  "deviceName": "Pixel 4",
  "packageName": "com.vwww.mira",
  "androidIdHash": "sha256:...",
  "model": "Pixel 4",
  "sdk": 30,
  "arch": "arm64-v8a",
  "state": "idle",
  "transport": "control"
}
```

服务端回复:

```json
{"type":"control.ready","protocol":1,"installId":"uuid"}
```

### 按需打开终端

浏览器点击 Open Terminal 后, Relay 通过 `/ws/control` 发给 Android:

```json
{
  "type": "session.open",
  "protocol": 1,
  "installId": "uuid",
  "sessionId": "server-generated-uuid",
  "serverWs": "wss://example.trycloudflare.com/ws/device",
  "cols": 120,
  "rows": 36
}
```

Android 创建 PTY 后连接 `/ws/device`:

```json
{"type":"device.attach","protocol":1,"installId":"uuid","sessionId":"uuid"}
```

浏览器连接 `/ws/browser`:

```json
{"type":"browser.attach","protocol":1,"installId":"uuid","sessionId":"uuid"}
```

终端数据使用 JSON(JSON 数据格式) + base64(二进制文本编码):

```json
{"type":"terminal.input","sessionId":"uuid","dataBase64":"cHdkCg=="}
{"type":"terminal.output","sessionId":"uuid","dataBase64":"..."}
{"type":"terminal.resize","sessionId":"uuid","cols":120,"rows":36}
{"type":"session.close","sessionId":"uuid"}
```

## 会话工具箱

设备收到 session.open 后会在创建 PTY 前准备 Mira Toolbox(工具箱):

```text
/data/user/0/com.vwww.mira/cache/mira-sessions/<sessionId>/bin/busybox
```

随后将工具目录放到 PATH(命令搜索路径) 前面:

```text
PATH=/data/user/0/com.vwww.mira/cache/mira-sessions/<sessionId>/bin:$PREFIX/bin:/system/bin:/system/xbin
```

会话关闭后, Android 会删除该 session 工具目录。详细说明见 `docs/TOOLBOX.md`。

## 设备身份

Android 首次启动会生成并保存:

```text
installId = UUID.randomUUID()
deviceSecret = 32 bytes random
```

当前阶段:

1. `installId` 作为设备安装身份, 不是鉴权令牌。
2. `sessionId` 作为服务端生成的会话标识, 用来绑定本次 PTY 会话。
3. `deviceSecret` 只生成保存, 不外发, 不参与当前协议, HMAC(基于哈希的消息认证码) 留到后续。
4. `androidIdHash` 只作为辅助展示字段。

## 验收命令

远程终端打开后输入:

```sh
pwd
echo hello
busybox echo busybox-ok
command -v busybox
cat /proc/self/mountinfo | head
```

预期:

1. 输出来自手机真实 PTY。
2. 浏览器刷新后可以回放最近 1 MiB session 输出。
3. Close Session 后设备断开 `/ws/device` 并关闭 PTY。
4. `/ws/control` 保持连接, 设备状态回到 idle。
5. `busybox` 来自 `cache/mira-sessions/<sessionId>/bin`。

## 当前边界

1. 不做 Scan LAN 主路径, 旧 `/api/discover` 只作为兼容兜底保留。
2. 不做二维码扫码。
3. 每台设备同一时间只允许一个 active session。
4. 不实现 apt(包管理器), AI Agent(智能体) 自动执行或服务端动态工具包下发。
5. 本阶段不使用共享令牌, 默认依赖企业自部署服务端边界。后续企业认证可接账号, 证书或 HMAC。
6. 手机首页不暴露 Local Terminal(本地终端) 入口, 后续如需恢复只作为开发调试能力处理。
7. BusyBox 当前内置 arm64-v8a, armeabi-v7a, x86 和 x86_64 四个 ABI 版本, 但不提供 apt 或动态工具包下发。
