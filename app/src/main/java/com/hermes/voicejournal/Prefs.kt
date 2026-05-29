package com.hermes.voicejournal

import android.content.Context

data class RecordingConfig(
    val serverUrl: String,
    val uploadPath: String,
    val sessionLabel: String,
    val geminiApiKey: String,
)

object Prefs {
    private const val PREFS_NAME = "voice_journal_prefs"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_UPLOAD_PATH = "upload_path"
    private const val KEY_SESSION_LABEL = "session_label"
    private const val KEY_GEMINI_API_KEY = "gemini_api_key"
    private const val KEY_SETUP_COMPLETE = "setup_complete"

    fun load(context: Context): RecordingConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return RecordingConfig(
            serverUrl = prefs.getString(KEY_SERVER_URL, "https://3394dc7db4303708-187-77-115-121.serveousercontent.com") ?: "https://3394dc7db4303708-187-77-115-121.serveousercontent.com",
            uploadPath = prefs.getString(KEY_UPLOAD_PATH, "/api/upload") ?: "/api/upload",
            sessionLabel = prefs.getString(KEY_SESSION_LABEL, "workday") ?: "workday",
            geminiApiKey = prefs.getString(KEY_GEMINI_API_KEY, "") ?: "",
        )
    }

    fun save(context: Context, config: RecordingConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_SERVER_URL, config.serverUrl)
            .putString(KEY_UPLOAD_PATH, config.uploadPath)
            .putString(KEY_SESSION_LABEL, config.sessionLabel)
            .putString(KEY_GEMINI_API_KEY, config.geminiApiKey)
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
}
