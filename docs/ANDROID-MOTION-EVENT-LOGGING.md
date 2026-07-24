# Android MotionEvent logging

## 目标

Mira Android 侧在 `Debug(调试构建)` 中打印 `MotionEvent(Android 触摸事件对象)` 的完整可读日志, 便于后续人工或 AI 分析输入链路风险。

这个日志不是业务事件埋点, 而是输入事件取证面。设计目标是:

1. 尽量打印 `MotionEvent` 可读取字段。
2. 单字段读取失败不能影响 UI 事件分发。
3. 日志必须结构化, 便于 AI 聚合和解析。
4. 厂商 ROM(安卓定制系统), 系统版本, 输入设备差异不能导致崩溃。

## 入口

入口在:

`/Users/vw2x/Projects/Reverses/Mira/android/app/src/main/java/com/vwww/mira/MainActivity.java`

`MainActivity` 只负责接入事件分发:

```java
@Override
public boolean dispatchTouchEvent(MotionEvent event) {
    MiraMotionEventLogger.log(event);
    return super.dispatchTouchEvent(event);
}
```

日志实现集中在:

`/Users/vw2x/Projects/Reverses/Mira/android/app/src/main/java/com/vwww/mira/MiraMotionEventLogger.java`

## 日志标签

```bash
adb logcat -s MiraMotionEvent
```

## 输出结构

日志以 `JSON(JSON 数据格式)` 输出。每次输入事件共享同一个 `seq(事件序号)`。

### event 记录

`record=event` 表示事件整体信息, 包括:

1. `action`, `actionMasked`, `actionIndex`.
2. `pointerCount`, `historySize`.
3. `downTime`, `eventTime`, `eventAgeMs`.
4. `deviceId`, `device`, `source`, `flags`, `edgeFlags`.
5. `metaState`, `buttonState`.
6. `xPrecision`, `yPrecision`.
7. 默认指针的坐标, 压力, 接触面积, 工具大小, 方向。
8. `classification(触摸分类)`, `displayId(显示屏 ID)` 等版本相关字段。
9. `axisValues(输入轴值)`。

### pointer 记录

`record=pointer` 表示每一个指针, 手指, 鼠标或触控笔的当前采样信息, 包括:

1. `pointerIndex`, `pointerId`.
2. `toolType`, `toolTypeName`.
3. `x`, `y`, `rawX`, `rawY`.
4. `orientation`, `pressure`, `size`.
5. `touchMajor`, `touchMinor`, `toolMajor`, `toolMinor`.
6. `pointerProperties(指针属性)`.
7. `pointerCoords(指针坐标快照)`.
8. `axisValues`.

### history 记录

`record=history` 表示历史采样。`MOVE(移动事件)` 可能包含多个历史点, 每个历史点会单独输出一条记录, 并包含对应指针列表。

## 异常设计

所有字段读取都通过统一包装方法执行:

```java
put(json, "field", () -> event.getSomething())
```

如果某个字段在特定机型或系统版本上抛异常, 日志会写入:

```json
{"fieldError":"ExceptionType: message"}
```

不会中断整条事件日志, 也不会影响 `super.dispatchTouchEvent(event)` 继续执行。

`log()` 和 `emit()` 外层也有最终兜底, 避免日志系统自身异常冒泡到 UI 线程。

## 长日志分片

如果单条 JSON 超过 logcat 单行安全长度, 会输出 `mira_motion_event_chunk` 分片记录:

```json
{
  "type":"mira_motion_event_chunk",
  "seq":1,
  "record":"event",
  "chunkIndex":0,
  "chunkCount":2,
  "payload":"..."
}
```

AI 或脚本读取时按 `seq + record + chunkIndex` 重新拼接 `payload`, 再解析原始 JSON。

## 兼容性注意

1. `BuildConfig.DEBUG` 为 `false` 时不会打印, 避免 release 包噪音。
2. 新系统字段必须做版本判断或反射调用, 不要直接无保护调用。
3. `MotionEvent` 可能在事件分发后被复用, 当前实现同步读取, 不异步保存对象引用。
4. 不要在 `dispatchTouchEvent` 中做网络上传或重型分析, 避免输入卡顿。
5. 如果未来接入远端采集, 应传 JSON 文本或结构化副本, 不传 `MotionEvent` 对象本身。

## Review checklist

修改 `MiraMotionEventLogger` 后至少检查:

1. 每个新字段是否通过 `put` 包装。
2. 新增 Android API(安卓接口) 是否有 `SDK_INT(系统版本号)` 判断或反射保护。
3. 日志是否仍然是单行 JSON 或可重组 JSON 分片。
4. `./gradlew :mira-app:compileDebugJavaWithJavac` 是否通过。
5. 真机上 `adb logcat -s MiraMotionEvent` 是否能看到 `event`, `pointer`, `history` 三类记录。
