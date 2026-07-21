package com.example.myapplication

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
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

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        // MediaPipe tasks-genai 0.10.35 requires a .task bundle (a ZIP archive containing the
        // tflite model + tokenizer + metadata). Raw .bin/.tflite flatbuffers are no longer
        // accepted directly by LlmInference.createFromOptions().
        private const val GEMMA_URL = "https://34.134.65.149.nip.io/gemma3-1b-it-int4.task"
        private const val MODEL_FILE_NAME = "gemma3-1b-it-int4.task"
    }

    private val inferenceModel = InferenceModel(application)
    private val modelDownloader = ModelDownloader()
    
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _modelState = MutableStateFlow<ModelState>(ModelState.Idle)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private var currentResponse = StringBuilder()

    private fun log(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        // Cap the log history so it doesn't grow unbounded over a long-running session.
        _logs.value = (_logs.value + "[$timestamp] $message").takeLast(200)
    }

    init {
        viewModelScope.launch {
            inferenceModel.partialResults.collect { (text, done) ->
                currentResponse.append(text)
                updateLastAiMessage(currentResponse.toString())
                if (done) {
                    _isGenerating.value = false
                    log("Response complete.")
                    currentResponse.clear()
                }
            }
        }
    }

    /**
     * Starts the model: 1) checks whether a valid model file is already downloaded, 2) if so,
     * skips the network download entirely and goes straight to initialization, 3) otherwise
     * downloads, verifies, and then initializes.
     */
    fun downloadAndInit(forceRedownload: Boolean = false) {
        val modelFile = File(getApplication<Application>().filesDir, MODEL_FILE_NAME)
        
        viewModelScope.launch {
            log("Checking for an existing model at ${modelFile.absolutePath}...")
            if (!forceRedownload && modelFile.exists() && modelFile.isFile && modelFile.length() > 100 * 1024 * 1024) {
                val sizeMb = modelFile.length() / (1024 * 1024)
                log("Found existing model ($sizeMb MB). Skipping download and starting chat.")
                initModel(modelFile.absolutePath)
                return@launch
            }

            if (forceRedownload && modelFile.exists()) {
                log("Re-download requested. Removing the existing file first.")
            } else {
                log("No valid cached model found.")
            }
            // Delete any existing/invalid file before downloading a fresh copy
            if (modelFile.exists()) modelFile.deleteRecursively()

            log("Downloading model from $GEMMA_URL")
            var lastLoggedDecile = -1
            modelDownloader.downloadModel(GEMMA_URL, modelFile).collect { status ->
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
                        log("Download complete. Verified as a valid MediaPipe .task bundle.")
                        initModel(status.file.absolutePath)
                    }
                    is DownloadStatus.Error -> {
                        log("Download failed: ${status.message}")
                        _modelState.value = ModelState.Error(status.message)
                    }
                }
            }
        }
    }

    fun initModel(path: String) {
        viewModelScope.launch {
            log("Initializing MediaPipe LlmInference engine from $path...")
            _modelState.value = ModelState.Initializing
            val result = inferenceModel.initialize(path)
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
                    error.contains("not a valid", ignoreCase = true)
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
        
        inferenceModel.generateResponse(text)
    }

    /**
     * Leaves the chat and returns to the main setup screen. This releases the LLM engine's
     * native resources (freeing the memory it was using) without deleting the downloaded model
     * file, so starting again from the main screen re-initializes quickly without re-downloading.
     */
    fun exitChat() {
        log("Leaving chat. Releasing model resources.")
        inferenceModel.close()
        _messages.value = emptyList()
        _modelState.value = ModelState.Idle
    }

    fun clearStorage() {
        val modelFile = File(getApplication<Application>().filesDir, MODEL_FILE_NAME)
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
        inferenceModel.close()
    }
}
