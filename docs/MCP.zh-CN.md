<p align="right">
  <a href="./MCP.md">English</a> | 简体中文
</p>

# Mira MCP Server

Mira MCP 让 Codex、Claude 等 AI 客户端通过 Relay 操作已连接的 Mira 设备。Relay 负责设备传输和会话，MCP Server 将这些能力提供为 stdio JSON-RPC 工具。

```text
AI 客户端 -> Mira MCP -> Relay -> Android 或 iOS Mira -> PTY 和运行时操作
```

## 启动

先启动 Relay：

```bash
python3 -m mira.relay.server \
  --host 0.0.0.0 \
  --port 8765 \
  --advertise-url http://<电脑局域网 IP>:8765
```

再配置客户端启动 MCP Server：

```bash
python3 -m mira.mcp.server --relay http://127.0.0.1:8765
```

也可以用 `MIRA_RELAY_URL` 代替 `--relay`。命令应从仓库根目录运行，并让 `PYTHONPATH` 指向该目录。

Claude Desktop 通常读取 `~/Library/Application Support/Claude/claude_desktop_config.json`：

```json
{
  "mcpServers": {
    "mira": {
      "command": "<Python 路径>",
      "args": ["-m", "mira.mcp.server", "--relay", "http://127.0.0.1:8765"],
      "cwd": "<Mira 仓库路径>",
      "env": { "PYTHONPATH": "<Mira 仓库路径>" }
    }
  }
}
```

Codex 读取 `~/.codex/config.toml`：

```toml
[mcp_servers.mira]
command = "<Python 路径>"
args = ["-m", "mira.mcp.server", "--relay", "http://127.0.0.1:8765"]
cwd = "<Mira 仓库路径>"
env = { PYTHONPATH = "<Mira 仓库路径>" }
default_tools_approval_mode = "approve"
```

修改 MCP Python 代码或配置后，需要重启或重新连接 MCP 客户端。已经运行的 MCP 进程不会自动加载新代码。

## 常用分析流程

1. 用 `mira_list_devices` 查找设备，并按准确的 `installId` 选择目标。
2. 用 `mira_open_terminal` 打开终端，也可以让命令工具自动打开。
3. 后续命令和 Frida 任务复用返回的 `sessionId`。
4. 交互式命令需要更多内容时，再读取输出。
5. 分析结束后关闭终端。

工具覆盖设备和屏幕状态、PTY 生命周期、命令执行、快照，以及 Frida 状态、进程列表和脚本执行。MCP resources 提供只读的会话、Relay 和分析上下文。`mira_android_triage`、`mira_magisk_risk_review`、`mira_frida_triage` prompts 只提供起始环境，不预设检测结论。

分析 root、Magisk、Zygisk 或 LSPosed 时，应把已知环境告诉 AI。Mira 终端位于 Mira App 沙箱内，不是 adb shell 或 root shell。检测逻辑应放在临时生成或可复用脚本中，例如 [`tools/android/mira-proc-audit-sidechannel.sh`](../tools/android/mira-proc-audit-sidechannel.sh)，不要写成固定 MCP detector。

## Android Frida 任务

用户只需用自然语言描述分析目标。AI 负责生成 Frida 源码，并调用 `mira_frida_run_script`，参数包括：

- 用 `rpcMethod` 指定的任务导出方法；
- 用 `cleanupMethod` 指定的幂等清理方法；
- 任务需要的 RPC 参数。

当前 Android 路径保持一个 Frida runtime 和一个 runner Script，每次请求使用独立的局部函数作用域。清理方法在 `finally` 中执行，每份源码不会缓存成新的 Script。这样可以连续运行内容不同的一次性分析任务，不需要重启 Mira 或卸载 Gadget。

AI 生成的清理方法要恢复 Java implementation、移除 native listener、取消 timer，并释放任务持有的对象。Mira 无法自动撤销脚本遗留的任意全局状态或外部副作用。当前不提供长期 listener 管理。

Android single-runner 路径会把 `ArrayBuffer`、`DataView` 和 typed array 结果封装为：

```json
{ "type": "bytes", "encoding": "base64", "dataBase64": "..." }
```

需要时由 AI 解码或保存，用户不用处理 Base64。普通 JSON 保持不变，不支持的值和循环引用会明确报错，不会静默丢失。

尚未发布的兼容构建已在 Android 10 到 15 的 arm64、4 KB 页大小本地模拟器上通过有界回归。Android 16 暂不支持 Java hook，设置 Frida Java method implementation 可能导致 ART 崩溃。Android 16 定向测试中的 native Interceptor 和只读 Java 操作可以运行，但不满足 Java hook 和 original call 工作流。详情见 [Frida 兼容性记录](./notes/android-frida-gadget-compatibility-2026-09-05.md)。

## 限制

- MCP 连接已有 Relay，不负责启动 Relay，也不提供公网 MCP HTTP 服务。
- shell 命令超时后，该 PTY session 状态不确定。先检查输出，再关闭并新建 session。命令不会自动重放。
- watchdog 可以限制卡住的 Frida 客户端调用，但无法从致命 native 或 JNI 操作中恢复 App。
- Mira 只观察和操作已授权的 Mira 宿主 App 沙箱。
