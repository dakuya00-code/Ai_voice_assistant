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

class UploadClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun uploadChunk(
        serverUrl: String,
        uploadPath: String,
        sessionId: String,
        chunkIndex: Int,
        durationSeconds: Long,
        startedAtIso: String,
        file: File,
    ): Result<Unit> = withContext(Dispatchers.IO) {
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
                if (!response.isSuccessful) {
                    error("upload failed: HTTP ${response.code}")
                }
            }
        }
    }

    private fun buildUploadUrl(serverUrl: String, uploadPath: String): String {
        val base = serverUrl.trim().trimEnd('/')
        val path = uploadPath.trim().trimStart('/')
        return "$base/$path".toHttpUrl().toString()
    }
}
