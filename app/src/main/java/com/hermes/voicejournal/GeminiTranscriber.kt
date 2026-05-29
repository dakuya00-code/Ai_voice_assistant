package com.hermes.voicejournal

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

object GeminiTranscriber {
    const val STT_ENGINE = "gemini"
    const val MODEL_ID = "gemini-1.5-flash"

    // API 키는 설정 화면에 입력된 값을 사용

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    fun transcribeWav(file: File, apiKey: String, language: String = "ko"): String {
        if (apiKey.isBlank()) throw IllegalStateException("Gemini API key missing")
        if (!file.exists() || file.length() <= 44L) return ""

        val b64 = Base64.getEncoder().encodeToString(file.readBytes())
        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put(
                            "parts",
                            JSONArray()
                                .put(JSONObject().apply {
                                    put("text", "다음 오디오를 ${language}로 정확히 받아쓰기 하세요. 설명 없이 전사 텍스트만 출력하세요.")
                                })
                                .put(JSONObject().apply {
                                    put(
                                        "inline_data",
                                        JSONObject().apply {
                                            put("mime_type", "audio/wav")
                                            put("data", b64)
                                        }
                                    )
                                })
                        )
                    }
                )
            )
        }

        val url = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("generativelanguage.googleapis.com")
            .addPathSegment("v1beta")
            .addPathSegment("models")
            .addPathSegment("$MODEL_ID:generateContent")
            .addQueryParameter("key", apiKey)
            .build()
            .toString()

        val req = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Gemini STT failed: HTTP ${resp.code}")
            }
            val body = resp.body?.string().orEmpty()
            val json = JSONObject(body)
            val cands = json.optJSONArray("candidates") ?: return ""
            if (cands.length() == 0) return ""
            val parts = cands.getJSONObject(0)
                .optJSONObject("content")
                ?.optJSONArray("parts") ?: return ""

            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val t = parts.optJSONObject(i)?.optString("text").orEmpty()
                if (t.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(t.trim())
                }
            }
            return sb.toString().trim()
        }
    }
}
