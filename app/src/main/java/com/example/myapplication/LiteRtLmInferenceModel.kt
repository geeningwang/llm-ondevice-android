package com.example.myapplication

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

/**
 * Extracts the plain text out of a [Message]. A [Message]'s content is a list of [Content] items
 * (text, images, audio, tool responses, etc.) rather than a single string field, so text-only
 * chat needs to filter for [Content.Text] entries and concatenate them.
 */
private fun Message.extractText(): String =
    contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

/**
 * Wraps the LiteRT-LM Kotlin API (`com.google.ai.edge.litertlm`) behind the same simple
 * initialize()/generateResponse()/close() shape as [InferenceModel] (the MediaPipe wrapper), so
 * [ChatViewModel] can switch between backends without caring which engine is actually running.
 *
 * Unlike MediaPipe's `LlmInference` convenience methods, a LiteRT-LM [Conversation] is inherently
 * stateful: as long as the same [Conversation] instance is reused for every turn (which this
 * class does), conversation context is preserved automatically -- no extra bookkeeping needed.
 */
class LiteRtLmInferenceModel(private val context: Context) {
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    private val _partialResults = MutableSharedFlow<Pair<String, Boolean>>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val partialResults: SharedFlow<Pair<String, Boolean>> = _partialResults.asSharedFlow()

    fun initialize(modelPath: String): Result<Unit> {
        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            return Result.failure(Exception("Model file not found at $modelPath"))
        }

        if (modelFile.length() < 100 * 1024 * 1024) {
            return Result.failure(Exception("Model file is too small (${modelFile.length()} bytes). It might be corrupted."))
        }

        return try {
            // Defaulting to the CPU backend for broad compatibility. The GPU backend needs extra
            // <uses-native-library> manifest entries and is not guaranteed to be present on every
            // device; NPU needs a device-specific compiled .litertlm variant.
            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                cacheDir = context.cacheDir.path
            )
            val newEngine = Engine(engineConfig)
            newEngine.initialize()
            engine = newEngine
            conversation = newEngine.createConversation()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun generateResponse(prompt: String) {
        val activeConversation = conversation ?: return
        activeConversation.sendMessageAsync(
            prompt,
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    _partialResults.tryEmit(message.extractText() to false)
                }

                override fun onDone() {
                    _partialResults.tryEmit("" to true)
                }

                override fun onError(throwable: Throwable) {
                    _partialResults.tryEmit("\n[Error: ${throwable.message}]" to true)
                }
            }
        )
    }

    fun close() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
    }
}
