# App Overview: On-Device Gemma Chat Demo (Android)

_Last updated: 2026-07-22_

This document describes what this app does, how it's built, and the full
list of real bugs/issues found and fixed while building it — useful context
for anyone picking up this codebase later.

## What the app does

A Kotlin + Jetpack Compose Android app that lets the user pick one of two
on-device LLMs, download it (or reuse a cached copy), and chat with it
entirely on-device (no server calls for inference). It shows a live log of
what it's doing and an always-visible system-resource panel (CPU, Memory PSS, Native Heap, and a 60s real-time line chart) that stays active across all screens.

## Available models

| Model | Backend | Format | Approx. size | Source |
|---|---|---|---|---|
| Gemma 3 1B-IT | MediaPipe `tasks-genai` (`LlmInference`) | `.task` (ZIP) | ~555MB | `litert-community/Gemma3-1B-IT` on Hugging Face (gated; requires accepting the Gemma license) |
| Gemma 4 E2B-IT | LiteRT-LM | `.litertlm` | ~2.41GB | `litert-community/gemma-4-E2B-it-litert-lm` on Hugging Face (gated) |

Both files are mirrored on a private test HTTP server for this demo (see
`Models.kt` for the exact URLs) — that server is not a public/permanent
distribution point; for a real deployment, host the files somewhere
appropriate for your use case.

## Architecture

- **`Models.kt`** — `ModelOption` data class + `AVAILABLE_MODELS` registry.
  Each option declares its backend, download URL, local file name, and
  expected container format (`ModelFormat.TASK_ZIP` or `ModelFormat.LITERTLM`).
- **`ModelDownloader.kt`** — OkHttp-based downloader with progress reporting
  and format validation. Validates `.task` files via `ZipFile` open-success
  (not a brittle offset-0 magic-byte check — see "Bugs fixed" below) and
  `.litertlm` files via the 8-byte ASCII magic `"LITERTLM"`.
- **`InferenceModel.kt`** — wraps MediaPipe's `LlmInference` +
  `LlmInferenceSession`. Keeps one persistent `LlmInferenceSession` alive for
  the whole chat so conversation context is preserved across turns.
- **`LiteRtLmInferenceModel.kt`** — wraps LiteRT-LM's `Engine` + `Conversation`.
  Also keeps one persistent `Conversation` alive across turns.
- **`ChatViewModel.kt`** — orchestrates everything: downloading, an
  `EngineAdapter` sealed class that wraps either `InferenceModel` or
  `LiteRtLmInferenceModel` behind one shape (so the rest of the app doesn't
  care which backend is active), the action log, and chat state.
- **`MainActivity.kt`** — Compose UI: model picker, download/init progress
  screens, chat screen (with persistent `SystemStatusPane` + scrollable `LogPanel`
  at the bottom), error screen with retry/reset actions.

## System Resource Monitoring: Memory PSS & Native Heap

The app includes a real-time system status pane (`SystemStatusPane` in `MainActivity.kt`) that monitors CPU usage and memory metrics continuously, displaying live numerical values alongside a 60-second real-time line chart (`SystemStatusChart`). This pane is positioned at the top level of the UI hierarchy, making it persistent across all screens (Model Setup, Downloading, Initializing, Chatting, and Error screens) so that memory allocation during model downloading, engine initialization, and token generation can be observed seamlessly.

### Memory PSS (Proportional Set Size)

- **Definition**: PSS is the primary metric used by the Android OS to measure an application process's true RAM footprint. It consists of the process's private memory (Private Clean + Private Dirty) plus its proportional share of shared memory (Shared Clean + Shared Dirty divided evenly among all processes sharing those RAM pages, such as system frameworks, ART runtime libraries, and shared shared-object `.so` files).
- **Why RSS is insufficient**: Traditional Resident Set Size (RSS) double-counts shared memory pages across processes, overestimating RAM usage. PSS provides an accurate, non-overlapping representation of how much system RAM this process is directly responsible for holding onto.
- **How it's measured in Android**: `Debug.MemoryInfo().totalPss` (retrieved via `Debug.getMemoryInfo(memInfo)` in KB, converted to MB by dividing by `1024f`).
- **Role in On-Device LLM Execution**: PSS reflects the total memory impact of the entire app process—including the Kotlin/Java ART runtime, Jetpack Compose UI layout buffers, system graphics drivers, mmapped file buffers, and the native C++ inference engine footprint.

### Native Heap

