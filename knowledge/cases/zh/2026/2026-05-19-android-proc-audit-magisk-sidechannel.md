# Android proc audit 侧信道检测 Magisk SELinux 上下文

## 1. 检测对象

Android app sandbox shell 中对 `/proc/<pid>` 元数据的访问行为, 作为 audit log 侧信道, 用于观察目标进程的 SELinux `tcontext`.

## 2. 初始怀疑

检测思路不是从第三方 app 里直接读取 Magisk 进程信息, 而是触碰 `/proc/<pid>` 触发 SELinux audit 记录, 再从 logcat 中搜索 `tcontext=u:r:magisk:s0`.

## 3. 候选主题

`android-proc-audit-sidechannel-root-detection`

该候选主题聚焦 Android 第三方 app 上下文中的 `/proc` access audit side-channel. 它不同于常规 root path check 或 direct process-list check.

## 4. 已确认主题

未确认. 当前仅作为单个 case 记录. 只有在后续积累更多相关 case 后, 才创建 [knowledge/topics/android-proc-audit-sidechannel-root-detection](https://github.com/vwww-droid/Mira/tree/main/knowledge/topics/android-proc-audit-sidechannel-root-detection/).

## 5. 异常味道

1. 单点 `[ -d /proc/1030 ]` 可以触发 `tcontext=u:r:magisk:s0`, 但大范围线性扫描可能漏掉.
2. `sh script.sh` 和当前 Mira PTY shell 对该信号并不等价.
3. 每个 PID fork 外部 `stat` 或 `getxattr` 会制造无关 audit noise.
4. 只增加 `sleep` 不能解决 audit rate limiting 导致的 PID 漏检.

## 6. 关键线索

1. `[ -d /proc/1030 ]` 产生了 `{ getattr }` denial, 且包含 `tcontext=u:r:magisk:s0`.
2. `1000-1049 wait=5` 只输出前几个 `kernel` target, 没有输出 `1030`, 说明问题不是 log latency.
3. `1030-1079 wait=5` 能稳定输出 `/proc/1030`, `/proc/1031`, `/proc/1032`, `/proc/1051` 的 Magisk contexts.
4. `CHUNK=50` 扫描 `1000-1100` 时, 可以在 `1050-1099` 窗口命中 `/proc/1051`.

## 7. 验证动作

1. 通过 Mira MCP 打开 Android PTY.
2. 在当前 shell 中运行 `[ -d /proc/1030 ]`, 再用 `/system/bin/logcat -d -b all` 搜索 `tcontext=u:r:magisk:s0`.
3. 对比 `sh script.sh`, `source script`, inline loop, 外部 `/system/bin/stat`, shell builtin 等触发方式.
4. 对比 `CHUNK=10`, `CHUNK=50`, `CHUNK=500` 和不同 wait duration.
5. 将可复用脚本落到 [mira-proc-audit-sidechannel.sh](https://github.com/vwww-droid/Mira/blob/main/tools/android/mira-proc-audit-sidechannel.sh), 并将 case artifact 放入 [knowledge/cases/artifacts/2026](https://github.com/vwww-droid/Mira/tree/main/knowledge/cases/artifacts/2026/).

## 8. 脚本产物与执行模型

Case script artifact:

[2026-05-19-android-proc-audit-magisk-sidechannel.sh](https://github.com/vwww-droid/Mira/blob/main/knowledge/cases/artifacts/2026/2026-05-19-android-proc-audit-magisk-sidechannel.sh)

Maintained reusable copy:

[mira-proc-audit-sidechannel.sh](https://github.com/vwww-droid/Mira/blob/main/tools/android/mira-proc-audit-sidechannel.sh)

推荐执行方式:

1. 将脚本内容粘贴进 `mira_run_command`, 让它在当前 Mira PTY 中执行.
2. 或写入设备 cache path 后, 用 `. /data/data/com.vwww.mira/cache/mira-proc-audit-sidechannel.sh` source 执行.
3. 不要用 `sh mira-proc-audit-sidechannel.sh`, 因为 child shell 可能改变信号行为.

已验证参数:

```sh
START=1
END=10000
CHUNK=50
STEP=50
WAIT_SEC=1
COOLDOWN_SEC=2
LOG_TAIL=1000
MATCH='tcontext=u:r:magisk:s0|tcontext=u:r:su:s0|tcontext=u:r:magiskd:s0'
```

不稳定时优先调整:

1. 将 `CHUNK=50` 降为 `CHUNK=10`.
2. 使用 overlapping windows, 例如 `CHUNK=50 STEP=25`.
3. 每个 window 前保留 `logcat -c -b all`.
4. 不要只增加 `sleep`, 因为核心失败模式通常是 audit rate limiting.

## 9. 脚本快照

已验证脚本快照作为独立 `.sh` 文件保存在 case artifacts 目录, 便于直接复现:

[2026-05-19-android-proc-audit-magisk-sidechannel.sh](https://github.com/vwww-droid/Mira/blob/main/knowledge/cases/artifacts/2026/2026-05-19-android-proc-audit-magisk-sidechannel.sh)

## 10. 结果

该 side-channel 在目标设备上确认成立. 代表性命中:

```text
avc: denied { getattr } for comm="sh" path="/proc/1030" dev="proc" ... tcontext=u:r:magisk:s0 ... app=com.vwww.mira
```

本 case 推荐参数是 `CHUNK=50`, `WAIT_SEC=1-3`, `COOLDOWN_SEC=2-3`, `LOG_TAIL=1000-2000`. 如果全量扫描漏检, 优先缩小 chunk 或使用 overlapping steps, 不要只增加 wait time.

## 11. 误判风险

1. 旧 logcat buffer 可能包含 stale hits, 所以每个 decision window 前应清空 logcat.
2. `tcontext=u:r:magisk:s0` 是强信号, 但支持的结论是 Magisk-related SELinux context 暴露, 不是完整 root capability assessment.
3. Android version, ROM, SELinux policy, Magisk configuration 和 logcat visibility 都会影响可观测性.
4. wide-window miss 不代表 Magisk 不存在, 可能只是 audit rate limiting 或 window noise.

## 12. 判断种子

1. 如果单点 audit side-channel probe 命中, 但 batch probing 漏检, 优先怀疑 rate limiting 和 window noise.
2. 对 Mira PTY scripts, current-shell execution 和 `sh file` 在被证明前不能视为等价.
3. 对 `/proc` scans, trigger window 应低于 audit noise threshold, 并避免 target PID 落在 window 太后面.
4. `logcat -c` 可以隔离 current-window evidence, 但不能替代 chunk control.

## 13. 后续检查

1. 在更多 Android versions 和 ROMs 上验证 `CHUNK=50` 与 `CHUNK=10` 的差异.
2. 验证 overlapping windows, 例如 `CHUNK=50 STEP=25`, 是否能减少 target late-in-window miss.
3. 检查非 Magisk root tools 是否暴露不同 `tcontext`, 例如 `u:r:su:s0` 或 vendor-specific domains.
4. 对比 enforcing 和 permissive modes 下的 audit visibility.
5. 判断是否已有足够 case 支撑 topic 和 article draft.

## 14. 相关文章

暂无. 当前仍是 case record. 后续 article 应基于本 case 和更多设备样本提炼 boundaries, false-positive risks 和 stable parameters.
