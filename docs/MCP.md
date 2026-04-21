# Mira MCP Server

## 目标

MCP(Model Context Protocol, 模型上下文协议) server(服务端) 让外部 AI client(智能客户端), 例如 Codex, 通过标准 JSON-RPC(JSON 远程过程调用) 工具接口操作 Mira Relay(中继服务端)。

它不替代 Relay Server。它是 AI 到 Relay 的适配层:

```text
Codex
  -> MCP stdio(JSON-RPC)
  -> mira.mcp.server
  -> Mira Relay HTTP API + WebSocket
  -> Android Mira Discovery Service
  -> Android PTY
```

## 启动顺序

先启动 Relay Server:

```bash
python3 -m mira.relay.server \
  --host 0.0.0.0 \
  --port 8765 \
  --discovery-port 8766 \
  --advertise-url http://<电脑局域网IP>:8765
```

再让 MCP client 启动 MCP Server:

```bash
python3 -m mira.mcp.server \
  --relay http://127.0.0.1:8765 \
  --broadcast-target 255.255.255.255
```

也可以用环境变量:

```bash
MIRA_RELAY_URL=http://127.0.0.1:8765 \
MIRA_BROADCAST_TARGET=255.255.255.255 \
python3 -m mira.mcp.server
```

## Codex CLI 配置示例

如果 Codex CLI(命令行接口) 使用 TOML(配置文件格式) 声明 MCP server, 可以按下面思路配置:

```toml
[mcp_servers.mira]
command = "python3"
args = ["-m", "mira.mcp.server", "--relay", "http://127.0.0.1:8765"]
cwd = "/Users/vw2x/Projects/Reverses/Mira"
default_tools_approval_mode = "approve"

[mcp_servers.mira.tools.mira_discover_devices]
approval_mode = "approve"

[mcp_servers.mira.tools.mira_list_devices]
approval_mode = "approve"

[mcp_servers.mira.tools.mira_open_terminal]
approval_mode = "approve"

[mcp_servers.mira.tools.mira_run_command]
approval_mode = "approve"

[mcp_servers.mira.tools.mira_collect_snapshot]
approval_mode = "approve"

[mcp_servers.mira.tools.mira_send_input]
approval_mode = "approve"

[mcp_servers.mira.tools.mira_read_output]
approval_mode = "approve"

[mcp_servers.mira.tools.mira_close_terminal]
approval_mode = "approve"
```

也可以不写入全局配置, 直接在单次命令里临时注入配置:

```bash
codex -a never exec --json \
  -C /Users/vw2x/Projects/Reverses/Mira \
  -c 'mcp_servers.mira.command="python3"' \
  -c 'mcp_servers.mira.args=["-m","mira.mcp.server","--relay","http://127.0.0.1:8765","--broadcast-target","<设备IP或广播地址>"]' \
  -c 'mcp_servers.mira.cwd="/Users/vw2x/Projects/Reverses/Mira"' \
  -c 'mcp_servers.mira.default_tools_approval_mode="approve"' \
  -c 'mcp_servers.mira.tools.mira_discover_devices.approval_mode="approve"' \
  -c 'mcp_servers.mira.tools.mira_list_devices.approval_mode="approve"' \
  -c 'mcp_servers.mira.tools.mira_open_terminal.approval_mode="approve"' \
  -c 'mcp_servers.mira.tools.mira_run_command.approval_mode="approve"' \
  -c 'mcp_servers.mira.tools.mira_collect_snapshot.approval_mode="approve"' \
  -c 'mcp_servers.mira.tools.mira_send_input.approval_mode="approve"' \
  -c 'mcp_servers.mira.tools.mira_read_output.approval_mode="approve"' \
  -c 'mcp_servers.mira.tools.mira_close_terminal.approval_mode="approve"' \
  '请使用 mira MCP 工具发现设备, 采集一次 Android 终端快照, 然后汇总结果。'
```

可以用下面命令确认 Codex 已加载 Mira MCP server:

```bash
codex mcp list \
  -c 'mcp_servers.mira.command="python3"' \
  -c 'mcp_servers.mira.args=["-m","mira.mcp.server","--relay","http://127.0.0.1:8765"]' \
  -c 'mcp_servers.mira.cwd="/Users/vw2x/Projects/Reverses/Mira"'
```

如果只配置 `default_tools_approval_mode`, 某些 Codex CLI 版本可能仍会在非交互 `exec` 模式里把 MCP tool call(工具调用) 标记为 `user cancelled MCP tool call`。给每个 Mira tool 显式设置 `approval_mode = "approve"` 后, `codex exec` 可以直接完成 discovery(发现), open terminal(打开终端), snapshot(快照采集) 和 close session(关闭会话)。

MCP stdio 消息使用单行 JSON-RPC(JSON 远程过程调用) 消息, stdout(标准输出) 只写协议响应, 日志应写 stderr(标准错误输出)。

## 暴露的 tools

