package com.hermes.voicejournal

import android.content.Context
import java.io.File

object VoskModelInstaller {
    private const val ASSET_MODEL_DIR = "vosk-model"

    fun ensureInstalled(context: Context): Result<Boolean> {
        return runCatching {
            val targetDir = File(context.filesDir, ASSET_MODEL_DIR)
            if (targetDir.exists() && targetDir.isDirectory && targetDir.list()?.isNotEmpty() == true) {
                return@runCatching false
            }

            val rootEntries = context.assets.list(ASSET_MODEL_DIR) ?: emptyArray()
            if (rootEntries.isEmpty()) {
                throw IllegalStateException("assets/vosk-model not found")
            }

            copyAssetDir(context, ASSET_MODEL_DIR, targetDir)
            true
        }
    }

    private fun copyAssetDir(context: Context, assetPath: String, outDir: File) {
        outDir.mkdirs()
        val entries = context.assets.list(assetPath) ?: emptyArray()
        for (entry in entries) {
            val childAssetPath = "$assetPath/$entry"
            val childEntries = context.assets.list(childAssetPath) ?: emptyArray()
            if (childEntries.isNotEmpty()) {
                copyAssetDir(context, childAssetPath, File(outDir, entry))
            } else {
                context.assets.open(childAssetPath).use { input ->
                    File(outDir, entry).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}
