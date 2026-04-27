# Termux Fork 备用路线

> 当前主线说明: Mira 现在优先采用 APK(安卓安装包) 直接打包 BusyBox(单文件工具集) 的轻量工具箱方案, 不接 Termux package repository(包仓库), 不维护 apt(包管理器) 软件源索引。本文只保留为后续需要完整类 Termux 用户空间时的备用研究记录。

## 结论

APK(安卓安装包) 改名很简单, 但完整类 Termux 用户空间要重编 bootstrap(启动用户空间) 和相关包。Mira 不能直接把官方 `com.termux` bootstrap zip(启动压缩包) 原样放到 `com.vwww.mira` 沙盒里长期使用。

当前建议的第一阶段 fork 策略是保留 Java package(Java 包名) `com.termux`, 只把 Android applicationId(安卓应用标识) 改成 `com.vwww.mira`。这样能避免把整棵 Java 源码目录和大量类名引用一起迁移。

## 两个不同问题

### 1. APK 改名

这部分主要改:

1. `termux-app/app/build.gradle` 里的 `applicationId` 和 `manifestPlaceholders.TERMUX_PACKAGE_NAME`。
2. `termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java` 里的 `TERMUX_PACKAGE_NAME`。
3. `app/src/main/res/values/strings.xml` 里的 entity(实体变量) 名称。
4. `termux-shared/src/main/res/values/strings.xml` 里的 package 和 prefix(路径前缀) entity。
5. `app/src/main/res/xml/shortcuts.xml` 里的 `targetPackage` 和 failsafe extra(故障安全参数) 名称。
6. 必要时改 launcher(启动器) 名称和图标。

第一阶段不要改 `namespace "com.termux"` 和源码目录。这里的 `namespace` 绑定了 `BuildConfig` 和相对 activity(活动组件) 类名解析, 盲目改成 `com.vwww.mira` 会比只改安装包名更容易炸。

因此生成脚本会把 `TermuxConstants.TERMUX_PACKAGE_NAME` 改成 `com.vwww.mira`, 同时把主 app 内部真实 class name(类名) 常量固定保留为 `com.termux.*`。这是 applicationId 和 Java package 拆分后的必要补丁。

这一步不难。

### 2. bootstrap 重编

这部分才是关键。Termux 的很多二进制和脚本会围绕 `$PREFIX` 编译或生成。默认路径是:

```text
/data/data/com.termux/files/usr
```

Mira 需要的是:

```text
/data/data/com.vwww.mira/files/usr
```

因此需要用 `termux-packages` 按 `com.vwww.mira` 重新构建 bootstrap 和后续包。

## 已加入的 submodule(代码子模块)

```text
third_party/termux-app
third_party/termux-packages
```

## 已加入的工具脚本

### 准备 termux-packages fork 工作区

```bash
./tools/termux/prepare-mira-termux-packages.sh
```

默认输出:

```text
.mira/forks/termux-packages-mira
```

然后构建 aarch64 bootstrap:

```bash
cd .mira/forks/termux-packages-mira
./scripts/run-docker.sh ./scripts/build-bootstraps.sh --architectures aarch64 -f &> build-mira-bootstrap-aarch64.log
```

预期产物:

```text
bootstrap-aarch64.zip
```

完整 fork APK 需要四个架构:

```bash
./scripts/run-docker.sh ./scripts/build-bootstraps.sh -f &> build-mira-bootstrap.log
```

### 准备 termux-app fork 工作区

```bash
./tools/termux/prepare-mira-termux-app.sh
```

默认输出:

```text
.mira/forks/termux-app-mira
```

它会把包名改为:

```text
com.vwww.mira
```

但它还需要配套放入按 `com.vwww.mira` 重编的 bootstrap zip。

复制 bootstrap zip 后使用:

```bash
cd .mira/forks/termux-app-mira
MIRA_USE_LOCAL_BOOTSTRAPS=1 ./gradlew :app:assembleDebug
```

`MIRA_USE_LOCAL_BOOTSTRAPS=1` 用来告诉生成的 fork app 构建脚本直接使用本地自定义 bootstrap, 不按官方 checksum(校验和) 删除并重新下载 `com.termux` 的 bootstrap。

在这个模式下, 如果某个 `bootstrap-*.zip` 缺失, 构建会直接失败, 避免混入官方 `com.termux` bootstrap。

## 当前 Mira 主线怎么用

当前 Mira 主线不是直接复制完整 `TermuxActivity`, 而是:

1. 复用 Termux `terminal-emulator` 的 PTY(伪终端) 能力。
2. 保留 Mira 自己的 Web Terminal(网页终端) 和 WebSocket(网页长连接协议) 服务。
3. 后续把自定义 bootstrap zip 解压到 Mira 的 `files/usr`。

这条路线比直接搬完整 Termux app 更可控。

## 下一步

1. 运行 `prepare-mira-termux-packages.sh` 生成构建工作区。
2. 用 Docker(容器运行环境) 构建 `bootstrap-aarch64.zip`。
3. 把 bootstrap zip 接入 Mira APK 的 assets(资源文件) 或 native(原生) 打包流程。
4. 替换当前最小 `usr/bin/sh` wrapper。
5. 让 Web Terminal 进入真正的 `files/usr/bin/bash` 或 `files/usr/bin/sh`。
