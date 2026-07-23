# MediaPipe LLM Inference 与 LiteRT-LM 对比：优缺点与建议

_最后更新：2026-07-21_

> 状态更新：本演示应用现已同时支持两个模型，并且均已在真机上完成端到端
> 验证：Gemma 3 1B-IT 通过 MediaPipe 的 LlmInference API 运行，
> Gemma 4 E2B-IT 通过 LiteRT-LM 运行（已针对真实的 .litertlm
> 文件验证，魔数 LITERTLM，格式版本 1.5.0，约 2.41GB）。这两个依赖可以
> 在同一个 APK 中共存，没有任何冲突。完整架构说明以及开发过程中发现并修复
> 的问题清单，请参见 app_overview_zh.md。

本文总结了我们在为 Android 构建端侧 Gemma 聊天演示应用过程中,对 Google
两套端侧大模型（LLM）推理方案的第一手对比经验：传统的 **MediaPipe LLM
Inference API**（`com.google.mediapipe:tasks-genai`，核心类
`LlmInference` / `LlmInferenceSession`）以及其官方指定的后继方案
**LiteRT-LM**（`com.google.ai.edge.litertlm`）。

## 一句话总结

- **MediaPipe 整体并未被放弃。** 它仍是一个在视觉、音频、文本等任务上持续
  维护的框架（目标检测、姿态/手部关键点、图像分割等功能均在积极开发中）。
- **仅有 MediaPipe 中的 LLM Inference 这一子任务被标记为弃用**（源码中标注
  `@Deprecated`，官方文档标注为"仅维护模式"）。Google 明确建议新的 GenAI
  开发迁移到 LiteRT-LM。
- **新发布的模型（例如 Gemma 4，所有尺寸）仅提供 LiteRT-LM 格式**
  （`.litertlm`）。官方没有为 Gemma 4 提供 `.task` 格式包，也没有计划提供。
  自行转换在技术上也非常困难（详见下文）。
- 如果你必须继续使用传统的 MediaPipe API，较早的模型（Gemma 2B/3 1B 等）
  仍有真实可用的 `.task` 格式包，例如可在 Hugging Face 的
  `litert-community` 组织下找到。

## 本项目使用的版本信息

本应用将两套技术栈同时打包在一起（已验证可在同一个 APK 中共存，没有依赖
冲突或原生库冲突）。截至本文撰写时的具体版本如下：

| 依赖 | 版本 | 说明 |
|---|---|---|
| `com.google.mediapipe:tasks-genai` | 0.10.35 | 撰写本文时的最新发布版本（2026 年 4 月）；即便该 API 已被弃用/进入维护模式，这仍是当前可获取的最新版本 |
| `com.google.ai.edge.litertlm:litertlm-android` | 0.14.0 | 最新发布版本，对应 LiteRT-LM GitHub 上的 `v0.14.0` 版本 |
| `.litertlm` 文件格式版本 | 1.5.0（主版本.次版本.修订版本） | 从我们验证过的 `gemma-4-E2B-it.litertlm` 文件头（魔数 `LITERTLM` + 版本字段）中读取得到；运行时会校验主版本号，主版本不匹配的文件会被拒绝加载 |
| Android Gradle 插件（AGP） | 9.3.0 | |
| Kotlin | 2.2.10 | |
| Jetpack Compose BOM | 2026.02.01 | |
| OkHttp（用于模型下载） | 4.12.0 | |

将两套技术栈打包在一起，会让原生库体积大致翻倍：我们的 debug APK 从约
129MB（仅 MediaPipe）增长到约 176MB（MediaPipe + LiteRT-LM）。如果你只
需要其中一套技术栈，移除未使用的依赖即可控制 APK 体积。

## MediaPipe 和 LiteRT-LM 是什么关系？

一个自然会产生的问题是：两者之间是否存在“基于”关系（谁构建在谁之上）？
答案是都不是——它们其实是“兄弟关系”，都构建在同一个共享的底层运行时
LiteRT（即改名后的 TensorFlow Lite）之上：

```mermaid
graph TD
    A[LiteRT: 端侧底层运行时, 原名 TensorFlow Lite] --> B[MediaPipe: 多任务框架, 涵盖视觉/音频/文本/GenAI]
    A --> C[LiteRT-LM: 全新的, 专为 LLM 设计的编排层]
    B -.已弃用的 GenAI 子任务.-> D[MediaPipe LlmInference, tasks-genai, 维护模式]
    D -.Google 推荐的迁移方向.-> C
```

