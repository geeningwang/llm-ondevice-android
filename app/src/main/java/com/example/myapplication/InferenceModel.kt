package com.example.myapplication

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

class InferenceModel(private val context: Context) {
    private var llmInference: LlmInference? = null

    // A single persistent session is used across all turns of the conversation. MediaPipe's
    // LlmInference.generateResponseAsync(String) convenience method resets its "implicit
    // session" -- and therefore the conversation context/KV-cache -- on every call, which is why
    // a naive usage of it loses context after the first message. Using an explicit
    // LlmInferenceSession and calling addQueryChunk()/generateResponseAsync() on the SAME session
    // object for every turn keeps prior turns (both user prompts and model responses) in context.
    private var session: LlmInferenceSession? = null

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
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(1024)
                .build()

            val engine = LlmInference.createFromOptions(context, options)
            llmInference = engine
            session = LlmInferenceSession.createFromOptions(
                engine,
                LlmInferenceSession.LlmInferenceSessionOptions.builder().build()
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun generateResponse(prompt: String) {
        val activeSession = session ?: return
        activeSession.addQueryChunk(prompt)
        activeSession.generateResponseAsync { partialResult, done ->
            _partialResults.tryEmit(partialResult to done)
        }
    }

    fun close() {
        session?.close()
        session = null
        llmInference?.close()
        llmInference = null
    }
}
