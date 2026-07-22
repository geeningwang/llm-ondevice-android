# 应用概览：端侧 Gemma 聊天演示应用（Android）

_最后更新：2026-07-22_

本文档介绍这个应用做什么、是如何构建的，以及在开发过程中发现并修复的
全部真实问题清单——便于日后接手这份代码的人快速了解背景。

## 应用功能

一个基于 Kotlin + Jetpack Compose 的 Android 应用，用户可以选择两个
端侧大模型中的一个，下载它（或复用已缓存的副本），然后完全在设备端与
其对话（推理过程不经过任何服务器）。应用会实时展示当前正在执行的操作
日志，以及全局常驻的系统资源（CPU、Memory PSS、Native Heap 及 60 秒实时折线图）监控面板。

## 可选模型

| 模型 | 后端 | 格式 | 大致体积 | 来源 |
|---|---|---|---|---|
| Gemma 3 1B-IT | MediaPipe `tasks-genai`（`LlmInference`） | `.task`（ZIP） | 约 555MB | Hugging Face 上的 `litert-community/Gemma3-1B-IT`（受限仓库，需接受 Gemma 许可协议） |
| Gemma 4 E2B-IT | LiteRT-LM | `.litertlm` | 约 2.41GB | Hugging Face 上的 `litert-community/gemma-4-E2B-it-litert-lm`（受限仓库） |

这两个文件目前都镜像在一台私有测试 HTTP 服务器上（具体地址见
`Models.kt`）——该服务器并非公开/永久的分发节点；如果要用于正式部署，
应根据实际需求另行选择合适的托管方式。

## 架构

- **`Models.kt`** —— `ModelOption` 数据类以及 `AVAILABLE_MODELS` 注册表。
  每个选项都声明了自己的后端、下载地址、本地文件名，以及期望的容器格式
  （`ModelFormat.TASK_ZIP` 或 `ModelFormat.LITERTLM`）。
- **`ModelDownloader.kt`** —— 基于 OkHttp 的下载器，支持进度上报和格式
  校验。`.task` 文件通过尝试 `ZipFile` 是否能成功打开来校验（而不是脆弱
  的"偏移量 0 处魔数检查"——原因见下文"已修复的问题"部分）；`.litertlm`
  文件通过检查开头 8 字节的 ASCII 魔数 `"LITERTLM"` 来校验。
- **`InferenceModel.kt`** —— 封装 MediaPipe 的 `LlmInference` +
  `LlmInferenceSession`。整个对话过程中始终复用同一个 `LlmInferenceSession`
  实例，以保持多轮对话的上下文。
- **`LiteRtLmInferenceModel.kt`** —— 封装 LiteRT-LM 的 `Engine` +
  `Conversation`。同样在整个对话过程中复用同一个 `Conversation` 实例。
- **`ChatViewModel.kt`** —— 统筹一切：下载流程、一个将 `InferenceModel`
  或 `LiteRtLmInferenceModel` 封装成统一形态的 `EngineAdapter` 密封类
  （这样应用的其他部分无需关心当前用的是哪个后端）、操作日志，以及聊天
  状态管理。
- **`MainActivity.kt`** —— Compose UI：模型选择界面、下载/初始化进度
  界面、聊天界面（带常驻的 `SystemStatusPane` 系统状态面板，底部带可滚动 `LogPanel`
  日志面板）、以及带重试/重置操作的错误界面。

## 系统资源与 LLM 性能监控

应用中内置了一个实时的系统状态监控面板（`MainActivity.kt` 中的 `SystemStatusPane`），持续监控 CPU 使用率、内存指标以及 Token 生成速度（Tokens/s），并在紧凑的 2x2 网格中展示数值指标，同时配合 60 秒历史折线图（`SystemStatusChart`）展示变化趋势。该面板位于 UI 布局顶层，在所有界面（模型选择、下载中、初始化中、聊天界面、错误界面）之间保持全局常驻，使得系统影响在模型下载、引擎初始化和 Token 生成过程中能够被连续观察。

### 监控指标与 2x2 布局

- **CPU Usage**（橙色）：通过 `/proc/self/stat` 差值计算的进程 CPU 使用率。
- **Memory PSS**（绿色）：通过 `Debug.getMemoryInfo()` 获取的进程实际占用系统 RAM 份额。
- **Native Heap**（紫色）：通过 `Debug.getNativeHeapAllocatedSize()` 获取的 C/C++ 动态堆内存（LLM 权重张量和 KV 缓存）。
- **Tokens/s**（青色）：LLM 生成 Token 过程中的实时吞吐速度（$\text{tok/s}$）。

### Memory PSS（Proportional Set Size，比例集大小）

