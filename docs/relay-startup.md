# Reproducible public Relay startup

This launcher requires a source checkout containing `tools/` and `apps/`. The wheel/sdist provide the Python MCP and Relay commands, not this Web supervisor workflow. Download the repository source archive or clone it, then run from its root:

```bash
./mira-web start   # also the default for ./mira-web
./mira-web status  # live HTTP + WebSocket verification
./mira-web stop
```

`start` builds the console and launches a background supervisor. Repeated or concurrent starts reuse the same supervisor. Closing the terminal does not stop it. Stop requests identify the current supervisor generation instead of sending signals to a PID from a stale status file. A successful exit (`0`) means the local and public `/api/health` endpoints identify the same Relay process and the public WebSocket handshake succeeds. An HTML tunnel error page does not count as healthy.

Startup waits up to 40 seconds in the caller. Exit `2` means startup is still proceeding in the background; use `status`. Exit `1` indicates failure/unhealthy state. `status` includes the current `publicUrl`, instance ID, supervisor PID and last failure. It reports `ready` only after checking reachability again.

The default tunnel provider is cpolar. Install and authenticate cpolar once before starting. The launcher reads both `Forwarding` and structured `Tunnel established at` log formats. Tunnel logs use INFO instead of DEBUG. Credentials are not printed by the launcher.

## Recovery and limits

- Startup retries three times by default, with backoff. Each attempt has a 180-second deadline including build and tunnel discovery.
- Local Relay process exits or repeated local health failures trigger a bounded TERM/KILL cleanup and Relay restart while retaining the tunnel and its public URL. The shell caps rapid Relay restarts at three per minute.
- The supervisor probes local/public HTTP health every five seconds. Three consecutive failures after readiness, or a launcher/tunnel exit, trigger a full restart. A healthy minute resets the restart budget.
- A full tunnel restart may change a free/random cpolar or Cloudflare hostname. This cannot guarantee that an offline phone learns the new address. Use a reserved domain or a separately managed fixed tunnel for durable unattended phone access.
- Public WebSocket connectivity is checked on readiness and by `status`. Phone online state is separate: a healthy Relay cannot restart a phone App killed by Android or by an in-process native probe.
- An occupied unmanaged port is reported, never automatically killed. Stop its original launcher before starting the supervisor.
- No login/boot service is installed. Run `start` once after reboot. `./mira-web foreground` retains the foreground launcher for manual debugging.

## Configuration

```bash
# Existing fixed tunnel; its client must already be running.
MIRA_PUBLIC_URL=https://your-reserved-host.example ./mira-web start

# Alternative provider.
MIRA_TUNNEL_PROVIDER=cloudflare ./mira-web start

# Reuse an already-built console.
MIRA_SKIP_CONSOLE_BUILD=1 ./mira-web start
```

Additional variables: `MIRA_RELAY_PORT` (8765), `MIRA_RELAY_HOST` (0.0.0.0), `MIRA_WEB_RETRIES` (3), `MIRA_WEB_STARTUP_TIMEOUT` (180), `CPOLAR_BIN`, `CLOUDFLARED_BIN`, and `PYTHON_BIN`. Change configuration by stopping the managed instance and starting it with the new environment. The active supervisor retains its launch environment across recovery attempts.

Runtime state and logs are Git-ignored under `.mira/runtime/web-<port>/` and `.mira/runtime/tunnel-logs/`. Per-attempt logs are retained for diagnosis. Remove old logs when the managed service is stopped; automatic log retention is not implemented.

## MCP diagnostics

The MCP server continues to use `http://127.0.0.1:8765`. Restart or reconnect the MCP client after updating MCP Python code. For Frida tasks, the user describes the goal and the AI supplies the task export, `rpcMethod`, and idempotent `cleanupMethod`. Mira runs each task in the shared single runner, calls cleanup in `finally`, and returns binary values as `{ "type": "bytes", "encoding": "base64", "dataBase64": "..." }`. Long-running listener management is not available.

The unreleased compatibility path passed bounded Android 10 through 15 checks on arm64, 4 KB AVDs. Android 16 Java hooks remain unsupported because installing a method implementation can crash ART. See [`docs/MCP.md`](./MCP.md) and the [Frida compatibility notes](./notes/android-frida-gadget-compatibility-2026-09-05.md).

A watchdog cannot undo a fatal native/JNI operation inside the instrumented App. Run unstable native probes in a disposable child process under the same App UID and validate returned handles before using them. Keep ordinary sandbox denials as unavailable evidence; do not grant extra development permissions to simulate a normal third-party App.

An ordinary shell command timeout leaves its session marked uncertain. Read its output for diagnosis, then close that terminal and open a new one. Further commands are rejected on that session so a still-running command cannot consume the next request. Other sessions and devices remain available.

Regression checks:

```bash
python3 -m unittest discover -s tests -p test_mcp_stability.py -v
python3 -m unittest discover -s tests -p test_web_supervisor.py -v
```