- **Definition**: Native Heap represents memory allocated dynamically on the C/C++ heap via native memory allocators (`malloc`, `calloc`, `posix_memalign`, `new`, etc., managed by Android's `jemalloc` / `scudo`). It operates outside the ART (Android Runtime) garbage-collected Java heap.
- **Why it is critical for On-Device LLMs**: On-device LLM engines (MediaPipe `LlmInference`, LiteRT-LM `Engine`, XNNPACK, OpenCL, and Vulkan acceleration layers) are C++ native runtimes. They allocate weight tensors, context workspace buffers, and Key-Value (KV) caches directly on the native heap or in native memory allocations.
- **How it's measured in Android**: `Debug.getNativeHeapAllocatedSize()` (retrieved in bytes, converted to MB by dividing by `1024f * 1024f`).
- **Role in On-Device LLM Execution**: Native Heap is the direct, real-time indicator of the LLM model's C++ engine memory footprint. When an LLM initializes or generates response tokens, Native Heap usage spikes as model weights and KV-cache states are allocated in native memory. Monitoring Native Heap allows developers to observe LLM memory overhead separately from Java runtime allocations.

## Bugs found and fixed during development

1. **Wrong file format served initially.** The original model URLs served
   raw TFLite flatbuffers (magic `TFL3`) mislabeled with `.bin`/`.task`/`.web.task`
   extensions. MediaPipe's `tasks-genai` (as of 0.10.35) only accepts genuine
   `.task` ZIP bundles — raw flatbuffers throw a native "not a valid ZIP
   archive" error. Fixed by sourcing/verifying a real `.task` bundle
   (`gemma3-1b-it-int4.task` from `litert-community/Gemma3-1B-IT`) via
   magic-byte inspection before wiring it in.
2. **Overly strict ZIP validation.** Our own downloader required the ZIP
   local-file-header signature (`PK\x03\x04`) at byte offset 0, but real
   `.task` bundles can have a few leading padding bytes before it. Fixed by
   validating with `ZipFile(file).use {}` (which locates the archive via the
   End-Of-Central-Directory record, like any standard zip reader) instead of
   a raw byte-offset check.
3. **No cache reuse.** Every "Start" press re-downloaded the model even when
   a valid copy was already on disk. Fixed with a check-existing-file-first
   flow, with clear log messages so the behavior is visible to the user.
4. **"Retry Download" didn't actually retry.** It reused the same
   cached-but-broken file forever. Fixed by adding a `forceRedownload` flag.
5. **Conversation context lost after the first message (MediaPipe).**
   `LlmInference.generateResponseAsync(String, ...)` resets its "implicit
   session" (and therefore the KV-cache/context) on *every* call. Fixed by
   creating one `LlmInferenceSession` explicitly and reusing it for every
   turn via `addQueryChunk()` + `generateResponseAsync()`.
6. **No visible scrollbar in the chat list or log panel.** Compose's
   `LazyColumn` has no built-in scroll indicator (unlike a traditional
   View-based scrolling container). Fixed with a custom `drawWithContent`
   modifier that draws a fading thumb based on `LazyListState.layoutInfo`.
7. **Auto-scroll didn't keep the streaming output visible.**
   `animateScrollToItem()` aligns the target item to the *top* of the
   viewport, so a long, still-growing response bubble kept its newest (bottom)
   text scrolled out of view; rapid per-token updates also queued up
   overlapping animations. Fixed with an immediate (non-animated)
   `scrollToItem(index, scrollOffset = Int.MAX_VALUE)`, which Compose clamps
   to the actual content size.
8. **No way to leave the chat and free memory without losing the download.**
   Added a back button (visible only while chatting) that releases the
   active engine's native resources but keeps the downloaded file cached.
9. **No visibility into what the app is doing.** Added a persistent,
   timestamped action log (download/verify/init progress, errors) shown in a
   scrollable panel at the bottom of the screen.
10. **No visibility into resource cost.** Added an always-visible system-status
    panel (`SystemStatusPane`) featuring numerical metrics (CPU % via `/proc/self/stat`
    deltas, Memory PSS via `Debug.getMemoryInfo()`, and Native Heap via
    `Debug.getNativeHeapAllocatedSize()`) alongside a 60-second real-time Compose Canvas
    line chart (`SystemStatusChart`). Moved the pane to the top layout level so it
    remains active and continuously sampled across all screen transitions (Model Setup,
    Downloading, Initializing, Chatting, and Error screens).
11. **Destructive buttons ("Clear Internal Storage" / "Clear Storage &
    Reset") had red text but no border**, since they were `TextButton`s
    (borderless by design). Switched to `OutlinedButton` with an explicit red
    `BorderStroke`.
12. **Gemma 4 has no MediaPipe `.task` bundle at all.** Every Gemma 4 size
    (E2B/E4B/12B/26B-A4B/31B) ships only as `.litertlm`. Building a converter
    from `.litertlm` to `.task` was investigated and found technically
    infeasible (see `mediapipe_vs_litertlm_en.md` for the full container
    format comparison) — the two engines' native op sets have diverged.
    Resolved by adding LiteRT-LM as a second, coexisting backend instead.
13. **LiteRT-LM Kotlin API ground-truth required decompiling the AAR.** The
    public docs' example code (`message.text`) doesn't match the actual
    `litertlm-android:0.14.0` API — `Message` has no `.text` property. The
    real way to extract text is
    `message.contents.contents.filterIsInstance<Content.Text>()`. Found by
    running `javap` against the classes extracted from the AAR in the Gradle
    cache.
14. **Verified MediaPipe and LiteRT-LM coexist in one APK.** Adding both
    dependencies causes no duplicate-class or native-library-name conflicts
    (`liblitertlm_jni.so` vs `libllm_inference_engine_jni.so`); confirmed via
    a full `assembleDebug`. APK size grew from ~129MB (MediaPipe only) to
    ~176MB (both).

## Building and running

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The Android SDK path is configured in `local.properties` (`sdk.dir`).

## Known limitations / possible follow-ups

- LiteRT-LM defaults to the CPU backend for broad compatibility; GPU/NPU
  backends need extra manifest entries (`<uses-native-library>`) and/or
  device-specific compiled `.litertlm` variants.
- The exact streaming semantics of `MessageCallback.onMessage()` (whether
  each callback delivers a delta chunk or the cumulative message so far)
  were inferred from the API shape, not confirmed against real generation
  output — worth double-checking once tested live on a device with a real
  prompt.
- Model files are hosted on a private test server for this demo; not
  suitable as a public distribution point.

## Related documents

- [mediapipe_vs_litertlm_en.md](mediapipe_vs_litertlm_en.md) /
  [mediapipe_vs_litertlm_zh.md](mediapipe_vs_litertlm_zh.md) — deep dive into
  the two frameworks, container formats, and a full pros/cons comparison.