- **LiteRT** 是共享的底层运行时，负责在 CPU/GPU/NPU 上真正执行模型图。
- **MediaPipe** 是一个更宽泛、历史更久的框架（由一系列“calculator”组成的
  图），它在内部通过 LiteRT 来运行各类任务的模型：视觉、音频、文本，
  以及（在被弃用之前的）GenAI/LLM。
- **LiteRT-LM** 是一个独立的、更新的、专为 LLM 设计的编排层，直接构建在
  LiteRT 之上，而不是构建在 MediaPipe 之上。它添加了 LLM 专属的基础设施
  （KV 缓存管理、prompt 模板、工具/函数调用、推测解码），这些是 MediaPipe
  基于 calculator 图的模型设计中并未考虑到的。

因此，MediaPipe 中（已弃用的）LlmInference 子任务和 LiteRT-LM，其实是
同一个底层 LiteRT 运行时的两个独立、并行的使用者——LiteRT-LM 的设计目的是
取代 MediaPipe 的 GenAI 子任务，而不是在其基础上扩展或位于其下层。
MediaPipe 整体（其非 GenAI 的其他任务）并不受影响，也完全不依赖
LiteRT-LM。

## 背景：两种不同的容器格式

| | MediaPipe `.task` | LiteRT-LM `.litertlm` |
|---|---|---|
| 容器类型 | ZIP 压缩包（ZIP 签名前可能有少量填充字节） | 自定义二进制容器：8 字节魔数 `"LITERTLM"` + 版本号 + flatbuffer 头部 + 按块对齐的多个数据段 |
| 结构（我们实际检查到的真实示例） | 固定 3 个条目：`TF_LITE_PREFILL_DECODE`（合并了 prefill+decode 的 TFLite 图）、`TOKENIZER_MODEL`（SentencePiece 分词器）、`METADATA`（很小的 proto 元数据） | 任意数量的分类数据段：`TFLiteModel`（含 `model_type`：`embedder`/`prefill_decode`/`prefill`/`decode`/草稿模型 等）、`SP_Tokenizer`、`HF_Tokenizer`、`LlmMetadata` proto、embedding 元数据、系统元数据（uuid、时间戳）等 |
| 可扩展性 | 结构固定——一个模型、一个分词器、极少元数据 | 专为可扩展设计——可容纳多个模型（例如用于推测解码的草稿模型、视觉/音频编码器）、多种分词器类型、更丰富的元数据 |
| 工具链 | `mediapipe.tasks.python.genai.bundler`（较旧的 Python 工具） | 官方 PyPI 包 `litert-lm-builder`，提供命令行工具（`litert-lm-builder`、`litert-lm-peek`）用于构建/查看/解包 `.litertlm` 文件 |
| 运行时识别方式 | ZIP 签名 `PK\x03\x04`（文件中的偏移量可能不是 0） | 文件起始处的魔数字符串 `LITERTLM` |

## MediaPipe LLM Inference API（`tasks-genai`）

### 优点

- **API 简单、体积小。** `LlmInference.createFromOptions()` 搭配
  `LlmInferenceSession`（`addQueryChunk()` / `generateResponseAsync()`）
  易学易用，可快速集成。
- **仍在正常发布和维护可用。** 最新版本（0.10.35，2026 年 4 月）对于
  目标模型运行良好；并非"损坏"，只是功能范围被冻结。
- **对多年前转换的模型有广泛的向后兼容性**（Gemma 1/2、Phi-2，以及
  Gemma 3 1B/2B 级别的模型仍可在 `litert-community` 组织下找到
  `.task` 格式包）。
- **对已有 MediaPipe 应用的集成成本更低**——如果你的应用已经在用
  MediaPipe 做视觉/音频任务，继续使用同一框架可以避免引入第二套
  依赖/运行时。
- 支持 LoRA 适配器（仅限 `.task` 格式的基础模型，且仅支持 GPU 后端）。

### 缺点

- **已被弃用，处于维护模式。** 不会有新功能，更关键的是——**不会支持新
  模型**。所有相关类在 MediaPipe 源码中都标注了 `@Deprecated`，并明确
  指向 LiteRT-LM。
- **无法使用更新的模型代际。** Gemma 4（包括 E2B、E4B、12B、
  26B-A4B MoE、31B 所有尺寸）仅以 `.litertlm` 格式发布。官方没有为
  Gemma 4 提供 `.task` 格式包，也没有相关计划。
- **隐式 Session 的上下文丢失风险。** 便捷方法
  （`LlmInference.generateResponse(String)` /
  `generateResponseAsync(String, ...)`）会在**每次调用时**静默重置其
  "隐式 session"，从而丢失对话上下文。必须显式创建并持续复用同一个
  `LlmInferenceSession` 对象才能维持多轮对话上下文（这是我们在本项目中
  实际遇到并修复的一个真实 bug）。
