<p align="right">
  English | <a href="./MCP.zh-CN.md">简体中文</a>
</p>

# Mira MCP Server

Mira MCP lets AI clients such as Codex and Claude operate a connected Mira device through Relay. Relay owns device transport and sessions. The MCP server exposes them as stdio JSON-RPC tools.

```text
AI client -> Mira MCP -> Relay -> Android or iOS Mira -> PTY and runtime actions
```

## Start

Start Relay first:

```bash
python3 -m mira.relay.server \
  --host 0.0.0.0 \
  --port 8765 \
  --advertise-url http://<your-lan-ip>:8765
```

Then configure the client to launch:

```bash
python3 -m mira.mcp.server --relay http://127.0.0.1:8765
```

You can set `MIRA_RELAY_URL` instead of passing `--relay`. Run from the repository root with `PYTHONPATH` pointing to that root.

Claude Desktop commonly reads `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "mira": {
      "command": "<path-to-python>",
      "args": ["-m", "mira.mcp.server", "--relay", "http://127.0.0.1:8765"],
      "cwd": "<path-to-mira-repo>",
      "env": { "PYTHONPATH": "<path-to-mira-repo>" }
    }
  }
}
```

Codex reads `~/.codex/config.toml`:

```toml
[mcp_servers.mira]
command = "<path-to-python>"
args = ["-m", "mira.mcp.server", "--relay", "http://127.0.0.1:8765"]
cwd = "<path-to-mira-repo>"
env = { PYTHONPATH = "<path-to-mira-repo>" }
default_tools_approval_mode = "approve"
```

Restart or reconnect the MCP client after changing MCP Python code or configuration. The running MCP process does not reload source files.

## Typical analysis flow

1. Use `mira_list_devices` and select the exact `installId`.
2. Use `mira_open_terminal` or let a command tool open one.
3. Reuse the returned `sessionId` for commands and Frida tasks.
4. Read additional output when an interactive command needs it.
5. Close the terminal when the analysis is done.

The tools cover device and screen state, PTY lifecycle, command execution, snapshots, and Frida status, process listing, and scripts. MCP resources expose read-only session, Relay, and analysis context. The `mira_android_triage`, `mira_magisk_risk_review`, and `mira_frida_triage` prompts provide starting context without hard-coding a detection result.

For root, Magisk, Zygisk, or LSPosed investigations, tell the AI what environment is expected. The Mira terminal runs inside the Mira app sandbox, not an adb or root shell. Detection logic belongs in generated or reusable scripts, such as [`tools/android/mira-proc-audit-sidechannel.sh`](../tools/android/mira-proc-audit-sidechannel.sh), rather than a fixed MCP detector.

## Android Frida tasks

Describe the analysis goal in ordinary language. The AI writes the Frida source and calls `mira_frida_run_script` with:

- an exported task method named by `rpcMethod`;
- an idempotent cleanup method named by `cleanupMethod`;
- any RPC arguments required by the task.

The current Android path keeps one Frida runtime and one runner Script alive, but gives each request its own local function scope. It runs cleanup in `finally` and does not cache each source as another Script. This supports repeated, changing one-shot analysis tasks without restarting Mira or unloading Gadget.

The AI must make cleanup restore Java implementations, detach native listeners, cancel timers, and release task-owned objects. Mira cannot undo arbitrary global state or external side effects left by a script. Long-running listener management is not available yet.

On the Android single-runner path, `ArrayBuffer`, `DataView`, and typed-array results are returned as:

```json
{ "type": "bytes", "encoding": "base64", "dataBase64": "..." }
```

The AI decodes or saves this data when needed. Ordinary JSON is unchanged, and unsupported or circular values fail explicitly instead of being silently dropped.

The unreleased compatibility build passed bounded arm64, 4 KB emulator checks on Android 10 through 15. Android 16 Java hooks remain unsupported because assigning a Frida Java method implementation can crash ART. Native Interceptor and read-only Java operations passed in the targeted Android 16 diagnosis, but that does not satisfy Java hook and original-call workflows. See the [Frida compatibility notes](./notes/android-frida-gadget-compatibility-2026-09-05.md).

## Limits

- MCP connects to an existing Relay. It does not start Relay or expose a public MCP HTTP service.
- A timed-out shell command leaves that PTY session uncertain. Inspect its output, close it, and open a new session. Commands are not replayed.
- A watchdog bounds stuck Frida client calls, but it cannot recover the app from a fatal native or JNI operation.
- Mira only observes and operates inside the authorized Mira host app sandbox.
