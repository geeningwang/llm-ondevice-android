package com.example.myapplication

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Manages buffering and uploading client-side system status metric samples to the telemetry web API.
 * Samples are buffered in memory and uploaded in batches to minimize network overhead and save battery.
 */
object TelemetryManager {
    private const val TAG = "TelemetryManager"
    private const val BATCH_SIZE_THRESHOLD = 30 // Upload when 30 samples are accumulated (30 seconds of data)

    // Base URL of the telemetry API. Easily configurable by the user or build config.
    var apiBaseUrl: String = "https://34.134.65.149.nip.io/"

    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO)

    // In-memory buffer for status samples
    private val buffer = mutableListOf<TelemetrySample>()

    // Current active session metadata
    private var currentSessionId: String = ""
    private var currentModelId: String = ""

    data class TelemetrySample(
        val timestamp: String,
        val cpuPercent: Float,
        val totalPssMb: Float,
        val nativeHeapMb: Float,
        val tokensPerSecond: Float,
        val gpuPercent: Float?,
        val thermalStatus: String
    )

    /**
     * Updates the current active session context. If the session ID changes, any pending
     * samples in the buffer from the previous session are immediately uploaded (flushed) before resetting.
     */
    @Synchronized
    fun updateSessionContext(sessionId: String, modelId: String) {
        if (sessionId != currentSessionId && currentSessionId.isNotEmpty()) {
            Log.d(TAG, "Session ID changed. Flushing old buffer for session: $currentSessionId")
            flush()
        }
        currentSessionId = sessionId
        currentModelId = modelId
    }

    /**
     * Adds a system status metric sample to the local buffer.
     * Triggers an automatic batch upload if the buffer size exceeds the threshold.
     */
    @Synchronized
    fun addSample(
        cpuPercent: Float,
        totalPssMb: Float,
        nativeHeapMb: Float,
        tokensPerSecond: Float,
        gpuPercent: Float?,
        thermalStatus: String
    ) {
        if (currentSessionId.isEmpty()) {
            // No active session yet (e.g., app is Idle or Downloading before first initialization)
            return
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val timestampIso = sdf.format(Date())

        val sample = TelemetrySample(
            timestamp = timestampIso,
            cpuPercent = cpuPercent,
            totalPssMb = totalPssMb,
            nativeHeapMb = nativeHeapMb,
            tokensPerSecond = tokensPerSecond,
            gpuPercent = gpuPercent,
            thermalStatus = thermalStatus
        )
        buffer.add(sample)

        if (buffer.size >= BATCH_SIZE_THRESHOLD) {
            Log.d(TAG, "Buffer threshold reached (${buffer.size} samples). Triggering batch upload.")
            flush()
        }
    }

    /**
     * Flushes the buffer by executing a background coroutine to upload all buffered samples to the server.
     */
    @Synchronized
    fun flush() {
        if (buffer.isEmpty() || currentSessionId.isEmpty()) return

        val samplesToUpload = ArrayList(buffer)
        buffer.clear()

        val sessionId = currentSessionId
        val modelId = currentModelId

        scope.launch {
            uploadBatch(sessionId, modelId, samplesToUpload)
        }
    }

    private fun uploadBatch(sessionId: String, modelId: String, samples: List<TelemetrySample>) {
        try {
            val jsonBody = JSONObject().apply {
                put("session_id", sessionId)
                put("model_id", modelId)

                val jsonSamples = JSONArray()
                samples.forEach { sample ->
                    val jsonSample = JSONObject().apply {
                        put("timestamp", sample.timestamp)
                        put("cpu_percent", sample.cpuPercent.toDouble())
                        put("total_pss_mb", sample.totalPssMb.toDouble())
                        put("native_heap_mb", sample.nativeHeapMb.toDouble())
                        put("tokens_per_second", sample.tokensPerSecond.toDouble())
                        if (sample.gpuPercent != null) {
                            put("gpu_percent", sample.gpuPercent.toDouble())
                        } else {
                            put("gpu_percent", JSONObject.NULL)
                        }
                        put("thermal_status", sample.thermalStatus)
                    }
                    jsonSamples.put(jsonSample)
                }
                put("samples", jsonSamples)
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(apiBaseUrl.trimEnd('/') + "/api/telemetry/status")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i(TAG, "Telemetry batch upload successful for session: $sessionId. Uploaded ${samples.size} samples.")
                } else {
                    Log.e(TAG, "Telemetry batch upload failed (HTTP ${response.code}): ${response.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during telemetry upload: ${e.message}", e)
        }
    }
}
