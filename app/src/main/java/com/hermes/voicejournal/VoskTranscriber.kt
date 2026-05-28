package com.hermes.voicejournal

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileInputStream

object VoskTranscriber {
    @Volatile
    private var model: Model? = null

    fun hasModel(context: Context): Boolean {
        val modelDir = File(context.filesDir, "vosk-model")
        return modelDir.exists() && File(modelDir, "am").exists() && File(modelDir, "conf").exists()
    }

    fun transcribeFile(context: Context, wavFile: File): String {
        if (!wavFile.exists() || wavFile.length() <= 44L) return ""
        val m = getOrLoadModel(context) ?: throw IllegalStateException("vosk-model not found at filesDir/vosk-model")
        val rec = Recognizer(m, 16_000f)
        try {
            FileInputStream(wavFile).use { fis ->
                val header = ByteArray(44)
                fis.read(header)
                val buf = ByteArray(4096)
                while (true) {
                    val n = fis.read(buf)
                    if (n <= 0) break
                    rec.acceptWaveForm(buf, n)
                }
            }
            val finalJson = JSONObject(rec.finalResult)
            return finalJson.optString("text").trim()
        } finally {
            rec.close()
        }
    }

    private fun getOrLoadModel(context: Context): Model? {
        model?.let { return it }
        synchronized(this) {
            model?.let { return it }
            val modelDir = File(context.filesDir, "vosk-model")
            if (!modelDir.exists()) return null
            model = Model(modelDir.absolutePath)
            return model
        }
    }
}