- **定义**：PSS 是 Android 操作系统用于衡量一个应用进程真实 RAM 占用量的核心指标。它包含该进程独占的私有内存（Private Clean + Private Dirty），以及按共享进程数比例分摊的共享内存（Shared Clean + Shared Dirty，例如系统框架库、ART 运行时库和共享动态链接库 `.so` 文件）。
- **为何不直接用 RSS**：传统的驻留集大小（RSS）会在多个共享内存页的进程间重复计算，从而高估内存占用。PSS 能够提供准确、无重叠计算的数值，反映此进程实际需要为系统 RAM 承担的责任份额。
- **Android 中的获取方式**：`Debug.MemoryInfo().totalPss`（通过 `Debug.getMemoryInfo(memInfo)` 获取，单位为 KB，除以 `1024f` 转换为 MB）。
- **在端侧大模型中的作用**：PSS 反映了整个应用进程对系统内存的总影响，包含了 Kotlin/Java ART 虚拟机堆、Jetpack Compose UI 渲染缓冲区、系统图形驱动、mmapped 映射文件以及底层 C++ 推理引擎的原生内存开销。

### Native Heap（原生堆）

- **定义**：Native Heap 指通过 C/C++ 动态内存分配函数（`malloc`、`calloc`、`posix_memalign`、`new` 等，由 Android 的 `jemalloc` / `scudo` 分配器管理）在 Native 堆上分配的内存，运行在 ART (Android Runtime) 垃圾回收堆之外。
- **为何对端侧大模型至关重要**：端侧 LLM 推理引擎（如 MediaPipe `LlmInference`、LiteRT-LM `Engine`、XNNPACK、OpenCL 与 Vulkan 加速层）均为 C++ Native 库。它们会将模型权重张量、上下文工作区缓冲区以及 KV 缓存（Key-Value Cache，用于多轮对话生成）直接分配在 Native 堆或 Native 显存/内存空间中。
- **Android 中的获取方式**：`Debug.getNativeHeapAllocatedSize()`（获取单位为 Byte，除以 `1024f * 1024f` 转换为 MB）。
- **在端侧大模型中的作用**：Native Heap 是观察 LLM C++ 引擎内存开销的直接指标。当大模型进行初始化或流式生成 Token 时，由于权重和 KV 缓存状态被写入 Native 内存，Native Heap 会出现明显的上升。通过监控 Native Heap，开发者可以将 LLM 的底层 C++ 内存消耗与 Java 运行时的内存分配隔离开进行独立分析。

## 开发过程中发现并修复的问题

1. **最初提供的文件格式不对。** 最初的模型下载地址提供的是原始 TFLite
   flatbuffer 文件（魔数为 `TFL3`），却被错误地标记为 `.bin`/`.task`/
   `.web.task` 扩展名。MediaPipe 的 `tasks-genai`（截至 0.10.35 版本）
   只接受真正的 `.task` ZIP 格式包——原始 flatbuffer 会触发"不是有效的
   ZIP 压缩包"这一原生层错误。解决方式是通过检查文件魔数字节，找到并
   验证一个真正的 `.task` 格式包（来自
   `litert-community/Gemma3-1B-IT` 的 `gemma3-1b-it-int4.task`）后再接入。
2. **ZIP 格式校验过于严格。** 我们自己的下载器要求 ZIP 本地文件头签名
   （`PK\x03\x04`）必须出现在文件偏移量 0 的位置，但真正的 `.task`
   格式包在该签名前可能会有少量填充字节。解决方式是改用
   `ZipFile(file).use {}` 进行校验（与任何标准 ZIP 读取器一样，通过文件
   末尾的目录结束记录来定位压缩包），而不是按字节偏移量硬性检查。
3. **未复用已下载的缓存文件。** 即便磁盘上已有有效的模型文件副本，每次
   点击"Start"都会重新下载。解决方式是先检查本地是否已有有效文件，并
   通过清晰的日志让用户能看到这一行为。
4. **"Retry Download"（重试下载）实际并未真正重试。** 它会一直复用同一
   个已损坏的缓存文件。解决方式是增加了 `forceRedownload` 标志位。
5. **（MediaPipe）第一条消息之后对话上下文就丢失了。**
   `LlmInference.generateResponseAsync(String, ...)` 会在**每次调用时**
   重置其"隐式 session"（从而丢失 KV 缓存/对话上下文）。解决方式是显式
   创建一个 `LlmInferenceSession`，并在每一轮对话中都复用它，通过
   `addQueryChunk()` + `generateResponseAsync()` 调用。
6. **聊天列表和日志面板中没有可见的滚动条。** Compose 的 `LazyColumn`
   并没有内置的滚动指示器（不同于传统的基于 View 的滚动容器）。解决
   方式是编写了一个自定义的 `drawWithContent` 修饰符，根据
   `LazyListState.layoutInfo` 绘制一个会渐隐的滚动条滑块。
