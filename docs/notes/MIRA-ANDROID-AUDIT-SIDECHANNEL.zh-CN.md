# Mira Android audit 侧信道脚本写法沉淀

## 现象

1. 在 Mira 的 Android PTY(伪终端)里, 当前交互 shell(命令解释器)执行 `[ -d /proc/<pid> ]` 会触发 SELinux(安全增强 Linux) audit log(审计日志).
2. 当目标 PID(进程编号)属于 Magisk 相关域时, logcat(Android 日志读取工具)里会出现 `tcontext=u:r:magisk:s0` 这类目标上下文.
3. 例如命中日志形态是 `avc: denied { getattr } for path="/proc/1030" ... tcontext=u:r:magisk:s0 ... app=com.vwww.mira`.

## 风险来源

1. 这个检测依赖 audit log 侧信道, 不是直接读取进程列表.
2. audit log 有 rate limit(审计限流), 大窗口连续触发会导致后半段 PID 的关键日志被吞掉.
3. 外部命令会产生额外 audit 噪声, 例如每个 PID 执行一次 `/system/bin/stat` 会比 shell builtin(内建命令)更吵.
4. `sh script.sh` 和当前 Mira PTY 交互 shell 的行为可能不同, 不要把脚本执行方式当成无关变量.

## 坏味道

1. 用 `500` 甚至更大的 chunk(分块)线性扫 1-10000, 很容易误判为没有命中.
2. 每个 PID fork(派生进程)一次 `stat` 或 `getxattr` 外部命令, 会制造大量无关 `execute` 或 `read` audit 日志.
3. 不清空 logcat 就判断命中, 可能把旧日志当成新命中.
4. 只加长 sleep(等待时间)不缩小窗口, 通常解决不了 audit 限流.
5. 看到 no hit(无命中)就认为环境不存在 Magisk, 是错误结论.

## 关键线索

1. 单点 `[ -d /proc/1030 ]` 能稳定触发 `tcontext=u:r:magisk:s0`.
2. `1000-1049` 这种窗口可能只吐出前几个 `kernel` 目标, 后面的 `1030` 被限流吞掉.
3. `1030-1079` 这种窗口能稳定吐出 `1030`, `1031`, `1032`, `1051` 的 Magisk 上下文.
4. `50` 一组可以用, 但目标落在窗口前半段更稳.

## 思路与收敛路径

1. Mira 只提供通用 shell 和 logcat 能力, 不把 Magisk 检测器固化进产品逻辑.
2. AI 生成 shell 片段, 在 Mira PTY 当前 shell 中执行触发和日志判断.
3. 触发动作使用 shell builtin `[ -d "/proc/$pid" ]`, 目标是触发 `{ getattr }` audit.
4. 每个窗口前执行 `/system/bin/logcat -c -b all`, 只保留当前窗口产生的新日志.
5. 每个窗口后执行 `/system/bin/logcat -d -b all -t <N> | grep -E <MATCH>`, 命中后立即退出.

## 问题本质

1. 这个方案的本质不是读取 `/proc` 内容, 而是利用 SELinux 对跨域 `/proc/<pid>` 元数据访问产生的 audit 记录.
2. 稳定性取决于触发噪声和 audit 限流, 不是单纯取决于等待时间.
3. 可靠脚本要控制每个判断窗口里的触发数量, 并让目标 PID 尽量不要落在窗口太后面.

## 修复与验证

1. 默认使用 `CHUNK=50`, `WAIT_SEC=1`, `COOLDOWN_SEC=2`.
2. 如果全量扫描没有命中, 优先改用 `CHUNK=10` 或 `STEP=25` 的重叠滑动窗口, 不要只加等待时间.
3. 实测 `1000-1100` 范围, `CHUNK=50` 可以在 `1050-1099` 窗口命中 `/proc/1051` 的 `tcontext=u:r:magisk:s0`.
4. 实测 `1030-1079` 范围, `WAIT_SEC=5` 可以命中 `/proc/1030`, `/proc/1031`, `/proc/1032`, `/proc/1051`.

## 可复用判断模式

1. 如果单点能命中而批量不命中, 先怀疑 audit 限流和窗口噪声.
2. 如果 `sh script.sh` 不命中而交互命令命中, 先怀疑执行进程和 PTY 语义差异.
3. 如果增加等待仍不命中, 先缩小 chunk 或使用重叠 STEP, 而不是继续拉长 sleep.
4. 如果要做全量 1-10000 扫描, 推荐两阶段策略: 先 `CHUNK=50 STEP=50` 快扫, 再对可疑或未覆盖范围用 `CHUNK=10 STEP=10` 慢扫.

## 仓库脚本

通用 shell 片段位于:

```text
tools/android/mira-proc-audit-sidechannel.sh
```

推荐通过 Mira MCP 的 `mira_run_command` 把文件内容粘贴到当前 PTY 中执行, 或先推送到设备后用 `.` source(在当前 shell 中加载执行). 不推荐 `sh <file>`.
