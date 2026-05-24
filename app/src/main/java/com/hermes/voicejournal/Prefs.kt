package com.hermes.voicejournal

import android.content.Context

data class RecordingConfig(
    val serverUrl: String,
    val uploadPath: String,
    val chunkMinutes: Int,
    val sessionLabel: String,
)

object Prefs {
    private const val PREFS_NAME = "voice_journal_prefs"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_UPLOAD_PATH = "upload_path"
    private const val KEY_CHUNK_MINUTES = "chunk_minutes"
    private const val KEY_SESSION_LABEL = "session_label"

    fun load(context: Context): RecordingConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return RecordingConfig(
            serverUrl = prefs.getString(KEY_SERVER_URL, "https://your-domain.example") ?: "https://your-domain.example",
            uploadPath = prefs.getString(KEY_UPLOAD_PATH, "/api/upload") ?: "/api/upload",
            chunkMinutes = prefs.getInt(KEY_CHUNK_MINUTES, 60),
            sessionLabel = prefs.getString(KEY_SESSION_LABEL, "workday") ?: "workday",
        )
    }

    fun save(context: Context, config: RecordingConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_SERVER_URL, config.serverUrl)
            .putString(KEY_UPLOAD_PATH, config.uploadPath)
            .putInt(KEY_CHUNK_MINUTES, config.chunkMinutes)
            .putString(KEY_SESSION_LABEL, config.sessionLabel)
            .apply()
    }
}
