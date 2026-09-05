# Android APK 无损压缩实验 (2026-09-05)

本报告记录第一阶段。后续解压恢复与分包实验见 [后续报告](android-apk-recovery-and-variants-2026-09-05.md)。

本轮只修改 `android/app/build.gradle` 的 `packaging.jniLibs.useLegacyPackaging = true`。
保留原有 ABI、Python、pip、Frida、Gadget、资源路径、bootstrap 释放逻辑、SDK 版本和签名配置。
新增 `tools/android/check-apk-compression.py` 和 `tests/test_apk_compression.py`。
未提交、推送或发布。包级验证通过；没有连接 Android 设备，设备功能验收未完成。

## 环境与输入

实验在仓库根目录执行；请求中提供的旧工作区路径不存在。
基于 commit `3851f5244bd9b9e87db240576936e7dd8aa6bf67`。构建产物内 metadata 确认 AGP 9.3.2；Gradle 9.7.1、JDK 21.0.2、NDK 29.0.14206865。
应用版本 1.1.0 / 110，compileSdk 36、minSdk 23、targetSdk 28。没有改动上述版本。

这是当前代码的重建比较，不是对 GitHub v1.1.6 (147,008,822 字节) 的重压实验。
两次构建均使用已存在的 `android/app/build/generated/mira-toolbox-assets` 和 `src/main/jniLibs`，
排除 `buildTermuxPrefixAssets`，不重新下载 Termux 运行库。
共 2,890 个输入文件的 SHA-256 在基线前记录，基线后及修改版后确认未变。
基线首次联网获取缺失的 Gradle/AGP 工具链，修改版使用 Gradle offline 模式。

完整输入哈希与依赖记录位于忽略的 `build/apk-compression/`：
`inputs.sha256.json`、`SOURCE.txt`、`gradle-runtime-dependencies.txt`。
运行库包括 Python 3.13.13-1、pip 26.1、Frida 16.0.7；其他 Termux 包、wheel 和来源见 `SOURCE.txt`。
Gradle runtime classpath 为 termux-am-library、Kotlin stdlib 2.2.10 和 annotations 13.0。

## 实测体积

| APK | 字节 | MiB |
| --- | ---: | ---: |
| 当前代码基线 | 145,635,511 | 138.89 |
| 修改版 | 80,868,610 | 77.12 |
| 减少 | 64,766,901 | 61.77 |

完整 APK 减少 **44.47%**。以下为实际 ZIP 目录数据，不是 raw DEFLATE 试算。
AGP 的实际压缩结果与 level 6 试算不同。

| 文件 | 基线方式 | 基线压缩后字节 | 修改版方式 | 修改版压缩后字节 |
| --- | --- | ---: | --- | ---: |
| `assets/bootstrap/prefix/arm64-v8a/lib/python3.13/site-packages/_frida.abi3.so` | STORED | 54,978,056 | DEFLATE | 26,493,996 |
| `lib/arm64-v8a/libdynamic.config.so` | STORED | 198 | DEFLATE | 138 |
| `lib/arm64-v8a/libdynamic.so` | STORED | 25,399,640 | DEFLATE | 10,723,355 |
| `lib/armeabi-v7a/libdynamic.config.so` | STORED | 198 | DEFLATE | 138 |
| `lib/armeabi-v7a/libdynamic.so` | STORED | 16,371,344 | DEFLATE | 8,526,743 |

完整 116 个库条目的比较见 `build/apk-compression/comparison.json`，基线完整目录见 `baseline-inventory.json`。

## assets 下 .so 的原因与加载链路

