# Mira Toolbox

## 目标

Mira Toolbox(工具箱) 用来替代 Termux package repository(包仓库) 的第一阶段方案。

本阶段不接 apt(包管理器), 不维护软件源索引, 也不从 Termux 包仓库安装工具。APK(安卓安装包) 直接内置 BusyBox(单文件工具集), 每次远程 terminal session(终端会话) 打开时释放到应用 cache(缓存目录) 的会话目录, 会话关闭后删除。

## 当前打包内容

当前只内置 arm64-v8a(64 位 ARM 应用二进制接口) 的 BusyBox:

```text
android/app/src/main/assets/toolbox/busybox/arm64-v8a/busybox
```

当前二进制大小约 812 KiB, APK 压缩后增量更小。

## 构建来源

BusyBox 版本:

```text
1.37.0
```

来源:

```text
https://busybox.net/downloads/busybox-1.37.0.tar.bz2
```

复现构建:

```bash
tools/toolbox/build-busybox-android.sh
```

当前资产 SHA256:

```text
c9370fa9f68484f26d7930496c40f811e5e98395c64ec6f99e4d0dc5dc2aceab
```

## 会话释放流程

```text
Open Terminal
  -> MiraRelayClient
  -> MiraToolbox.prepare(sessionId)
  -> /data/user/0/com.vwww.mira/cache/mira-sessions/<sessionId>/bin/busybox
  -> 创建常用 applet(命令入口) symlink(符号链接)
  -> MiraPtyFactory 把 session bin 放到 PATH 前面
  -> 创建 PTY(伪终端)
  -> session.close 后删除 mira-sessions/<sessionId>
```

## 运行时环境变量

远程 PTY 会新增:

```text
MIRA_TOOLBOX_BIN=/data/user/0/com.vwww.mira/cache/mira-sessions/<sessionId>/bin
MIRA_BUSYBOX=/data/user/0/com.vwww.mira/cache/mira-sessions/<sessionId>/bin/busybox
MIRA_PATH_PREFIX=$MIRA_TOOLBOX_BIN
PATH=$MIRA_TOOLBOX_BIN:$PREFIX/bin:/system/bin:/system/xbin
```

## 验收命令

```sh
command -v busybox
busybox echo busybox-ok
command -v ls
ls /proc/self | head -3
```

预期 `busybox` 和 `ls` 都来自 `cache/mira-sessions/<sessionId>/bin`。

## 当前边界

1. 只支持 arm64-v8a, 其他 ABI(应用二进制接口) 后续按需补齐。
2. 只在远程 Relay(中继) session 中释放工具箱, Local Terminal(本地终端) 暂不接入。
3. 会话目录是临时工具副本, 会话关闭后删除。
4. APK 内的 BusyBox 资产不会删除, 这是直接打包方案的基础。
5. BusyBox 使用 GPL-2.0 许可证, 分发时需要保留来源和许可证说明。
