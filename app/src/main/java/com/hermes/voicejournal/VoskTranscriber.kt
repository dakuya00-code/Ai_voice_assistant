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
        FileInputStream(wavFile).use { fis ->
            val header = ByteArray(44)
            if (fis.read(header) < 44) return ""
            val sampleRate = parseWavSampleRate(header).toFloat()
            val rec = Recognizer(m, sampleRate)
            try {
                val buf = ByteArray(4096)
                while (true) {
                    val n = fis.read(buf)
                    if (n <= 0) break
                    rec.acceptWaveForm(buf, n)
                }
                val finalJson = JSONObject(rec.finalResult)
                return finalJson.optString("text").trim()
            } finally {
                rec.close()
            }
        }
    }

    private fun parseWavSampleRate(header: ByteArray): Int {
        if (header.size < 28) return 16_000
        val b0 = header[24].toInt() and 0xFF
        val b1 = header[25].toInt() and 0xFF
        val b2 = header[26].toInt() and 0xFF
        val b3 = header[27].toInt() and 0xFF
        val sr = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        return if (sr in 8_000..48_000) sr else 16_000
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
