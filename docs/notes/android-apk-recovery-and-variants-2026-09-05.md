# APK 解压恢复、ABI 分包与 Release 实验

2026-09-05，继续上一轮压缩实验。用户补充授权解压失败恢复、Release 调查和三个 ABI 包。
本轮保留完整包，新增可选分包开关，没有改 SDK、签名身份、运行库版本或联网初始化策略；没有提交、推送、发布。

## 解压可靠性

`MiraVerifiedExtraction` 使用 APK ZIP 目录中的原始大小和 CRC 验证解压流，写到目标旁的临时文件，
调用 `FileDescriptor.sync()`，关闭后重新读取磁盘文件并比较 SHA-256，通过后才同目录 rename 替换目标。
CRC 的参照来自经 APK 签名保护的 ZIP 元数据；SHA-256 的参照来自该次已经通过 CRC/大小检查的解压流。
不是单独计算一个没有参照值的 MD5，也不是只比较内存缓冲区。

每个文件最多尝试两次，每次重新打开 ZIP 流并重新生成临时文件。
持久性磁盘空间不足、读写异常、损坏 APK 或权限问题不会无限重试，最终抛出 IOException，保留该文件原目标。
固定临时文件名使下次安装能清理中断残留；不删除 home、用户数据或整个 usr。
多文件安装不是整个目录的事务：此前已成功替换的文件可能已更新，但失败不会留下本次完成标记，下次安装重新进行。

`MiraBootstrap` 开始重装前先移除旧完成标记，只有所有资源和 wrapper 写入完成才原子写入新标记。
状态匹配由子串改为完整内容匹配，避免 `version=90` 被错误当成 `version=9`。
关键 Python、pip、Frida 条目必须存在且非空；空 Python 包标记仍允许。
移除了 ZIP 解压后再次从 AssetManager 覆盖的重复流程，也不再使用缺少条目元数据的静默 fallback。
仍使用原有 ZIP、标准 Java API 和相同资源路径，没有引入压缩库或容器。

边界：已有且符合当前版本完成标记的运行时仍复用，不会在每次启动时全量 SHA-256 扫描，也没有强制重装老用户运行时。
这里验证的是新释放/重装的写入完整性与失败恢复，不保证识别安装完成后被其他代码改写的所有运行时文件。
该策略也避免每次启动覆盖用户通过 pip 主动修改的内容。

## 实测验证

- 宿主 JVM 执行真实 Java 解压实现，覆盖首次读失败后重试成功、两次 CRC 失败、短读/超长数据、缺少元数据、同长度 SHA-256 损坏、临时文件中断残留、目标替换失败、合法空文件、STORED/DEFLATE。
- Android 环境服务使用轻量测试替身，实际 `MiraBootstrap` 安装代码处理故意损坏的 ZIP：旧标记清除、旧目标文件保留、不报告完成；更换正确 ZIP 后恢复成功；用户 home/.profile 保留。
- 使用真实 Debug APK，宿主 JVM 完整释放并校验 2,886 个 bootstrap 文件。原始输出 `build/apk-variants/host-extraction.log`。
- Debug 与上一轮压缩 APK 的 2,910 个 assets/JNI 条目集合和 SHA-256 一致；本轮 Java 可靠性修改会改变 DEX，因此不把整包 DEX 不变当作本轮验收条件。
- 分包检查对 Debug 和 Release 分别验证：只移除另一 ABI 的 JNI 目录，其余载荷逐文件 SHA-256 一致，所有 `.so` 保持 DEFLATE；两种 ABI 的 Gadget 配置和 PTY 库都明确存在。
- 分包回归拒绝缺少 ABI、空基线或把完整包误当单 ABI 包。
- 三个 Debug 包 `apksigner verify` 和 `zipalign -c -P 16 4` 通过，证书相同；三个 unsigned Release 包 ZIP 对齐通过。
- Release 的 lintVital 检查和构建通过。首次 lint 依赖下载中断后重试成功；没有跳过 lint 来制造成功。
- 默认不传分包参数的 `assembleDebug` 再次通过，原 `mira-android` 所需默认输出路径仍有效。

宿主 JVM 测试不等同于 Android 真机测试。`adb devices -l` 无设备，因此磁盘写满实机行为、突然断电、真实安装/启动、
Relay/MCP、Frida import/枚举/脚本/RPC、首次初始化时间仍未验证。
fsync/rename 改善写入恢复，但本轮没有断电实验，不能保证任何存储硬件故障都能恢复。
Gadget 4 KB ELF 段对齐仍未改变。

## 三种 ABI 包与 Release 体积

| 包 | Debug 字节 | Debug MiB | unsigned Release 字节 | unsigned Release MiB |
| --- | ---: | ---: | ---: | ---: |
| universal | 80,871,001 | 77.12 | 74,867,741 | 71.40 |
| arm64-v8a | 72,333,427 | 68.98 | 66,820,620 | 63.73 |
| armeabi-v7a | 70,134,141 | 66.89 | 64,855,954 | 61.85 |

