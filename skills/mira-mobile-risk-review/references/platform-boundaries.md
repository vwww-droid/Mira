# Platform Boundaries(平台边界)

## 证据来源分级

1. OS-backed evidence(系统支撑证据): 系统公开 API, 当前进程 `/proc/self/*`, App sandbox(应用沙盒)可见文件。
2. Mira-projected evidence(Mira 投影证据): `/mira` 这类由 Mira 映射或模拟的视图。
3. Agent-derived evidence(智能体推导证据): Codex 根据上述证据归纳出的模式, 只能作为分析结论, 不能当作原始证据。

报告中必须标注证据来源。Mira 投影证据不能伪装成系统原生证据。

## iOS 边界

### `/mira`

`/mira` 是 Mira 通过 do_mount(挂载映射)提供的 app-view root(应用视角根目录)。它表达的是 Mira App 在 iOS sandbox(沙盒)内能看见和组织的文件视图, 不是 iOS 系统真实 `/` 根目录。

分析时可用它回答:

1. Mira 自己的 bundle(应用包), Documents(文档目录), Library(库目录), tmp(临时目录)或测试产物是否符合预期。
2. App 自有目录或测试流程生成文件是否存在结构异常。
3. App 视角下的运行基线是否与声明环境一致。

遇到下面问题时可以提出怀疑或待验证点, 但必须写成边界不足, 不能伪装成系统级事实:

1. 系统全盘文件是否存在。
2. 其他 App 私有目录内容。
3. iOS 是否被越权访问。

### `/mira/proc`

`/mira/proc` 是 Mira 模拟的 process view(进程视图), 用来让 AI 用类 Unix(类 Unix 系统)习惯理解当前 App 进程, 线程, 内存段摘要或运行时状态。它不是 Linux procfs(Linux 进程文件系统), 也不是 iOS 内核原生 `/proc`。

写报告时使用这些措辞:

1. `Mira simulated process view shows ...`
2. `In Mira's app-scoped /mira/proc projection ...`
3. `This is app-scoped evidence, not global process enumeration.`

避免写成下面这种会把 Mira 投影视图伪装成系统事实的表述:

1. `iOS /proc shows ...`
2. `system process list proves ...`
3. `full device filesystem contains ...`

### UI upload(界面上传)

iOS UI upload 只代表 Mira App 自己的 key window(主窗口)画面, 不是 ReplayKit(屏幕录制框架), 也不是系统级屏幕采集。它适合证明 Mira 自身状态, Relay(中继)连接状态, 输入框内容和用户操作结果。

## Android 边界

Android 侧优先把 Mira 终端解释为第三方 App sandbox(第三方应用沙盒)内的真实 PTY(伪终端)。它不是 adb shell(Android 调试桥命令环境), 不是 root shell(最高权限命令环境)。

### 建议先看的目录和文件

1. `/proc/self/maps`: 当前进程内存映射。适合看加载库, 匿名映射, 可执行段和路径形态。
2. `/proc/self/smaps`: 当前进程内存映射详细统计。输出大, 默认只摘要关键段。
3. `/proc/self/status`: UID(用户标识), GID(用户组标识), capability(能力位), tracer(跟踪进程)等当前进程状态。
4. `/proc/self/cmdline`: 当前进程命令行。注意脱敏。
5. `/proc/self/environ`: 环境变量。默认只列 key(键名), 不输出 value(值)。
6. `/proc/self/mountinfo`: 当前 mount namespace(挂载命名空间)视图。适合看 App 沙盒, overlay(叠加挂载), tmpfs(临时文件系统)和可见系统分区。
7. `/proc/self/fd`: 当前进程打开文件描述符。只输出类型和脱敏路径摘要。
8. `/proc/self/task`: 当前进程线程列表。可看线程名和数量摘要。
9. `/proc/self/attr/current`: SELinux(Security-Enhanced Linux, 安全增强 Linux)上下文。
10. `/proc/self/cgroup`: cgroup(控制组)归属, 可辅助理解进程隔离和运行环境。

### Android maps 判断原则

`maps` 只证明当前 Mira 进程或 shell 进程加载了什么, 不证明全系统状态。判断风险时看组合异常:

1. 加载路径与声明 ABI(Application Binary Interface, 应用二进制接口)不一致。
2. 出现非预期注入库, hook 框架库或调试相关库。
3. 同一环境中 mountinfo, status, maps 和 App 上下文互相矛盾。
4. 与真实设备基线相比, 图形栈, libc(标准 C 库), linker(动态链接器), runtime(运行时)形态异常。

所有值得说出来的现象:

1. 单个可疑库名。
2. 单个匿名可执行映射。
3. 路径中出现 generic(通用)或 emulator(模拟器)字样。
4. 与真实设备基线不一致但暂时解释不了的路径, 权限, 映射或挂载。

报告中应脱敏完整私有路径, 只保留 basename(文件名), 路径类别和为什么看起来不对劲。
