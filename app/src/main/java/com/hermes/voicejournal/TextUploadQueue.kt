package com.hermes.voicejournal

import android.content.Context
import java.io.File

object TextUploadQueue {
    fun listPendingTextFiles(context: Context): List<File> {
        val root = File(context.cacheDir, "voice-journal")
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension.equals("txt", ignoreCase = true) }
            .sortedBy { it.lastModified() }
            .toList()
    }

    suspend fun uploadPending(context: Context, config: RecordingConfig, client: UploadClient): Int {
        val textFiles = listPendingTextFiles(context)
        var uploaded = 0
        for (file in textFiles) {
            val sessionId = file.parentFile?.name ?: config.sessionLabel
            val text = runCatching { file.readText() }.getOrDefault("").trim()
            if (text.isBlank()) continue

            val result = client.uploadAnalysisText(
                serverUrl = config.serverUrl,
                sessionId = sessionId,
                sourceFile = file.name,
                analyzedText = text,
            )
            if (result.isSuccess) {
                UploadHistoryStore.append(
                    context,
                    UploadedFileEntry(
                        sessionId = sessionId,
                        fileName = file.name,
                        chunkIndex = 0,
                        durationSeconds = 0,
                        startedAtIso = java.time.Instant.ofEpochMilli(file.lastModified()).toString(),
                        uploadedAtIso = java.time.Instant.now().toString(),
                        payloadType = "text",
                    )
                )
                runCatching { file.delete() }
                uploaded += 1
            }
        }
        return uploaded
    }
}