- **容器格式僵化**，限制了模型可表达的内容（单一合并 TFLite 图、单一
  分词器、极少元数据）——无法容纳推测解码草稿模型、多模态编码器，或更
  丰富的能力元数据。
- **与 `.litertlm` 无官方互通性。** MediaPipe 自身的模型格式检测逻辑将
  `.task`（ZIP）与 `.litertlm` 视为完全独立、互不相关的格式——没有官方
  支持的转换工具；即便自行编写转换工具，也很可能遇到"不支持的算子"
  错误，因为底层 C++ calculator 在该 API 被弃用的同时基本被冻结了。
  更新的模型可能依赖旧引擎 calculator 中并不存在的算子/图结构。

## LiteRT-LM

### 优点

- **持续积极开发、迭代迅速。** 一年内发布了 26+ 个 GitHub 版本，从
  v0.7 迭代到 v0.14：NPU 加速、多模态支持、工具/函数调用、多 token
  预测（推测解码，解码速度提升约 3 倍）、全新的 Swift/JavaScript/Flutter
  API，以及兼容 OpenAI API 的 CLI 服务端。
- **这是所有新模型的落地平台**，包括 Gemma 4、Gemma 3n，以及第三方
  模型系列（Llama、Phi-4、Qwen 等）。
- **更丰富、可扩展的容器格式**（`.litertlm`），可在单个文件中打包多个
  模型/分词器/能力配置，并配有官方工具链（`litert-lm-builder`、
  `litert-lm-peek`）用于构建和查看。
- **跨平台一致性**：Android/JVM（稳定的 Kotlin API）、iOS/macOS
  （Swift）、Web（JavaScript）、Flutter（社区维护）、C++（稳定）、
  Python（稳定）——一套运行时，覆盖多个平台。
- **已在生产环境验证**——据称已用于 Chrome、Chromebook Plus 和
  Pixel Watch 中的端侧 GenAI 功能。
- **更丰富的对话 API**：内置 `Conversation`/`Message` 抽象、系统指令、
  采样器配置，以及结构化的工具调用——比手动拼接 prompt 更符合工程实践。
- **提供针对特定设备编译的变体**（例如 Google Tensor G5、多款
  Qualcomm/Intel 芯片），以获得更好性能，同时也提供可在任意设备运行的
  通用版本。

### 缺点

- **相对较新的依赖，实战验证时间不如 MediaPipe 长**（MediaPipe 已有
  多年历史），不过 LiteRT-LM 已经在 Google 的多款主力产品中上线。
- **与 MediaPipe 不兼容（源码和格式层面均不兼容）。** 将已有的基于
  MediaPipe 的应用迁移过去，需要真正重写推理调用相关代码（不同的
  Gradle 依赖、不同形态的 Kotlin API、需要托管/下载不同的模型文件），
  并非简单替换一个 URL 就能完成。
- **模型文件体积更大、差异更明显。** 我们找到的 Gemma 4 E2B 的
  `.litertlm` 文件体积从约 2GB（通用版/Web 版）到约 3.3GB（NPU 专用
  变体）不等——相比部分小型旧模型体积更紧凑的单一量化 `.task` 文件，
  下载体积更大。
- **部分平台 API 仍处于早期预览阶段**（Swift、JavaScript 为早期预览，
  Flutter 由社区维护）——截至目前，仅 Kotlin/Android、Python 和 C++
  被标记为完全稳定。
- **部分受限/需授权的模型下载时需要身份验证**（例如 Gemma 需要在
  Hugging Face 上接受许可协议），这与 MediaPipe 时代的模型下载要求
  类似——并非 LiteRT-LM 特有的缺点，但依然是实际使用中的一个门槛。

---

## 端侧遥测性能对比分析：Models 实测比较

我们利用自己构建的服务端遥测收集系统，对三款端侧模型在 Android 物理设备上（基于 **CPU 执行算子**）进行了端到端性能测定。

在模型初始化、Prompt 预填充、Token 涌现流式解码和空闲 resting 各阶段，系统以 1 秒/次的超高频采样捕获了实时运行指标：

### A. 遥测性能指标矩阵

