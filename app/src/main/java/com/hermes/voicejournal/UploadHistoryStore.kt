package com.hermes.voicejournal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val HISTORY_FILE_NAME = "uploaded_files.json"

data class UploadedFileEntry(
    val sessionId: String,
    val fileName: String,
    val chunkIndex: Int,
    val durationSeconds: Long,
    val startedAtIso: String,
    val uploadedAtIso: String,
    val payloadType: String = "audio",
    val fileSizeBytes: Long = 0,
)

object UploadHistoryStore {
    fun append(context: Context, entry: UploadedFileEntry) {
        val file = historyFile(context)
        val items = readAll(context).toMutableList()
        items.add(entry)
        val json = JSONArray().apply {
            items.takeLast(100).forEach { put(it.toJson()) }
        }
        file.writeText(json.toString())
    }

    fun readAll(context: Context): List<UploadedFileEntry> {
        val file = historyFile(context)
        if (!file.exists()) return emptyList()
        val text = file.readText().trim()
        if (text.isBlank()) return emptyList()
        val array = JSONArray(text)
        return buildList {
            for (i in 0 until array.length()) {
                add(array.getJSONObject(i).toEntry())
            }
        }
    }

    fun clear(context: Context) {
        val file = historyFile(context)
        if (file.exists()) file.delete()
    }

    private fun historyFile(context: Context): File = File(context.filesDir, HISTORY_FILE_NAME)

    private fun UploadedFileEntry.toJson(): JSONObject = JSONObject().apply {
        put("sessionId", sessionId)
        put("fileName", fileName)
        put("chunkIndex", chunkIndex)
        put("durationSeconds", durationSeconds)
        put("startedAtIso", startedAtIso)
        put("uploadedAtIso", uploadedAtIso)
        put("payloadType", payloadType)
        put("fileSizeBytes", fileSizeBytes)
    }

    private fun JSONObject.toEntry(): UploadedFileEntry = UploadedFileEntry(
        sessionId = getString("sessionId"),
        fileName = getString("fileName"),
        chunkIndex = getInt("chunkIndex"),
        durationSeconds = getLong("durationSeconds"),
        startedAtIso = getString("startedAtIso"),
        uploadedAtIso = getString("uploadedAtIso"),
        payloadType = optString("payloadType").ifBlank { "audio" },
        fileSizeBytes = optLong("fileSizeBytes", 0),
    )
}
