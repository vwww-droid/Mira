# TODO

## 当前状态

Mira 当前 v1.0.0 是研究原型和参考实现, 重点不是做完整企业风控平台, 而是证明关键想法可行: AI 可以通过 MCP(Model Context Protocol, 模型上下文协议)进入 Android(安卓系统) 和 iOS(苹果移动系统) 的 App 沙盒视角, 读取 Skill(技能), 观察所有可能不对劲的行为, 并沉淀成本地 Case(案例)。早期 Web Terminal(网页终端) 到 PTY(伪终端) 的本机原型只作为 bridge(连接层)调试入口保留。

## 接下来一周目标

本周目标不是演示完整企业链路, 而是把 Mira 作为 research prototype(研究原型)和 reference implementation(参考实现)讲清楚, 并完成最小证明。

一周结束时应能表达清楚:

1. Mira 当前 App 是 demo app(演示应用), 让使用者代入自己的业务 App 场景。
2. Mira 最终更自然的形态是 Android library(Android 库), iOS library(iOS 库)或 SDK(软件开发工具包)。
3. 企业自己的情报系统, 工单系统, 服务端策略, 代码仓库, 多模型 review(审查), CI/CD(持续集成和持续交付), 灰度发布和回滚由企业自己接入。
4. Mira 只实现其中最关键的一段: 端侧沙盒观察 + MCP 接入 + Skill 分析 + 本地 Case 沉淀。
5. 这个项目首先用于学习, 研究和方法沉淀。思路比实现更重要, 但实现要足够小而可运行。

## 已完成基线

1. Android APK(安卓安装包): 已支持 Relay URL(中继地址)连接, control WebSocket(控制长连接), 按需打开真实 PTY, 会话级 BusyBox(单文件工具集)释放和四 ABI(应用二进制接口)工具箱。
2. iOS App(苹果移动应用): 已支持 Relay 连接, PTY 会话, Mira App 自身 key window(主窗口)画面上传, CPU(中央处理器), 内存和网络速率指标, 浏览器侧快捷输入。
3. Relay Console(中继控制台): 已支持设备大厅, 三栏式工作台, 远程终端, App 画面预览, 状态展示和指标展示。
4. 公网启动: `./mira-web` 已支持自动启动 Relay 并接入 cpolar(国内内网穿透服务), 也支持 `MIRA_PUBLIC_URL` 复用外部公网地址。
5. 局域网启动: `./mira-local-web` 已支持浏览器使用 localhost(本机地址), 手机使用电脑局域网 IP(网络地址)。
6. CLI(Command Line Interface, 命令行接口): 已支持 `mira-cli devices`, `mira-cli run` 和 `mira-cli shell`。
7. MCP Server(模型上下文协议服务端): 已支持设备列表, 打开终端, 执行命令, 读取输出, 快照采集, 关闭会话, resources(资源)和 prompts(提示模板)。
8. Mobile Risk Skill(移动风险技能): 已新增 `skills/mira-mobile-risk-review`, 默认 observation-first(现象优先), 记录所有可能不对劲的行为, 不做风险等级压制。

## P0-必须完成

1. 收敛 README(项目说明文档)首屏定位, 明确 Mira 是研究原型和参考实现, 不是完整企业风控平台。
2. 写清楚 demo app(演示应用)和未来 library(库)或 SDK(软件开发工具包)形态的关系, 让使用者能代入自己的业务 App 场景。
3. 更新 MCP prompt(提示模板), 让 Codex 分析风险环境前先读取 `skills/mira-mobile-risk-review/SKILL.md`。
4. 增加 case 生成命令或脚本, 默认写入 `cases/<githubName>-<yyyymmdd>-<topic>/`, 不默认提交, 不默认公开。
5. 补充 iOS `/mira` 和 `/mira/proc` 文档, 明确它们是 App 视角投影和 Mira 模拟进程视图, 不是系统真实根目录或内核 procfs(进程文件系统)。
6. 补充 Android 当前进程观察指南, 首批聚焦 `/proc/self/maps`, `/proc/self/status`, `/proc/self/mountinfo`, `/proc/self/fd` 和 SELinux(Security-Enhanced Linux, 安全增强 Linux)上下文。
7. 补一份最小证明脚本, 只串起 Mira MCP, Skill 读取, Android `/proc/self/*` 或 iOS `/mira` 观察, 以及本地 case 输出, 不演示企业工单, PR(Pull Request, 代码合并请求), CI/CD 或灰度发布。
8. 将公开演示安全边界, 项目定位和企业接入设想沉淀为 README 小节和发布文章草稿。

## P1-探测强度提升

1. 基于《Android 风险环境检测》这类珍贵资料, 持续整理模拟器, 云手机, 改机, Hook(运行时劫持), 调试, 注入和系统接口异常的 risk observation(风险观察)条目。
2. 为每个风险观察补充系统原理, App 沙盒可见路径, 可能被 Hook 的层级和下一步交叉验证方式。
3. 将高层 API(Application Programming Interface, 应用程序编程接口)观察逐步补充到 native bridge(原生桥接层), syscall(系统调用)包装和更贴近运行时底层的观察点。
4. Android 侧探索签名校验后的 native probe(原生探针)组件, 包括带 inline assembly(内联汇编) 的自有 libc-compatible shared object(兼容 libc 语义的共享库), 用来交叉验证高层 API 返回值。
5. 封装更多系统提供给第三方 App 的公开接口, 例如 battery state(电池状态), charging power(充电功率), thermal state(温控状态), network path(网络路径), sensor availability(传感器可用性)和进程自身运行时状态。
6. 梳理哪些能力未来应该从 demo app 抽象到 Android library, iOS library 或 SDK, 先写边界和接口草案, 不急着做完整商业化封装。

## P2-产品化和自动化

1. 增加 case 浏览和导出能力, 让一次风险探索能直接形成可分享的脱敏报告。
2. 增加 Skill notes(技能笔记)提炼能力, 从 case 中提取可复用判断模式, 但不默认提交 PR(Pull Request, 代码合并请求)。
3. 增加真实设备, 模拟器和云手机的基线对比样例。
4. 增加任务编排和历史执行记录, 但默认仍保留人工确认边界。
5. 增加企业认证, HMAC(基于哈希的消息认证码), 证书或账号体系, 当前默认仍依赖自托管服务边界。

## 暂缓事项

1. 不维护 apt(包管理器)软件源。
2. 不默认动态下发未审查工具包。
3. 不做系统权限绕过。
4. 不把 UI upload(界面上传)描述为系统全屏采集。
5. 不默认自动提交 PR 或公开 case。
6. 不演示完整企业风控平台, 不内置企业工单, 服务端策略, 多模型 review, CI/CD, 灰度发布或回滚系统。
