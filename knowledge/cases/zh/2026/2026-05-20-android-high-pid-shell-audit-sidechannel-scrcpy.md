# Android 高 PID shell proc audit 侧信道提示 scrcpy 投屏

## 1. 检测对象

可能的 Android `scrcpy` 投屏行为. 从第三方 Mira App 沙箱里通过高 PID `/proc/<pid>` audit log(日志审计记录)观察 `u:r:shell:s0` 目标.

## 2. 初始怀疑

设备已经处于投屏状态. 从 adb(Android Debug Bridge, Android 调试桥) shell 看, 进程表里出现了高 PID 的 `shell` 进程, 包括 `sh -> app_process -> app_process` 链路, 形态符合宿主机通过 adb 启动投屏或自动化服务的模式.

观察: 可疑进程组不是普通安装 App 进程, 而是 Android `shell` 用户和 `u:r:shell:s0` SELinux(安全增强 Linux)上下文.

解释: App 沙箱不能直接检查这些进程细节, 但触碰 `/proc/<pid>` 仍可能触发 audit 记录并泄露目标 SELinux 上下文.

仍需验证: 同一高 PID shell 模式是否能唯一指向 `scrcpy`, 还是只能说明 adb 来源的 shell 自动化.

## 3. 候选主题

`android-proc-audit-sidechannel-adb-shell-automation-detection`

该候选主题关注第三方 App 沙箱通过 `/proc` audit 侧信道暴露高 PID adb shell 自动化进程. `scrcpy` 是本 case 中的一个具体疑似来源.

## 4. 已确认主题

未确认. 当前只是 Pixel 4 在活跃投屏会话中的单个 case. 需要更多正负样本后再升级为 topic.

## 5. 异常味道

1. 疑似投屏进程是高 PID 且属于 `shell`, 不是普通 App 包名进程.
2. 进程表显示 `sh` 拉起 `app_process`, 这是 Android 宿主侧工具通过 adb shell 启动 Java 代码的常见形态.
3. 从 App 沙箱看不到直接进程细节, 但 audit 记录仍泄露 `tcontext=u:r:shell:s0`.
4. 高 PID `shell` 上下文也可能来自普通 adb 会话, 所以该信号可疑但不能单独定性.
5. 大窗口扫描容易制造 audit 噪声, 并掩盖具体高 PID shell 目标.

噪声或误导:

1. `tcontext=u:r:shell:s0` 单独不能证明就是 `scrcpy`.
2. 低 PID shell 进程如持久 adb helper 可能是正常现象.
3. 高 PID kernel worker(内核工作线程)很常见, 不应当作投屏证据.
4. Mira toolbox(工具箱)执行会增加无关 audit 记录.

## 6. 关键线索

1. 投屏活跃时, adb shell 进程列表显示高 PID `shell` 条目:

```text
u:r:shell:s0 shell 27864 1747 ... S sh
u:r:shell:s0 shell 27866 27864 ... S app_process
u:r:shell:s0 shell 27885 27866 ... S app_process
```

2. Mira App 沙箱访问高 PID `/proc/27864` 后, logcat(日志缓冲)中出现 `tcontext=u:r:shell:s0` 的 audit denial(拒绝记录):

```text
avc: denied { getattr } for comm="sh" path="/proc/27864" ... scontext=u:r:untrusted_app_27:s0:... tcontext=u:r:shell:s0 ... app=com.vwww.mira
```

3. 另一次定点检查 `/proc/11814` 也产生了 `tcontext=u:r:shell:s0`, 证明高 PID shell 上下文可以通过该侧信道观测.
4. 信号来自 Mira App 沙箱, 而不是只依赖特权更高的 adb shell 进程表.

## 7. 验证动作

1. 确认物理设备已连接并处于投屏状态.
2. 运行 adb shell `ps -AZ`, 搜索 `shell`, `app_process` 和投屏相关进程形态.
3. 识别到 `u:r:shell:s0` 下的高 PID `sh -> app_process -> app_process` 链路.
4. 在 Mira MCP(Mira Control Protocol, Mira 控制协议)中打开 App 沙箱 shell.
5. 每个 probe(探针)窗口前用 `/system/bin/logcat -c -b all` 清空 logcat.
6. 从 Mira shell 用 shell builtin(内建命令) `[ -d "/proc/$p" ]` 触碰候选 `/proc/<pid>` 目录.
7. 读取新鲜 logcat 窗口, 搜索高 PID `tcontext=u:r:shell:s0` 记录.

