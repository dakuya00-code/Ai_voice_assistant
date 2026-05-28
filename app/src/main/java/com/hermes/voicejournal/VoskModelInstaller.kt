package com.hermes.voicejournal

import android.content.Context
import java.io.File
import java.net.URL
import java.util.zip.ZipInputStream

object VoskModelInstaller {
    private const val ASSET_MODEL_DIR = "vosk-model"
    private const val MODEL_ZIP_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

    fun ensureInstalled(context: Context): Result<String> {
        return runCatching {
            val targetDir = File(context.filesDir, ASSET_MODEL_DIR)
            if (targetDir.exists() && targetDir.isDirectory && targetDir.list()?.isNotEmpty() == true) {
                return@runCatching "already_installed"
            }

            val rootEntries = context.assets.list(ASSET_MODEL_DIR) ?: emptyArray()
            if (rootEntries.isNotEmpty()) {
                copyAssetDir(context, ASSET_MODEL_DIR, targetDir)
                return@runCatching "installed_from_assets"
            }

            downloadAndExtractModel(targetDir)
            "installed_from_download"
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

    private fun downloadAndExtractModel(targetDir: File) {
        targetDir.mkdirs()
        val tmpZip = File(targetDir.parentFile, "vosk-model.zip")
        URL(MODEL_ZIP_URL).openStream().use { input ->
            tmpZip.outputStream().use { output -> input.copyTo(output) }
        }

        ZipInputStream(tmpZip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                val normalized = name.substringAfter('/', missingDelimiterValue = name)
                if (normalized.isNotBlank()) {
                    val outFile = File(targetDir, normalized)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out -> zis.copyTo(out) }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        tmpZip.delete()
    }
}
