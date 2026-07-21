package com.example.myapplication

/** Which on-device inference engine a [ModelOption] should run on. */
enum class Backend {
    /** Legacy, deprecated/maintenance-mode MediaPipe `tasks-genai` LlmInference API. */
    MEDIAPIPE,

    /** Newer LiteRT-LM engine; required for Gemma 4 and other recent model releases. */
    LITERT_LM
}

/** Dependency versions shown in the UI (keep in sync with gradle/libs.versions.toml). */
const val MEDIAPIPE_VERSION = "0.10.35"
const val LITERTLM_VERSION = "0.14.0"

/** Describes one selectable on-device model shown on the main setup screen. */
data class ModelOption(
    val id: String,
    val displayName: String,
    val description: String,
    val backend: Backend,
    val downloadUrl: String,
    val fileName: String,
    val approxSizeMb: Int
) {
    val modelFormat: ModelFormat
        get() = when (backend) {
            Backend.MEDIAPIPE -> ModelFormat.TASK_ZIP
            Backend.LITERT_LM -> ModelFormat.LITERTLM
        }
}

/** The set of models the user can pick from on the main setup screen. */
val AVAILABLE_MODELS = listOf(
    ModelOption(
        id = "gemma3-1b-it",
        displayName = "Gemma 3 1B-IT",
        description = "Lightweight edge model (~550MB, int4 quantized). Runs on the MediaPipe LLM Inference API.",
        backend = Backend.MEDIAPIPE,
        downloadUrl = "https://34.134.65.149.nip.io/gemma3-1b-it-int4.task",
        fileName = "gemma3-1b-it-int4.task",
        approxSizeMb = 555
    ),
    ModelOption(
        id = "gemma4-e2b-it",
        displayName = "Gemma 4 E2B-IT",
        description = "Smallest Gemma 4 variant (~2.4GB). Requires the newer LiteRT-LM engine.",
        backend = Backend.LITERT_LM,
        // Verified: real .litertlm container (magic "LITERTLM", major/minor version 1.5),
        // mirrored from litert-community/gemma-4-E2B-it-litert-lm on Hugging Face.
        downloadUrl = "https://34.134.65.149.nip.io/gemma-4-E2B-it.litertlm",
        fileName = "gemma-4-E2B-it.litertlm",
        approxSizeMb = 2468
    )
)