## 8. 脚本产物与执行模型

Case script artifact(案例脚本快照):

[2026-05-20-android-high-pid-shell-audit-sidechannel-scrcpy.sh](https://github.com/vwww-droid/Mira/blob/main/knowledge/cases/artifacts/2026/2026-05-20-android-high-pid-shell-audit-sidechannel-scrcpy.sh)

Maintained reusable copy(维护版可复用脚本):

[mira-high-pid-shell-audit-sidechannel.sh](https://github.com/vwww-droid/Mira/blob/main/tools/android/mira-high-pid-shell-audit-sidechannel.sh)

推荐执行方式:

1. 将脚本粘贴进 `mira_run_command`, 让当前 Mira PTY(Pseudo Terminal, 伪终端) shell 触碰 `/proc/<pid>`.
2. 或使用 `. /data/data/com.vwww.mira/cache/mira-high-pid-shell-audit-sidechannel.sh` source(在当前 shell 加载)执行.
3. 在证明等价前避免 `sh file`.
4. 如果 adb 侧能看到疑似高 PID shell 范围, 优先围绕该范围做小窗口扫描.

已验证默认参数:

```sh
START=10000
END=32000
CHUNK=50
STEP=50
WAIT_SEC=1
LOG_TAIL=1200
MATCH='path="/proc/[1-9][0-9][0-9][0-9][0-9]".*tcontext=u:r:shell:s0'
```

最小定点验证模型:

```sh
/system/bin/logcat -c -b all >/dev/null 2>&1
[ -d /proc/<suspected-high-pid-shell> ] >/dev/null 2>&1
sleep 1
/system/bin/logcat -d -b all -t 500 | grep 'tcontext=u:r:shell:s0'
```

## 9. 结果

该侧信道已确认可以让 Mira App 沙箱通过 `/proc` audit 记录观察高 PID `u:r:shell:s0` 目标.

支持的结论: 活跃投屏期间, 与 adb 驱动投屏相符的高 PID shell 进程可被 App 沙箱观测为 `tcontext=u:r:shell:s0` audit 目标.

不支持的结论: 本 case 不能证明每个高 PID `shell` 都是 `scrcpy`, 也不能证明 App 沙箱无需外部关联即可识别具体命令名.

## 10. 误判风险

1. 任何 adb shell 自动化都可能制造高 PID `u:r:shell:s0` 进程.
2. `scrcpy`, 测试 runner(运行器), shell 脚本, 安装流程和人工 adb 会话从该侧信道看可能相似.
3. audit 行暴露的是 SELinux 上下文和 PID 路径, 不是进程命令行.
4. 如果不清空 logcat, 旧日志可能造成 stale hit(陈旧命中).
5. PID 范围依赖设备状态, 不应硬编码.

## 11. 判断种子

1. 高 PID `u:r:shell:s0` 应视作 adb 自动化味道, 不是最终结论.
2. `u:r:shell:s0` 下的 `sh -> app_process -> app_process` 比单纯 shell 上下文更可疑.
3. App 侧侧信道负责检测 shell domain(安全域)目标存在, adb 侧关联负责解释可能工具身份.
4. 投屏检测应结合高 PID shell audit 命中和 screen capture(屏幕采集)或 display service(显示服务)信号后再叫 `scrcpy`.
5. 未独立确认命令身份前, 结论措辞应保持为可能投屏或 adb 自动化.

## 12. 后续检查

1. 在 Mira 保持运行时启动和停止 `scrcpy`, 对比前后高 PID shell audit 命中变化.
2. 对比普通 `adb shell sleep`, instrumentation test(插桩测试)和安装流程.
3. 寻找 media projection(媒体投影), display(显示)或 virtual display(虚拟显示)服务周边的额外 audit 侧信道.
4. 测试其他 Android 版本上 `/proc/<pid>/cmdline` 或 `/proc/<pid>/status` 是否产生更具体 audit 元数据.
5. 建立区分普通 adb 自动化和疑似投屏的评分规则.

## 13. 相关文章

暂无. 在获得 `scrcpy` 和非 `scrcpy` adb 自动化的受控启停基线前, 先作为 case 保存.
