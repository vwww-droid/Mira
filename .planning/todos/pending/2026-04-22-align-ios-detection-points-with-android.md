---
created: 2026-04-22T15:19:18.020Z
title: Align iOS detection points with Android
area: general
files:
  - native/src/shell/ish_hostfs.m
  - native/src/shell/ish_shell.m
  - docs/IOS-ISH-POC.md
---

## Problem

iOS 端已经接入 iSH shell(命令解释器) 和 Mira hostfs(宿主虚拟文件系统), 但当前检测信息组织仍偏临时, 例如先用 `/mira/host/*` 暴露 host(宿主系统) 数据。后续目标应该不是伪造完整 iOS root(根目录), 而是把普通 iOS 第三方 app 自己能看到的所有信息按 Android(安卓系统) 排障习惯组织起来, 让已有 Android 风险检测思路可以迁移到 iOS。

需要重点向 Android 的 `/proc/self/*` 和 app 文件视角靠拢, 包括 maps(内存映射), fd(file descriptor, 文件描述符), cmdline(命令行), environ(环境变量), status(进程状态), images(已加载镜像), bundle(应用包), sandbox(应用沙盒), task(任务统计) 等检测点。这样远程终端中可以用 BusyBox(精简 Linux 工具集) 的 `cat`, `grep`, `awk`, `find` 复用 Android 式分析路径。

## Solution

后续 iOS 检测点设计以 `/mira/proc/self/*` 作为进程视角入口, 不保留未发布的 `/mira/host/*` 兼容路径。第一阶段先迁移现有 `maps`, `images`, `task`, `bundle`, `paths`, `summary` 到 `/mira/proc/self/*`, 并新增 `/mira/proc/self/fd`, `/mira/proc/self/cmdline`, `/mira/proc/self/environ`, `/mira/proc/self/status`。

文件视角单独放到 `/mira/fs/*`, 例如 `/mira/fs/app`, `/mira/fs/container`, `/mira/fs/documents`, `/mira/fs/library`, `/mira/fs/tmp`, 用于后续扫描重打包, Frida Gadget(Frida 内嵌动态库), 可疑资源和 sandbox runtime(沙盒运行态) 文件。所有 iOS 能力必须明确限定在当前 app 自己可见范围内, 不承诺越狱式全系统视角。