[AGP 9.3 官方 API](https://developer.android.com/reference/tools/gradle-api/9.3/com/android/build/api/variant/JniLibsApkPackaging)
说明 minSdk >= 23 时默认不压缩 `.so`，显式 legacy 开关改变此策略。
本轮还下载了 Google Maven 的 AGP **9.3.2** builder 和 gradle sources jar，直接检查相同版本源码：

- `PackagingUtils.getAllNoCompressExtensions` 在 `UNCOMPRESSED_AND_ALIGNED` 模式添加 `.so`。
- `getNoCompressPredicateForExtensions` 使用不区分大小写的后缀匹配，不限制为 `lib/`。
- `PackageAndroidArtifact` 从合并 Manifest 获取 native packaging mode，并将该 predicate 交给最终 APK packager。
- `CompressAssetsTask` 的中间资源压缩使用 JavaRes predicate；最终 APK 规则仍可让 assets 下 `.so` 变成 STORED。

因此不是假设 JNI DSL 覆盖 assets：相同版本源码和本轮实际 APK 都证明只改该开关即可。
没有添加 `noCompress`；它用于排除压缩，并非开启压缩。
官方源码包及摘录保存在 `build/apk-compression/`。

`MiraBootstrap.extractBootstrapPrefixFromApk()` 使用 `ZipFile.getInputStream()`；
`extractAssetTree()` 使用 `AssetManager.open()`，均以流复制到 `files/usr`。
压缩前后路径和原始字节相同，不需要容器、命名或解压架构变化。
现有代码会先走 ZIP 再遍历 assets；本轮未优化该流程，因此首次初始化耗时必须按现有完整流程实测。

`MiraApplication` 继续 `System.loadLibrary("dynamic")`。
合并 Manifest 的 `extractNativeLibs=true` 使 Android 按设备 ABI 将 JNI 库解压到应用的 `nativeLibraryDir`。
两个 ABI 都保留 `libdynamic.so` 和相邻的 `libdynamic.config.so`，配置均为原来的 198 字节，DEFLATE 后 138 字节，内容完全相同。
这种配置命名符合 [Frida 官方 Gadget 文档](https://frida.re/docs/gadget/) 对 Android 的 `lib*.so` 复制规则。
构建日志对 JSON 配置出现无法 strip 的提示，文件按原样保留；它不是 ELF，不能当作无用库移除。
实际设备的提取路径、配置加载和 Gadget 监听仍未验证。

[Android APK size 文档](https://developer.android.com/topic/performance/reduce-apk-size) 和
[Flet 参考](https://github.com/flet-dev/flet/blob/main/website/docs/publish/android.md) 的 raw APK 分发权衡适用于本项目；
本轮未引入 Flet 配置、AAB 或 Play 分发。

## 验证结果

- 两次 `assembleDebug` 成功；签名前通过正常 Gradle 流程完成压缩，没有重压签名后的交付包。
- 2,944 个非签名、非 Manifest 载荷条目的路径集合和解压后 SHA-256 完全一致，包括 DEX、资源、所有 bootstrap 文件和所有 ABI 库。
- 116 个 JNI/bootstrap `.so` 全部 DEFLATE，必需运行文件非空；两个 ABI 的 Gadget 与 JSON 配置明确存在。
- Python 空包标记文件可以合法为空；关键 frida-tools 检查使用非空的 `application.py`，不把空 `__init__.py` 当功能证据。所有文件仍参加哈希比较。
- 检查器拒绝空 APK、缺失关键文件、空关键文件、内容改变及仍为 STORED 的库，相关单元测试通过。
- Android Build Tools 36.1.0 `apksigner verify --verbose --print-certs` 两包通过 v1/v2 验证，签名证书相同。
- `zipalign -c -P 16 -v 4` 两包均通过；压缩库不需要从 APK 页对齐映射。
- `aapt2 dump xmltree` 逐行比较：Manifest 唯一变化为 `extractNativeLibs=false` → `true`，应用 ID、SDK、版本、权限等未变。
- arm64 Gadget ELF `LOAD` 对齐仍为 `0x1000`；Frida Python 扩展为 `0x4000`。**压缩没有修复 Gadget 的 16 KB ELF 兼容性**，也不构成整包 16 KB 设备兼容验证。
  参见 [Android 页大小文档](https://developer.android.com/guide/practices/page-sizes)。

签名、对齐、Manifest 原始输出、ELF program headers 和机器可读验证结果均保存在 `build/apk-compression/`。

## 安装占用与耗时

没有设备，以下仅为根据 ZIP 未压缩字节数的占用估算，不是系统安装占用实测：

| 设备 ABI | 安装时新增提取的 JNI 字节 | 新增 JNI MiB | APK 减少减去 JNI 提取后的理论净节省 MiB |
| --- | ---: | ---: | ---: |
| arm64-v8a | 25,430,718 | 24.25 | 37.51 |
| armeabi-v7a | 16,391,914 | 15.63 | 46.13 |

假设 Android 只提取所选 ABI，并且其他占用不变。上表不含文件系统块分配、DEX 优化和安装临时空间。
bootstrap 在原方案中也会释放，修改后释放出的内容大小不变。
压缩增加安装时 JNI 解压以及首次 bootstrap 读取时的解压 CPU 工作；实际安装耗时、首次初始化耗时和启动耗时均 **未测量**。
本轮基线构建 2m39s、增量修改构建 2s，只是构建日志，不能用来比较安装或初始化耗时。

## 设备验收仍待完成

开始和结束检查 `adb devices -l` 都没有设备；未安装、卸载、清数据或启动 Relay。
以下全部未验证：覆盖升级与已有运行时启动、Relay/MCP 接入、终端命令、`import frida`、进程枚举、最小 Frida 脚本与 RPC、
Gadget 配置实际加载、首次离线释放、安装占用和首次初始化耗时。
不能将包级通过描述为功能验收通过。

后续在设备可用时必须沿用 `./mira-android` → `adb install -r` → 启动链路；签名不兼容时停止并报告，不卸载重试。
首次释放需使用经确认可丢弃的独立测试环境，对两包分别记录初始化日志耗时及占用；不能使用已有 `files/usr` 作为首次释放证据。
若要复用本轮已冻结的 APK/输入，请先确认构建入口不会重新运行动态依赖生成任务。

## 复现与产物

基线是在增加 legacy block 之前执行：

```bash
./gradlew :mira-app:assembleDebug -x :mira-app:buildTermuxPrefixAssets --console=plain
cp android/app/build/outputs/apk/debug/mira-app-debug.apk build/apk-compression/mira-baseline.apk
```

添加本轮打包开关后执行：

```bash
./gradlew :mira-app:assembleDebug --offline -x :mira-app:buildTermuxPrefixAssets --console=plain
cp android/app/build/outputs/apk/debug/mira-app-debug.apk build/apk-compression/mira-compressed.apk
python3 tools/android/check-apk-compression.py build/apk-compression/mira-baseline.apk build/apk-compression/mira-compressed.apk
python3 -m unittest discover -s tests -p test_apk_compression.py -v
```

`-x` 仅用于本轮冻结运行库输入的对照实验；使用前必须有完整已生成的 bootstrap，并保存/复核其哈希。
检查器会拒绝缺失关键运行库的 APK。普通构建与 `mira/release.py` 不需改动，都会使用已修改的 packaging 配置。
正常重新生成 Termux 依赖后体积可能变化，不能与本轮数字直接归因比较。

APK 绝对路径：

- 基线：`build/apk-compression/mira-baseline.apk`
- 修改版：`build/apk-compression/mira-compressed.apk`
- 常规构建输出：`android/app/build/outputs/apk/debug/mira-app-debug.apk`

SHA-256：

- 基线：`180646a473027aced520caae141e41d4f1bf99b064df6ee079d9996fc4f7a22a`
- 修改版：`c1d7f3380841a16cd193e54e55612d44d59592b6c5a40fa013e2bce1420f55a4`
