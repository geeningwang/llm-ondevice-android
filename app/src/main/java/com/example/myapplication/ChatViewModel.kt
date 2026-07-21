package com.example.myapplication

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class ChatMessage(
    val text: String,
    val isUser: Boolean
)

sealed class ModelState {
    object Idle : ModelState()
    data class Downloading(val progress: Float) : ModelState()
    object Initializing : ModelState()
    object Ready : ModelState()
    data class Error(val message: String) : ModelState()
}

/**
 * Adapts either [InferenceModel] (MediaPipe) or [LiteRtLmInferenceModel] (LiteRT-LM) behind one
 * shape, so [ChatViewModel] doesn't need to care which engine is actually backing the currently
 * selected model.
 */
private sealed class EngineAdapter {
    abstract val partialResults: SharedFlow<Pair<String, Boolean>>
    abstract fun initialize(path: String): Result<Unit>
    abstract fun generateResponse(prompt: String)
    abstract fun close()

    class MediaPipeAdapter(context: Application) : EngineAdapter() {
        private val model = InferenceModel(context)
        override val partialResults get() = model.partialResults
        override fun initialize(path: String) = model.initialize(path)
        override fun generateResponse(prompt: String) = model.generateResponse(prompt)
        override fun close() = model.close()
    }

    class LiteRtLmAdapter(context: Application) : EngineAdapter() {
        private val model = LiteRtLmInferenceModel(context)
        override val partialResults get() = model.partialResults
        override fun initialize(path: String) = model.initialize(path)
        override fun generateResponse(prompt: String) = model.generateResponse(prompt)
        override fun close() = model.close()
    }

