package com.hermes.voicejournal

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl

data class UploadResult(
    val savedPath: String?,
    val transcriptPath: String?,
    val audioDeleted: Boolean,
    val audioDeleteError: String?,
)

data class TextUploadResult(
    val textSavedPath: String?,
    val qualityFlag: String?,
    val qualityScore: Double?,
)

class UploadClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun uploadChunk(
        serverUrl: String,
        uploadPath: String,
        sessionId: String,
        chunkIndex: Int,
        durationSeconds: Long,
        startedAtIso: String,
        file: File,
    ): Result<UploadResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = buildUploadUrl(serverUrl, uploadPath)
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("session_id", sessionId)
                .addFormDataPart("chunk_index", chunkIndex.toString())
                .addFormDataPart("duration_seconds", durationSeconds.toString())
                .addFormDataPart("started_at", startedAtIso)
                .addFormDataPart(
                    "recording",
                    file.name,
                    file.asRequestBody("audio/mp4".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("upload failed: HTTP ${response.code} ${responseText.trim()}")
                }
                val json = org.json.JSONObject(responseText)
                UploadResult(
                    savedPath = json.optString("saved_path").ifBlank { null },
                    transcriptPath = json.optString("transcript_path").ifBlank { null },
                    audioDeleted = json.optBoolean("audio_deleted", false),
                    audioDeleteError = json.optString("audio_delete_error").ifBlank { null },
                )
            }
        }
    }

    suspend fun uploadAnalysisText(
        serverUrl: String,
        sessionId: String,
        sourceFile: String,
        analyzedText: String,
        sttEngine: String? = null,
        sttModelId: String? = null,
        sttConfidence: Double? = null,
    ): Result<TextUploadResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = buildUploadUrl(serverUrl, "/api/upload-text")
            val bodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("session_id", sessionId)
                .addFormDataPart("source_file", sourceFile)
                .addFormDataPart("analyzed_text", analyzedText)
            sttEngine?.takeIf { it.isNotBlank() }?.let { bodyBuilder.addFormDataPart("stt_engine", it) }
            sttModelId?.takeIf { it.isNotBlank() }?.let { bodyBuilder.addFormDataPart("stt_model_id", it) }
            sttConfidence?.let { bodyBuilder.addFormDataPart("stt_confidence", it.toString()) }
            val body = bodyBuilder.build()

            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("text upload failed: HTTP ${response.code} ${responseText.trim()}")
                }
                val json = org.json.JSONObject(responseText)
                TextUploadResult(
                    textSavedPath = json.optString("text_saved_path").ifBlank { null },
                    qualityFlag = json.optString("quality_flag").ifBlank { null },
                    qualityScore = if (json.has("quality_score")) json.optDouble("quality_score") else null,
                )
            }
        }
    }

    private fun buildUploadUrl(serverUrl: String, uploadPath: String): String {
        val base = serverUrl.trim().trimEnd('/')
        val path = uploadPath.trim().trimStart('/')
        return "$base/$path".toHttpUrl().toString()
    }
}