Release 数字不含最终签名，不能当成已签名最终交付大小。
没有开启 R8 minify 或资源裁剪；Debug/Release 的所有 2,904 个 assets 文件原始内容完全一致。
Release 的 DEX、资源表和本地编译的 libmira_pty 与 Debug 可以不同；Gadget/Frida 预编译载荷未裁剪或升级。

相同源码的 AGP 9.3.2 `CompressAssetsTask` 在 debuggable 时使用 BEST_SPEED，非 debuggable 使用 DEFAULT_COMPRESSION。
实际 `_frida.abi3.so` 压缩后从 26,493,996 降到 25,030,125 字节；
arm64 Gadget 从 10,723,355 降到 10,001,394；arm32 Gadget 从 8,526,743 降到 8,037,749。
三个文件解压后原始字节未变。这解释了 Release 的部分额外收益，不是删除 Python/Frida。

ABI 分包使用 [Android 官方 splits.abi](https://developer.android.com/build/configure-apk-splits) 和 universalApk。
它们是可以独立安装的 APK，不是需要组合安装的 Play configuration splits。
本轮所有版本保留共同 assets；AGP 的 JNI ABI 分包不会自动过滤 assets 中的另一架构资源。

**arm32 的已有限制**：`build-bootstrap-prefix.py` 只构建 arm64-v8a 离线 Python/Frida prefix。
所以 arm32 单 ABI APK 虽可以构建，并保留 arm32 Gadget/PTY，却没有原生 arm32 的完整离线 Python/Frida 运行时。
它还包含不能在 arm32 上执行的 arm64 assets，不能作为“完整功能的 arm32 瘦身包”推荐交付。
完整包在纯 arm32 设备上也有同样的既有能力限制。补全 arm32 Python/Frida 工具链属于后续独立工作，不在这里用删除功能或联网下载替代。

## Release 的实际限制

项目目前没有 release signingConfig；输出明确为 `release-unsigned.apk`，没有擅自使用新签名或发布密钥。
普通 Release 关闭 debuggable，而 `tools/android/run-app.sh` 的终端转发步骤通过 `adb run-as` 读取私有 token；该步骤不能照搬。
`MiraMotionEventLogger` 也由 BuildConfig.DEBUG 控制，Release 中关闭。
因此本轮完成 Release 构建和体积实测，没有把它切成默认发布/设备安装入口，也未声称自动化功能兼容验证完成。
正式切换还需现有发布签名配置和 Release 设备回归；R8 等进一步优化应按 [Android 官方说明](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization) 单独验证。

## 复现与路径

同输入实验命令（使用上一轮已生成且有哈希记录的 bootstrap）：

```bash
./gradlew :mira-app:assembleDebug :mira-app:assembleRelease -PmiraAbiSplits=true -x :mira-app:buildTermuxPrefixAssets
python3 -m unittest discover -s tests -p 'test_bootstrap_extraction.py' -v
python3 -m unittest discover -s tests -p 'test_apk_*.py' -v
python3 tools/android/check-apk-variants.py \
  build/apk-variants/mira-app-universal-debug.apk \
  build/apk-variants/mira-app-arm64-v8a-debug.apk \
  build/apk-variants/mira-app-armeabi-v7a-debug.apk
```

正常有完整依赖环境的构建可省略 `-x`；它在本轮用于避免动态运行库更新干扰比较。
`-PmiraAbiSplits=true` 是可选开关，不传时仍输出原来完整包路径，未重构 release.py 或部署入口。

独立保存的产物目录：`build/apk-variants/`。

- `mira-app-universal-debug.apk`
- `mira-app-arm64-v8a-debug.apk`
- `mira-app-armeabi-v7a-debug.apk`（上文 arm32 能力限制）
- 对应三个 `mira-app-*-release-unsigned.apk`（未签名，不可直接安装）

同目录的 `debug-verification.json`、`release-verification.json` 记录所有产物字节数与 SHA-256；
`debug-comparison.json`、`release-comparison.json` 记录包级比较，签名/对齐/Manifest 文本和构建日志也保存在这里。
默认 Debug 输出为 `android/app/build/outputs/apk/debug/mira-app-debug.apk`。

本轮不提供手机端耗时和占用实测。SHA-256 回读与每个文件 fsync 增加 IO；删除重复解压减少 IO，净影响须在设备首次初始化时测量。

## 后续：已连接设备的当前版本冒烟测试

用户随后连接的 Mira Relay 设备报告为 SM-G965N / Android 10 / arm64-v8a。
本机 adb 列表仍为空，未执行覆盖安装。该设备当前 APK 为 147,008,822 字节，
SHA-256 `c171e061240eb4d99a9e126faad1d13a44bffca0a6a9868419787a70bcda419a`，
不是本轮构建的压缩包。以下通过项只属于当前已安装版本，不能计入新 APK 回归：

- Relay/MCP 连接与复用 PTY 正常，终端命令 exitCode=0。
- Python 3.13.13 与 Frida 16.0.7 导入正常。
- Frida 状态 connected=true，进程枚举返回一个 Gadget。
- 最小脚本 send 返回 pid/arm64，使用 Frida 16.0.7 的 `script.exports` 完成 RPC echo。
- MCP 的 `mira_frida_run_script` 自身失败，报 `Script object has no attribute exports_sync`；直接 Python 调用成功不代表该 MCP 工具通过。

README 中英文已补充经 HTTP 200 验证的 latest APK 直达下载链接。当前 GitHub latest 为 v1.1.6，
只有原完整 APK，尚无本轮新分包；未发布任何新资产。新 APK 覆盖升级、校验恢复真机测试与首次初始化测量仍待 adb 连接。

## 后续：Pixel 4 覆盖升级已验证

用户随后将 USB 接到正确电脑，adb 识别 Pixel 4 / Android 13 / arm64，序列号 `11FAFS00000W84`。
用户选择本轮只验证覆盖升级，不创建临时用户、不清数据；之后再次明确该设备可作为测试环境覆盖安装。

通过现有 `./mira-android` 完成 build → `adb install -r` → Activity extras 启动。
实际部署为独立保存的 `build/apk-variants/mira-app-universal-debug.apk`，80,871,001 字节。
安装前对比签名证书一致；安装后设备 `base.apk` SHA-256 为
`b108c845cc53e09367f714eab06b290e08f2136572a4aae542bf4b9583e7e477`，与该产物一致。
没有卸载或清除用户数据。

为避免再次联网生成运行库，设备测试使用现有 MIRA_PYTHON3 扩展点运行本地实验生成器。
首次尝试发现 Gradle 重执行任务前清理了生成目录；之后从已验证 APK 还原 2,886 个打包后的 bootstrap 资源。
打包资源与生成源树并非完全同名（例如部分 .gz 文件在打包时转换），所以不宣称恢复了原始输入树的全部哈希。
实际安装仍通过 MIRA_ANDROID_APK_PATH 固定为上文已校验的独立 APK，不依赖这一辅助构建的输出。
实验生成器和安装日志见 `build/apk-variants/pixel4/`；没有修改仓库部署脚本。

最初使用 adb reverse 的 localhost URL 被 App 已有输入校验拒绝；改用当前正常工作的公网 Relay URL，通过 Activity extras 重新启动后连接成功。
临时 adb reverse 已移除。没有修改 Relay、配对或 UI 校验实现。

实测结果：

- 覆盖安装 Success，App 启动成功。
- 日志为 `Bootstrap already installed, skip reinstall`；检查/跳过耗时 1–3 ms，**不是首次解压耗时**。
- 安装前后 `files/usr` 的 `du -sk` 都为 166,264 KiB，复用已有运行时。
- Android 已提取 arm64 Gadget、配置和 PTY 库；三个文件 SHA-256 均与 APK 对应条目一致。
- `libdynamic.config.so` 与 Gadget 位于相同的安装目录 `lib/arm64`，配置仍为 listen/resume/v8，Gadget 日志正常监听，加载耗时 18–19 ms。
- Relay 状态 control ready，Pixel 4 installId 为 `65ede1ea-05e0-4238-af29-5b33089753e9`。
- MCP 终端命令 exitCode=0；Python 3.13.13、Frida 16.0.7 导入成功。
- Frida 状态 connected=true；进程枚举返回 Gadget。
- 最小脚本 send 消息与 RPC echo 通过，使用设备 Frida 16.0.7 支持的 `script.exports` 接口。
- 专用 `mira_frida_run_script` MCP 工具仍实测失败：`Script object has no attribute exports_sync`。它是单独记录的兼容性缺陷，直接 Python RPC 成功不能算该工具通过。

安装后的实际分配占用（`du -sk`）：base.apk 79,056 KiB、提取的 arm64 JNI 24,876 KiB、已有 usr 166,264 KiB。
这些数字不含其他 App 数据、ART 优化文件或安装临时空间；未记录安装前完整系统占用，不能当作完整安装占用差值。
旧设备 APK 为 148,314,002 字节，与上一轮同代码压缩基线不同；不将此次覆盖替换的体积差直接归因为压缩收益。
覆盖安装未单独精确计时。首次初始化、设备端校验失败/磁盘写满恢复、16 KB 页兼容性仍未验证。

原始记录位于 `build/apk-variants/pixel4/`：`install-launch.log`、`startup.log`、`package-before.txt`、
`package-after.txt`、`jni-integrity.json`、`mcp-smoke.json`。
