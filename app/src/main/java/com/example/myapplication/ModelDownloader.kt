package com.example.myapplication

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipFile

sealed class DownloadStatus {
    data class Progress(val percentage: Float) : DownloadStatus()
    data class Success(val file: File) : DownloadStatus()
    data class Error(val message: String) : DownloadStatus()
}

/** The container format we expect the downloaded model file to be. */
enum class ModelFormat {
    /** MediaPipe tasks-genai .task bundle: a ZIP archive. */
    TASK_ZIP,

    /** LiteRT-LM .litertlm container: starts with the 8-byte ASCII magic "LITERTLM". */
    LITERTLM
}

class ModelDownloader {
    private val client = OkHttpClient()

    fun downloadModel(
        url: String,
        outputFile: File,
        expectedFormat: ModelFormat = ModelFormat.TASK_ZIP
    ): Flow<DownloadStatus> = flow {
        try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val code = response.code
                val message = response.message
                emit(DownloadStatus.Error("Failed to download model (HTTP $code): $message. This link might require authentication or is no longer available."))
                return@flow
            }

            val contentType = response.header("Content-Type")
            if (contentType?.contains("text/html") == true) {
                emit(DownloadStatus.Error("Received HTML instead of model weights. The link might be gated or invalid."))
                return@flow
            }

            val body = response.body
            if (body == null) {
                emit(DownloadStatus.Error("Response body is null"))
                return@flow
            }

            val totalBytes = body.contentLength()
            if (totalBytes > 0 && totalBytes < 100 * 1024 * 1024) {
                emit(DownloadStatus.Error("Downloaded file size ($totalBytes) is too small for an LLM model. Check the source link."))
                return@flow
            }

            var downloadedBytes = 0L
            val buffer = ByteArray(8192)

            body.byteStream().use { input ->
                FileOutputStream(outputFile).use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            emit(DownloadStatus.Progress(downloadedBytes.toFloat() / totalBytes))
                        }
                    }
                }
            }

            // MediaPipe's LlmInference (tasks-genai 0.10.35) requires a `.task` bundle, which is
            // a ZIP archive containing the tflite model, tokenizer, and metadata. Note that valid
            // .task bundles are NOT guaranteed to start with the ZIP local-file-header signature
            // ("PK\u0003\u0004") at byte offset 0 -- some bundlers emit a few leading padding/
            // alignment bytes before the first entry. Standard zip readers (including Java's
            // ZipFile) locate the archive via the End-Of-Central-Directory record at the end of
            // the file, not by requiring "PK" at offset 0, so we use ZipFile as the source of
            // truth rather than a brittle offset-0 magic-byte check.
            //
            // LiteRT-LM's .litertlm container is a completely different, non-ZIP format that
            // starts with the 8-byte ASCII magic "LITERTLM".
            //
            // Raw TFLite flatbuffers (magic "TFL3" at offset 4, i.e. header bytes
            // 1C 00 00 00 54 46 4C 33) are the older, now-unsupported ".bin" format understood by
            // neither engine and will fail with a confusing native error if allowed through.
            // We detect that case here so we can fail fast with an actionable message instead of
            // downloading gigabytes of data only to hit a confusing native error later.
            if (outputFile.exists() && outputFile.length() > 8) {
                val raf = RandomAccessFile(outputFile, "r")
                val header = ByteArray(8)
                raf.readFully(header)
                raf.close()

                val isRawTflite = header[4] == 'T'.code.toByte() && header[5] == 'F'.code.toByte() &&
                                    header[6] == 'L'.code.toByte() && header[7] == '3'.code.toByte()
                val isLitertlm = header.toString(Charsets.US_ASCII) == "LITERTLM"

                val isValid = when (expectedFormat) {
                    ModelFormat.LITERTLM -> isLitertlm
                    ModelFormat.TASK_ZIP -> try {
                        ZipFile(outputFile).use { true }
                    } catch (e: Exception) {
                        false
                    }
                }

                if (!isValid) {
                    outputFile.delete()
                    when {
                        expectedFormat == ModelFormat.LITERTLM && isRawTflite -> emit(DownloadStatus.Error(
                            "Downloaded file is a raw TFLite flatbuffer (old '.bin' format), not a .litertlm container. " +
                            "LiteRT-LM requires a genuine .litertlm file (magic 'LITERTLM' at byte 0). " +
                            "Host an actual .litertlm file at this URL, e.g. from the litert-community org on Hugging Face."
                        ))
                        expectedFormat == ModelFormat.TASK_ZIP && isRawTflite -> emit(DownloadStatus.Error(
                            "Downloaded file is not a valid ZIP archive. MediaPipe requires a .task bundle. " +
                            "The file at this URL is a raw TFLite flatbuffer (old '.bin' format), not a .task bundle. " +
                            "Host an actual MediaPipe .task bundle (a ZIP containing the tflite model + tokenizer + metadata) " +
                            "at this URL, or convert the raw model using the MediaPipe genai bundler."
                        ))
                        expectedFormat == ModelFormat.LITERTLM -> {
                            val first8Bytes = header.joinToString(" ") { "%02X".format(it) }
                            emit(DownloadStatus.Error(
                                "Invalid Model File: The file starts with [$first8Bytes] which is not a recognized " +
                                ".litertlm container (expected ASCII magic \"LITERTLM\"). Please ensure you are hosting the correct .litertlm file."
                            ))
                        }
                        else -> {
                            val first8Bytes = header.joinToString(" ") { "%02X".format(it) }
                            emit(DownloadStatus.Error(
                                "Invalid Model File: The file starts with [$first8Bytes] which is not a recognized MediaPipe .task bundle (ZIP). " +
                                "Please ensure you are hosting the correct .task file."
                            ))
                        }
                    }
                    return@flow
                }
            }

            emit(DownloadStatus.Success(outputFile))
        } catch (e: Exception) {
            if (outputFile.exists()) outputFile.delete()
            emit(DownloadStatus.Error(e.message ?: "Unknown download error"))
        }
    }.flowOn(Dispatchers.IO)
}
