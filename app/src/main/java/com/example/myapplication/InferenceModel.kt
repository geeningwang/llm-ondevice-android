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
    private var session: LlmInferenceSession? = null

    var isGpuAccelerated: Boolean = false
        private set

    private val _partialResults = MutableSharedFlow<Pair<String, Boolean>>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val partialResults: SharedFlow<Pair<String, Boolean>> = _partialResults.asSharedFlow()

    fun initialize(modelPath: String, useGpu: Boolean): Result<Unit> {
        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            return Result.failure(Exception("Model file not found at $modelPath"))
        }

        if (modelFile.length() < 100 * 1024 * 1024) {
            return Result.failure(Exception("Model file is too small (${modelFile.length()} bytes). It might be corrupted."))
        }

        return try {
            val builder = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(1024)

            if (useGpu) {
                builder.setPreferredBackend(LlmInference.Backend.GPU)
            } else {
                builder.setPreferredBackend(LlmInference.Backend.CPU)
            }

            val engine = LlmInference.createFromOptions(context, builder.build())
            llmInference = engine
            session = LlmInferenceSession.createFromOptions(
                engine,
                LlmInferenceSession.LlmInferenceSessionOptions.builder().build()
            )
            isGpuAccelerated = useGpu
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(if (t is Exception) t else Exception(t.message ?: t.toString()))
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
        isGpuAccelerated = false
    }
}
