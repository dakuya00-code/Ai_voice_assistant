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

    private fun listPendingWavFiles(context: Context): List<File> {
        val root = File(context.cacheDir, "voice-journal")
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension.equals("wav", ignoreCase = true) }
            .sortedBy { it.lastModified() }
            .toList()
    }

    suspend fun uploadPending(context: Context, config: RecordingConfig, client: UploadClient): Int {
        val hasModel = VoskTranscriber.hasModel(context)

        if (hasModel) {
            listPendingWavFiles(context).forEach { wav ->
                val textFile = File(wav.parentFile, "${wav.nameWithoutExtension}.txt")
                if (textFile.exists()) return@forEach
                val transcript = runCatching { VoskTranscriber.transcribeFile(context, wav) }
                    .getOrDefault("")
                    .trim()
                if (transcript.isBlank()) return@forEach
                runCatching { textFile.writeText(transcript) }
            }
        }

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
                sttEngine = VoskTranscriber.STT_ENGINE,
                sttModelId = VoskTranscriber.MODEL_ID,
                sttConfidence = estimateTextQualityScore(text),
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
                        fileSizeBytes = file.length(),
                    )
                )
                runCatching { file.delete() }
                val wavPeer = File(file.parentFile, "${file.nameWithoutExtension}.wav")
                runCatching { if (wavPeer.exists()) wavPeer.delete() }
                runCatching { File(file.parentFile, "${file.nameWithoutExtension}.json").delete() }
                uploaded += 1
            }
        }
        return uploaded
    }

    private fun estimateTextQualityScore(text: String): Double {
        if (text.isBlank()) return 0.0
        val cleaned = text.trim()
        val chars = cleaned.length.coerceAtLeast(1)
        val hangulCount = cleaned.count { it in '\uAC00'..'\uD7A3' }
        val latinCount = cleaned.count { it.isLetter() && (it in 'a'..'z' || it in 'A'..'Z') }
        val digitCount = cleaned.count { it.isDigit() }
        val punctCount = cleaned.count { it in listOf('.', ',', '?', '!', ':', ';') }
        val uniqueRatio = cleaned.toSet().size.toDouble() / chars.toDouble()

        var score = 0.0
        score += (hangulCount.toDouble() / chars) * 0.65
        score += (punctCount.toDouble() / chars).coerceAtMost(0.08) * 1.5
        score += uniqueRatio.coerceIn(0.0, 1.0) * 0.25
        score -= (digitCount.toDouble() / chars).coerceAtMost(0.2) * 0.08
        score -= (latinCount.toDouble() / chars).coerceAtMost(0.4) * 0.1
        return score.coerceIn(0.0, 1.0)
    }
}
