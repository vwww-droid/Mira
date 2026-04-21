# Remote On-Demand Terminal

## 目标

Remote On-Demand Terminal(按需远程终端) 把 Mira 从设备内 WebView(网页视图) 终端扩展为局域网可发现, 服务端按需唤起, 浏览器远程控制 Android PTY(安卓伪终端) 的工作台。

本阶段只做局域网 MVP(最小可行产品):

1. Android App(安卓应用) 平时只运行轻量 Discovery Service(发现服务)。
2. 服务端扫描局域网发现设备。
3. 用户点击 Open Terminal(打开终端) 后, 服务端请求设备打开 session(会话)。
4. 设备释放内置 BusyBox(单文件工具集) 到临时 session 工具目录。
5. 设备创建真实 PTY 并主动连接服务端 WebSocket(网页长连接协议)。
6. 浏览器通过服务端控制同一个手机 shell(命令解释器)。

本阶段不使用 Pairing Token(配对令牌) 或共享口令。企业自托管时默认由自己的 Relay Server(中继服务端) 控制入口, 协议只保留设备身份和会话绑定字段。

## 启动服务端

```bash
python3 -m mira.relay.server \
  --host 0.0.0.0 \
  --port 8765 \
  --discovery-port 8766 \
  --advertise-url http://<电脑局域网IP>:8765
```

浏览器打开:

```text
http://<电脑局域网IP>:8765
```

## Android 端使用

1. 安装并打开 Mira APK(安卓安装包)。
2. 首页填写:
   ```text
   Device Name: 任意名称
   Discovery Port: 8766
   ```
3. 点击 `Start Discovery`。
4. 回到浏览器点击 `Scan LAN`。
5. 发现设备后点击 `Open Terminal`。

## 协议概览

### UDP 发现

服务端广播:

```json
{
  "type": "mira.discover",
  "protocol": 1,
  "serverUrl": "http://192.168.1.10:8765",
  "nonce": "random"
}
```

设备响应:

```json
{
  "type": "mira.device",
  "protocol": 1,
  "installId": "uuid",
  "deviceName": "Pixel 4",
  "packageName": "com.vwww.mira",
  "androidIdHash": "sha256:...",
  "model": "Pixel 4",
  "sdk": 30,
  "arch": "arm64-v8a",
  "state": "idle",
  "wakeUrl": "http://192.168.1.23:39091/session/open"
}
```

### 按需唤起

服务端 POST 到设备 `wakeUrl`:

```json
{
  "type": "session.open",
  "protocol": 1,
  "installId": "uuid",
  "sessionId": "server-generated-uuid",
  "serverWs": "ws://192.168.1.10:8765/ws/device",
  "cols": 120,
  "rows": 36
}
```

设备创建 PTY 后连接服务端:

```json
{"type":"device.attach","protocol":1,"installId":"uuid","sessionId":"uuid"}
```

浏览器连接服务端:

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

### 会话工具箱

设备收到 wake 请求后会在创建 PTY 前准备 Mira Toolbox(工具箱):

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
3. Close Session 后设备断开 WebSocket 并关闭 PTY, Discovery Service 回到 idle。
4. `busybox` 来自 `cache/mira-sessions/<sessionId>/bin`。

## 当前边界

1. 只支持 `ws://`, 不支持 `wss://`。
2. 只保证局域网可用, 公网需要 TLS 和 Push 唤醒。
3. 每台设备同一时间只允许一个 active session。
4. 不实现 apt(包管理器), AI Agent(智能体) 自动执行或服务端动态工具包下发。
5. 本阶段不使用共享令牌, 默认依赖企业自部署服务端边界。后续企业认证可接账号, 证书或 HMAC。
6. Local Terminal(本地终端) 模式里仍有本地 WebView 专用的一次性 token, 该 token 不参与远程 Relay 协议。
7. BusyBox 当前内置 arm64-v8a, armeabi-v7a, x86 和 x86_64 四个 ABI 版本, 但不提供 apt 或动态工具包下发。
