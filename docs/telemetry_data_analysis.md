# On-Device Gemma Telemetry Data Analysis: Models Comparison

_Last updated: 2026-07-23_

This document summarizes the performance and resource consumption data collected end-to-end using the server-side Telemetry API during active on-device sessions. 

The evaluation compared three models (**Gemma 3 1B-IT**, **Gemma 4 E2B-IT**, and **Gemma 4 E4B-IT**) running on the **CPU delegate** of an Android client. Each session recorded high-frequency, once-per-second system status metrics during initialization, prompt prefill, token decoding, and idle states.

---

## 1. Consolidated Performance Metrics

| Performance Metric | Gemma 3 1B-IT (`MEDIAPIPE`) | Gemma 4 E2B-IT (`LITERTLM`) | Gemma 4 E4B-IT (`LITERTLM`) |
| :--- | :---: | :---: | :---: |
| **Model Size on Disk** | **~555 MB** | **~2.41 GB** | **~3.66 GB** |
| **Average Memory Footprint (PSS)** | 623.4 MB | 1123.6 MB | 4248.9 MB |
| **Peak Memory Footprint (PSS)** | **1083.79 MB** | **1185.34 MB** | **4342.78 MB** |
| **Average Native Heap Size** | 238.4 MB | 792.1 MB | 2061.2 MB |
| **Peak Native Heap Size** | **508.18 MB** | **795.64 MB** | **2095.61 MB** |
| **Peak CPU Load (Core Scaling)** | 283.78% (3 Cores) | 305.89% (3 Cores) | 302.06% (3 Cores) |
| **Peak Generation Speed** | **13.68 tok/s** | **7.28 tok/s** | **5.48 tok/s** |
| **Telemetry Samples Logged** | 364 samples (seconds) | 460 samples (seconds) | 1502 samples (seconds) |

---

## 2. Dynamic Lifecycle & Architectural Analysis

### A. Memory Footprint (PSS vs. Native Heap)
- **Gemma 3 1B-IT**: Highly lightweight. Peak native heap allocations reach only **508.18 MB**, keeping total app process RAM (PSS) below **1.1 GB**. It is fully compatible with any lower-end Android device (4GB physical RAM limit).
- **Gemma 4 E2B-IT**: Demonstrates extreme memory efficiency. Despite being ~2.41 GB on disk, LiteRT-LM's optimized memory mapping (`mmap`) keeps the active Native Heap footprint capped at **795.64 MB** and total RAM (PSS) at **1.18 GB**.
- **Gemma 4 E4B-IT**: High-overhead candidate. Peak Native Heap allocations reach **2.09 GB** with total process memory (PSS) peaking at **4.34 GB**. Running E4B on-device requires flagship smartphones equipped with at least **8GB - 12GB of physical RAM** to avoid background process termination by Android's Low Memory Killer Daemon (`lmkd`).

### B. Execution Throughput (Tokens per Second)
- **Gemma 3 1B-IT**: Reaches **13.68 tok/s**, yielding a fluid real-time streaming effect in the chat bubble.
- **Gemma 4 E2B-IT**: Reaches **7.28 tok/s**. It is slower due to deeper transformer scaling, but maintains a very readable conversational typing speed.
- **Gemma 4 E4B-IT**: Reaches **5.48 tok/s** on CPU. Decent speed but demonstrates hardware processing limits when run entirely on standard mobile CPU delegates.

### C. CPU Scaling & Thermal Load
- All three models peaked at **280% - 306% CPU load** during active prompt processing and token generation. This indicates that both MediaPipe and LiteRT-LM successfully partition tensor operations across **3 physical CPU cores** simultaneously.
- Thermal levels remained `Normal` during these short-burst tests, but continuous generations of E4B on CPU are expected to trigger device heat accumulation and subsequent thermal throttling.

---

## 3. Sizing & Target Device Recommendations

1. **Entry-Level Devices (3GB - 4GB RAM)**:
   - **Recommendation**: **Gemma 3 1B-IT** (CPU target).
   - **Rationale**: Minimal RAM usage ($<1.1\text{ GB}$ total process impact) ensures absolute stability, paired with fast, responsive generation speeds ($13.68\text{ tok/s}$).

2. **Standard Devices (6GB - 8GB RAM)**:
   - **Recommendation**: **Gemma 4 E2B-IT** (CPU/GPU target).
   - **Rationale**: Delivers superior vocabulary, reasoning, and context retention compared to Gemma 3, while maintaining a very stable and well-contained memory profile ($1.18\text{ GB}$ total process RAM).

3. **Premium Flagships (12GB - 16GB+ RAM)**:
   - **Recommendation**: **Gemma 4 E4B-IT** (GPU target).
   - **Rationale**: Ideal for complex tasks requiring high reasoning accuracy, but should only be enabled on premium devices to prevent low-memory crashes under concurrent application loads.
