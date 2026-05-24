package com.hermes.voicejournal

import android.content.Context

data class RecordingConfig(
    val serverUrl: String,
    val uploadPath: String,
    val silenceTimeoutSeconds: Int,
    val sessionLabel: String,
)

object Prefs {
    private const val PREFS_NAME = "voice_journal_prefs"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_UPLOAD_PATH = "upload_path"
    private const val KEY_SILENCE_TIMEOUT_SECONDS = "silence_timeout_seconds"
    private const val KEY_SESSION_LABEL = "session_label"
    private const val KEY_SETUP_COMPLETE = "setup_complete"
    private const val KEY_LAST_SEEN_RELEASE_PUBLISHED_AT = "last_seen_release_published_at"

    fun load(context: Context): RecordingConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return RecordingConfig(
            serverUrl = prefs.getString(KEY_SERVER_URL, "http://187.77.115.121:8799") ?: "http://187.77.115.121:8799",
            uploadPath = prefs.getString(KEY_UPLOAD_PATH, "/api/upload") ?: "/api/upload",
            silenceTimeoutSeconds = prefs.getInt(KEY_SILENCE_TIMEOUT_SECONDS, 15),
            sessionLabel = prefs.getString(KEY_SESSION_LABEL, "workday") ?: "workday",
        )
    }

    fun save(context: Context, config: RecordingConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_SERVER_URL, config.serverUrl)
            .putString(KEY_UPLOAD_PATH, config.uploadPath)
            .putInt(KEY_SILENCE_TIMEOUT_SECONDS, config.silenceTimeoutSeconds)
            .putString(KEY_SESSION_LABEL, config.sessionLabel)
            .apply()
    }

    fun isSetupComplete(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SETUP_COMPLETE, false)
    }

    fun setSetupComplete(context: Context, complete: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SETUP_COMPLETE, complete).apply()
    }

    fun getLastSeenReleasePublishedAt(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_SEEN_RELEASE_PUBLISHED_AT, "") ?: ""
    }

    fun setLastSeenReleasePublishedAt(context: Context, publishedAt: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_SEEN_RELEASE_PUBLISHED_AT, publishedAt).apply()
    }
}