    companion object {
        fun create(backend: Backend, context: Application): EngineAdapter = when (backend) {
            Backend.MEDIAPIPE -> MediaPipeAdapter(context)
            Backend.LITERT_LM -> LiteRtLmAdapter(context)
        }
    }
}

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val modelDownloader = ModelDownloader()

    private var engineAdapter: EngineAdapter? = null
    private var partialResultsJob: Job? = null

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _modelState = MutableStateFlow<ModelState>(ModelState.Idle)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    val availableModels = AVAILABLE_MODELS

    private val _selectedModel = MutableStateFlow(AVAILABLE_MODELS.first())
    val selectedModel: StateFlow<ModelOption> = _selectedModel.asStateFlow()

    private var currentResponse = StringBuilder()

    private fun log(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        // Cap the log history so it doesn't grow unbounded over a long-running session.
        _logs.value = (_logs.value + "[$timestamp] $message").takeLast(200)
    }

    /** Only allowed while on the main setup screen (Idle state), before a download/init starts. */
    fun selectModel(option: ModelOption) {
        if (_modelState.value == ModelState.Idle) {
            _selectedModel.value = option
            log("Selected model: ${option.displayName}")
        }
    }

    /**
     * Starts the currently selected model: 1) checks whether a valid model file is already
     * downloaded, 2) if so, skips the network download entirely and goes straight to
     * initialization, 3) otherwise downloads, verifies, and then initializes.
     */
    fun downloadAndInit(forceRedownload: Boolean = false) {
        val option = _selectedModel.value
        val modelFile = File(getApplication<Application>().filesDir, option.fileName)

        viewModelScope.launch {
            log("Checking for an existing model at ${modelFile.absolutePath}...")
            if (!forceRedownload && modelFile.exists() && modelFile.isFile && modelFile.length() > 100 * 1024 * 1024) {
                val sizeMb = modelFile.length() / (1024 * 1024)
                log("Found existing model ($sizeMb MB). Skipping download and starting chat.")
                initModel(option, modelFile.absolutePath)
                return@launch
            }

            if (forceRedownload && modelFile.exists()) {
                log("Re-download requested. Removing the existing file first.")
            } else {
                log("No valid cached model found.")
            }
            // Delete any existing/invalid file before downloading a fresh copy
            if (modelFile.exists()) modelFile.deleteRecursively()

            log("Downloading ${option.displayName} from ${option.downloadUrl}")
            var lastLoggedDecile = -1
            modelDownloader.downloadModel(option.downloadUrl, modelFile, option.modelFormat).collect { status ->
                when (status) {
                    is DownloadStatus.Progress -> {
                        _modelState.value = ModelState.Downloading(status.percentage)
                        val decile = (status.percentage * 10).toInt()
                        if (decile != lastLoggedDecile) {
                            lastLoggedDecile = decile
                            log("Downloading... ${(status.percentage * 100).toInt()}%")
                        }
                    }
                    is DownloadStatus.Success -> {
                        log("Download complete. Verified as a valid ${if (option.backend == Backend.LITERT_LM) "LiteRT-LM .litertlm container" else "MediaPipe .task bundle"}.")
                        initModel(option, status.file.absolutePath)
                    }
                    is DownloadStatus.Error -> {
                        log("Download failed: ${status.message}")
                        _modelState.value = ModelState.Error(status.message)
                    }
                }
            }
        }
    }

    fun initModel(option: ModelOption, path: String) {
        viewModelScope.launch {
            val engineName = if (option.backend == Backend.LITERT_LM) "LiteRT-LM" else "MediaPipe LlmInference"
            log("Initializing $engineName engine from $path...")
            _modelState.value = ModelState.Initializing

            // Tear down any previously active engine (e.g. switching models) before creating a
            // new one.
            partialResultsJob?.cancel()
            engineAdapter?.close()

            val adapter = EngineAdapter.create(option.backend, getApplication())
            engineAdapter = adapter
            partialResultsJob = viewModelScope.launch {
                adapter.partialResults.collect { (text, done) ->
                    currentResponse.append(text)
                    updateLastAiMessage(currentResponse.toString())
                    if (done) {
                        _isGenerating.value = false
                        log("Response complete.")
                        currentResponse.clear()
                    }
                }
            }

            val result = adapter.initialize(path)
            if (result.isSuccess) {
                log("Model initialized successfully. Ready to chat.")
                _modelState.value = ModelState.Ready
            } else {
                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                log("Initialization failed: $error")
                _modelState.value = ModelState.Error(error)
                // If it's a corrupted/incompatible file, delete it so the user can retry with a
                // fresh download instead of repeatedly failing to load the same broken file.
                if (error.contains("zip archive", ignoreCase = true) ||
                    error.contains("corrupted", ignoreCase = true) ||
                    error.contains("not a valid", ignoreCase = true) ||
                    error.contains("litertlm", ignoreCase = true)
                ) {
                    log("Removing incompatible/corrupted model file so it can be re-downloaded.")
                    File(path).delete()
                }
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || _isGenerating.value) return

        _messages.value += ChatMessage(text, true)
        _messages.value += ChatMessage("...", false) // Placeholder for AI response
        _isGenerating.value = true
        currentResponse.clear()
        log("Generating response...")

        engineAdapter?.generateResponse(text)
    }

    /**
     * Leaves the chat and returns to the main setup screen. This releases the LLM engine's
     * native resources (freeing the memory it was using) without deleting the downloaded model
     * file, so starting again from the main screen re-initializes quickly without re-downloading.
     */
    fun exitChat() {
        log("Leaving chat. Releasing model resources.")
        partialResultsJob?.cancel()
        partialResultsJob = null
        engineAdapter?.close()
        engineAdapter = null
        _messages.value = emptyList()
        _modelState.value = ModelState.Idle
    }

    fun clearStorage() {
        val modelFile = File(getApplication<Application>().filesDir, _selectedModel.value.fileName)
        if (modelFile.exists()) {
            modelFile.delete()
        }
        log("Cleared local model file and reset app state.")
        _modelState.value = ModelState.Idle
        _messages.value = emptyList()
    }

    private fun updateLastAiMessage(text: String) {
        val currentMessages = _messages.value.toMutableList()
        if (currentMessages.isNotEmpty() && !currentMessages.last().isUser) {
            currentMessages[currentMessages.size - 1] = ChatMessage(text, false)
            _messages.value = currentMessages
        }
    }

    override fun onCleared() {
        super.onCleared()
        engineAdapter?.close()
    }
}
