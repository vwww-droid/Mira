# Mira Toolbox

## 目标

Mira Toolbox(工具箱) 用来替代 Termux package repository(包仓库) 的第一阶段方案。

本阶段不接 apt(包管理器), 不维护软件源索引, 也不从 Termux 包仓库安装工具。APK(安卓安装包) 直接内置 BusyBox(单文件工具集), 每次远程 terminal session(终端会话) 打开时释放到应用 cache(缓存目录) 的会话目录, 会话关闭后删除。

## 当前打包内容

当前内置四个 Android ABI(安卓应用二进制接口) 的 BusyBox:

| ABI | 资产路径 | 大小 |
| --- | --- | --- |
| arm64-v8a | `android/app/src/main/assets/toolbox/busybox/arm64-v8a/busybox` | 809128 bytes |
| armeabi-v7a | `android/app/src/main/assets/toolbox/busybox/armeabi-v7a/busybox` | 855108 bytes |
| x86 | `android/app/src/main/assets/toolbox/busybox/x86/busybox` | 841232 bytes |
| x86_64 | `android/app/src/main/assets/toolbox/busybox/x86_64/busybox` | 803096 bytes |

运行时按 `Build.SUPPORTED_ABIS` 的系统优先级选择第一个可用资产。

## 机器可读清单

总清单位于:

```text
android/app/src/main/assets/toolbox/manifest.json
```

每个 ABI 目录下还保留对应 `SOURCE.txt`, 记录版本, 来源, 构建脚本和 SHA256(安全哈希摘要)。

## 构建来源

BusyBox 版本:

```text
1.37.0
```

来源:

```text
https://busybox.net/downloads/busybox-1.37.0.tar.bz2
```

复现构建全部 ABI:

```bash
tools/toolbox/build-busybox-android.sh
```

只构建指定 ABI:

```bash
ABI_LIST="arm64-v8a" tools/toolbox/build-busybox-android.sh
```

当前构建禁用了 BusyBox 内置 TLS(传输层安全协议) 和 HTTPS(安全超文本传输协议) wget 支持, 以避免 x86 目标上的内联汇编兼容问题。本阶段工具箱目标是基础命令闭环, 不是包下载器。

## 会话释放流程

```text
Open Terminal
  -> MiraRelayClient
  -> MiraToolbox.prepare(sessionId)
  -> 按设备 ABI 选择 BusyBox 资产
  -> /data/user/0/com.vwww.mira/cache/mira-sessions/<sessionId>/bin/busybox
  -> 执行 busybox --list 获取真实支持的 applet(子命令)
  -> 为全部真实支持的 applet 创建 symlink(符号链接)
  -> 复制 manifest.json 到 session 根目录
  -> MiraPtyFactory 把 session bin 放到 PATH 前面
  -> 创建 PTY(伪终端)
  -> session.close 后删除 mira-sessions/<sessionId>
```

## 运行时环境变量

远程 PTY 会新增:

```text
MIRA_TOOLBOX_BIN=/data/user/0/com.vwww.mira/cache/mira-sessions/<sessionId>/bin
MIRA_BUSYBOX=/data/user/0/com.vwww.mira/cache/mira-sessions/<sessionId>/bin/busybox
MIRA_BUSYBOX_ABI=<selected-abi>
MIRA_BUSYBOX_ASSET=toolbox/busybox/<selected-abi>/busybox
MIRA_TOOLBOX_MANIFEST=/data/user/0/com.vwww.mira/cache/mira-sessions/<sessionId>/toolbox-manifest.json
MIRA_PATH_PREFIX=$MIRA_TOOLBOX_BIN
PATH=$MIRA_TOOLBOX_BIN:$PREFIX/bin:/system/bin:/system/xbin
```

## 验收命令

```sh
echo "$MIRA_BUSYBOX_ABI"
echo "$MIRA_TOOLBOX_MANIFEST"
command -v busybox
busybox echo busybox-ok
command -v ls
ls /proc/self | head -3
```

预期 `busybox` 和 `ls` 都来自 `cache/mira-sessions/<sessionId>/bin`。

Mira 不再使用手写 applet 白名单。BusyBox 二进制支持什么命令, 当前 session 就释放什么命令入口。若二进制不支持某个系统命令, Mira 不会创建对应入口, 因此不会遮蔽 Android 系统自带命令。

## 当前边界

1. 只在远程 Relay(中继) session 中释放工具箱, Local Terminal(本地终端) 暂不接入。
2. 会话目录是临时工具副本, 会话关闭后删除。
3. APK 内的 BusyBox 资产不会删除, 这是直接打包方案的基础。
4. BusyBox 使用 GPL-2.0 许可证, 分发时需要保留来源和许可证说明。
5. 当前工具箱不实现 apt, 包索引, 动态工具包下发或 HTTPS wget。