7. **自动滚动无法始终展示正在流式输出的内容。** `animateScrollToItem()`
   会将目标条目对齐到视口的**顶部**，因此对于一条仍在不断增长的长回复
   消息气泡，最新（位于底部）的文本会一直停留在可视区域之外；同时，
   频繁的逐 token 更新还会导致动画相互叠加排队。解决方式是改用立即（
   不带动画）执行的 `scrollToItem(index, scrollOffset = Int.MAX_VALUE)`，
   Compose 会将该偏移量自动裁剪到实际内容大小。
8. **无法在不丢失已下载文件的情况下退出聊天并释放内存。** 增加了一个
   返回按钮（仅在聊天界面显示），点击后会释放当前引擎占用的原生资源，
   但保留已下载的模型文件缓存。
9. **无法了解应用当前在做什么。** 增加了一个带时间戳的持久操作日志
   （下载/校验/初始化进度、错误信息），显示在屏幕底部的可滚动面板中。
10. **无法了解资源消耗情况。** 增加了全局常驻的系统状态面板（`SystemStatusPane`），
    包含数值指标（通过 `/proc/self/stat` 的差值计算 CPU 使用率，通过
    `Debug.getMemoryInfo()` 获取 Memory PSS，通过 `Debug.getNativeHeapAllocatedSize()`
    获取 Native Heap）以及一个 60 秒精细滚动的 Compose Canvas 实时折线图
    （`SystemStatusChart`）。将其移至 UI 布局顶层，使得在模型选择、下载、初始化、
    聊天及错误等不同界面间切换时，采样保持连续不断。
11. **具有破坏性操作的按钮（"Clear Internal Storage"／"Clear Storage &
    Reset"）文字是红色的，但没有边框**，因为它们当时使用的是
    `TextButton`（该组件本身设计上就没有边框）。解决方式是改用
    `OutlinedButton`，并显式指定红色的 `BorderStroke`。
12. **Gemma 4 根本没有 MediaPipe 的 `.task` 格式包。** 所有尺寸的 Gemma 4
    （E2B/E4B/12B/26B-A4B/31B）都只以 `.litertlm` 格式发布。我们评估过
    编写一个从 `.litertlm` 转换到 `.task` 的转换工具，发现在技术上不
    可行（完整的容器格式对比参见 `mediapipe_vs_litertlm_en.md`）——两套
    引擎的底层算子集已经出现了分歧。最终的解决方案是新增 LiteRT-LM 作为
    第二个、与 MediaPipe 并存的后端。
13. **LiteRT-LM 的 Kotlin API 需要反编译 AAR 才能确认真实签名。** 官方
    文档中的示例代码（`message.text`）与真实的
    `litertlm-android:0.14.0` API 并不一致——`Message` 类根本没有 `.text`
    属性。真正获取文本的方式是
    `message.contents.contents.filterIsInstance<Content.Text>()`。这是
    通过对 Gradle 缓存中 AAR 提取出的 class 文件运行 `javap` 才确认的。
14. **验证了 MediaPipe 与 LiteRT-LM 可以在同一个 APK 中共存。** 同时引入
    这两个依赖不会产生重复类冲突，也不会产生原生库命名冲突
    （`liblitertlm_jni.so` 与 `libllm_inference_engine_jni.so`）；已通过
    完整执行一次 `assembleDebug` 得到验证。APK 体积从约 129MB
    （仅 MediaPipe）增长到约 176MB（两者都有）。

## 构建与运行

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Android SDK 路径配置在 `local.properties` 文件中的 `sdk.dir` 字段。

## 已知限制／可能的后续改进

- LiteRT-LM 目前默认使用 CPU 后端以保证广泛的兼容性；若要使用 GPU/NPU
  后端，需要额外在 Manifest 中声明（`<uses-native-library>`），并且／或
  需要针对特定设备编译的 `.litertlm` 变体。
- `MessageCallback.onMessage()` 的具体流式语义（每次回调传递的是增量
  片段，还是到目前为止的累计完整消息）目前是根据 API 形态推断得出的，
  尚未通过真机上实际生成内容进行确认——建议在设备上用真实 prompt
  测试后再次核实。
- 模型文件目前托管在本演示专用的私有测试服务器上；不适合作为公开的
  分发节点使用。

## 相关文档

- [mediapipe_vs_litertlm_en.md](mediapipe_vs_litertlm_en.md) /
  [mediapipe_vs_litertlm_zh.md](mediapipe_vs_litertlm_zh.md) —— 深入介绍
  这两套框架、容器格式，以及完整的优缺点对比。