| 性能监控指标 | Gemma 3 1B-IT (`MEDIAPIPE`) | Gemma 4 E2B-IT (`LITERTLM`) | Gemma 4 E4B-IT (`LITERTLM`) |
| :--- | :---: | :---: | :---: |
| **磁盘模型体积** | **约 555 MB** | **约 2.41 GB** | **约 3.66 GB** |
| **平均内存占用 (PSS)** | 623.4 MB | 1002.4 MB | 2041.6 MB |
| **峰值内存占用 (PSS)** | **1083.79 MB** | **2258.74 MB** | **4400.81 MB** |
| **平均 Native 堆大小** | 238.4 MB | 459.2 MB | 1016.5 MB |
| **峰值 Native 堆大小** | **508.18 MB** | **1089.68 MB** | **2097.75 MB** |
| **峰值 CPU 负载 (多核扩展)** | 283.78% (3 核并发) | 324.79% (3 核并发) | 322.61% (3 核并发) |
| **峰值流式解码速度** | **13.68 tok/s** | **7.28 tok/s** | **2.87 tok/s** |
| **平均流式解码速度 (活跃)** | **12.20 tok/s** | **5.50 tok/s** | **2.10 tok/s** |
| **遥测样本总数** | 364 个样本 (秒) | 460 个样本 (秒) | 1502 个样本 (秒) |

### B. 核心洞察与设备分级部署建议

1. **内存分级划分 (PSS 进程实际内存 vs. Native 堆分配)**:
   - **Gemma 3 1B-IT**：小巧轻量，极其稳定。其峰值 C++ Native 堆分配仅有 **508.18 MB**，整体 app 进程实际消耗的系统 RAM (PSS) 被完美控制在 **1.1 GB** 之下，即使在 3GB / 4GB 的超低配老年机或入门机上也能做到零闪退运行。
   - **Gemma 4 E2B-IT**：具备极佳的模型内存映射参数。在活跃运行阶段，峰值 Native 堆为 **1089.68 MB**，总 PSS 峰值控制在 **2.26 GB**。普通主流手机（6GB-8GB 运存）完全能流畅承载。
   - **Gemma 4 E4B-IT**：高开销、高性能选项。其峰值 Native 堆在活跃解码阶段霸占 **2.10 GB** 物理运存，驱动总进程 PSS 峰值达到了 **4.40 GB** 的庞大开销。若在生产环境部署 E4B，设备要求底线为 **8GB - 12GB+ 运存的旗舰机型**，否则会被 Android 的 Low Memory Killer Daemon (`lmkd`) 后台静默强制杀死。

2. **多核并行与解码效率**:
   - 在流式解码或 Prompt prefill 的高算力要求阶段，三款引擎均自动调用了物理设备上的 **3 颗 CPU 核心并发运行**（CPU 峰值达到 **280% - 325%** 极高算力扩张）。
   - 实测 CPU 吞吐表明，**Gemma 3 1B-IT** 可提供流畅无比的文字涌现（峰值 **13.68 tok/s**，均值 **12.20 tok/s**），**Gemma 4 E2B-IT** 维持出色的对话流速（峰值 **7.28 tok/s**，均值 **5.50 tok/s**），而 **Gemma 4 E4B-IT** 在 CPU 上则稳定输出文字（峰值 **2.87 tok/s**，均值 **2.10 tok/s**）。

## 建议

- **对于新项目，或希望使用最新模型（Gemma 4 及以后）：** 建议使用
  **LiteRT-LM**。这是 Google 工程投入和新模型发布的主战场。
- **对于已有的、仅需要较旧/较小模型的 MediaPipe 应用**（例如我们已验证
  运行良好的 Gemma 3 1B-IT）：继续使用 MediaPipe `tasks-genai` 是一个
  合理、改动成本更低的选择，但要清楚它不会再获得新模型或新功能支持。
- **混合方案**（在同一应用中提供多种模型选择，分别基于两套技术栈）也是
  可行的，本项目的演示应用目前就是这样做的：同时携带两套依赖/运行时，
  并通过统一的适配器接口封装两套不同的推理调用逻辑，两个模型均已验证可
  正常工作。只有在确实需要同时支持已集成的旧模型和更新的模型时，
  这份额外的复杂度才是值得的；否则，选择单一技术栈会更简单。

## 参考资料

- MediaPipe LLM Inference（Android）：https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android
- LiteRT-LM（Android/Kotlin）：https://developers.google.com/edge/litert-lm/android
- LiteRT-LM GitHub：https://github.com/google-ai-edge/LiteRT-LM
- LiteRT-LM File Builder（`.litertlm` 格式与工具）：https://developers.google.com/edge/litert-lm/file_builder
- Hugging Face `litert-community` 组织（两套技术栈的预构建模型）：https://huggingface.co/litert-community
