# Repository Architecture(仓库架构)

Mira 当前按平台应用壳, 共享原生层, Relay 与 MCP 服务, 前端控制台, 第三方组件和文档分层.

## 顶层目录

```text
android/      Android App(安卓应用) 壳与打包配置
apps/console/ 浏览器控制台源码
docs/         当前文档入口, 以及 notes/archive 分类文档
ios/          iOS App(苹果移动应用) 壳与 Xcode project(Xcode 项目)
mira/         Python(脚本语言) Relay, MCP 和 CLI 代码
native/       Android 与 iOS 共享的 C 原生能力
skills/       面向 Agent(智能体) 的可复用分析 Skill
third_party/  上游子模块与第三方代码
tools/        构建, 准备与辅助脚本
web/          控制台静态资源与打包输出输入目录
```

## 分层规则

1. `android/` 与 `ios/` 只放各自平台壳, UI(用户界面) 与平台打包配置.
2. `native/` 放跨平台 PTY(伪终端), shell 和进程相关能力, 上层通过 `native/include` 暴露的稳定接口接入.
3. `mira/` 放 Relay, MCP 和 CLI 的服务端逻辑, 不混入平台 UI 代码.
4. `apps/console/` 与 `web/` 共同承载浏览器工作台, 前者偏源码, 后者偏运行时静态资源.
5. `third_party/` 只放上游代码和跟随上游的许可证文本, 不把 Mira 自身实现混进去.
6. `docs/` 根目录只保留当前有效文档, 历史过程文档转入 `docs/notes/` 或 `docs/archive/`.

## 当前启动入口

```bash
./mira-web        # 公网 Relay, 默认走 cpolar(内网穿透服务)
./mira-local-web  # 局域网 Relay, 浏览器走 localhost, 手机走局域网 IP
./mira-ios        # 构建并启动 iOS Simulator 中的 Mira App
./mira-cli shell  # 直接进入远程 PTY 会话
```

## 文档分层

1. `docs/README.md` 是文档入口与索引.
2. `docs/*.md` 代表当前仍有效的使用, 架构和协议文档.
3. `docs/notes/` 保留研究背景, 备用路线和阶段性计划.
4. `docs/archive/` 保留已经完成阶段的历史实现记录.