| Tool | 用途 |
| --- | --- |
| `mira_discover_devices` | 通过 Relay 扫描局域网设备 |
| `mira_list_devices` | 读取 Relay 已知设备列表 |
| `mira_open_terminal` | 打开 Android PTY 会话并 attach(附着) |
| `mira_run_command` | 在持久 PTY 中执行命令并等待结束标记 |
| `mira_collect_snapshot` | 执行第一轮 Android 分析命令集 |
| `mira_send_input` | 向交互式 PTY 发送原始输入 |
| `mira_read_output` | 读取 MCP 侧缓存的终端输出 |
| `mira_close_terminal` | 关闭 PTY 会话并触发设备侧清理 |

## 暴露的 resources

| Resource | 用途 |
| --- | --- |
| `mira://analysis-guide` | Android 终端分析指南 |
| `mira://magisk-app-shell-context` | Magisk 手机第三方 app shell 环境上下文 |
| `mira://sessions` | 当前 MCP 进程内活跃 session(会话) |
| `mira://relay` | 当前 Relay URL(统一资源定位符) 和 broadcast target(广播目标) |

## 暴露的 prompt

| Prompt | 用途 |
| --- | --- |
| `mira_android_triage` | 指导 AI 采集设备身份, shell 环境, toolbox, mount, process 和 memory 证据 |
| `mira_magisk_risk_review` | 只告诉 Codex 当前是 Magisk 手机, 第三方 app shell 和 BusyBox 可用, 让它自己发现风险点 |

## 推荐 AI 分析流

```text
mira_discover_devices
  -> mira_collect_snapshot
  -> 按输出补充 mira_run_command
  -> 汇总设备身份, shell 环境, mount, toolbox 和风险点
  -> mira_close_terminal
```

## Magisk 环境上下文

当目标设备是已安装 Magisk 的手机, 且 Codex 通过 Mira 进入的是第三方 app shell 而不是 root shell 时, 推荐使用 `mira_magisk_risk_review` prompt。

该 prompt 不写固定扫描剧本, 只告诉 Codex:

1. 这是一台安装了 Magisk(安卓 root 管理框架) 的 Android 手机。
2. 它现在位于第三方 app shell(应用沙箱命令环境) 权限里, 不是 adb shell(调试桥命令环境), 也不是 root shell(最高权限命令环境)。
3. 当前终端是真实 PTY(伪终端)。
4. 会话里可以使用 BusyBox(单文件工具集)。
5. 风险发现路径由 Codex 自己决定。

示例:

```bash
codex -a never exec \
  -C /Users/vw2x/Projects/Reverses/Mira \
  -c 'mcp_servers.mira.command="python3"' \
  -c 'mcp_servers.mira.args=["-m","mira.mcp.server","--relay","http://127.0.0.1:8765","--broadcast-target","<设备IP或广播地址>"]' \
  -c 'mcp_servers.mira.cwd="/Users/vw2x/Projects/Reverses/Mira"' \
  -c 'mcp_servers.mira.default_tools_approval_mode="approve"' \
  -c 'mcp_servers.mira.tools.mira_discover_devices.approval_mode="approve"' \
  -c 'mcp_servers.mira.tools.mira_list_devices.approval_mode="approve"' \
  -c 'mcp_servers.mira.tools.mira_open_terminal.approval_mode="approve"' \
  -c 'mcp_servers.mira.tools.mira_run_command.approval_mode="approve"' \
  -c 'mcp_servers.mira.tools.mira_collect_snapshot.approval_mode="approve"' \
  -c 'mcp_servers.mira.tools.mira_send_input.approval_mode="approve"' \
  -c 'mcp_servers.mira.tools.mira_read_output.approval_mode="approve"' \
  -c 'mcp_servers.mira.tools.mira_close_terminal.approval_mode="approve"' \
  '这是一个安装了 Magisk 的手机。你现在位于第三方 app 权限中的 shell, 可以使用 busybox。请自己判断如何分析, 找到风险点, 然后告诉我该怎么做。'
```

## 设计边界

1. MCP Server 只连接已有 Relay Server, 不负责启动 Relay Server。
2. MCP Server 使用 stdio transport(标准输入输出传输), 不直接暴露公网 HTTP 服务。
3. 每个 MCP 进程在内存里维护自己的 browser attach 和输出缓存。
4. `mira_run_command` 面向短命令和诊断命令, 长时间交互用 `mira_send_input` 和 `mira_read_output`。
5. `mira_collect_snapshot` 优先使用 `/system/bin/...` 采集 Android 系统信息, 避免临时 toolbox applet(工具别名) 遮蔽系统命令。
6. `mira_magisk_risk_review` 只提供环境上下文, 不内置固定扫描步骤。
7. `mira_close_terminal` 可以关闭当前 MCP 进程创建的 session, 也可以请求 Relay 关闭已知 sessionId, 用于清理上一个 MCP 进程留下的会话。
8. 本阶段不做账号体系, TLS(传输层安全协议), 多租户隔离或 AI 自动决策策略。
