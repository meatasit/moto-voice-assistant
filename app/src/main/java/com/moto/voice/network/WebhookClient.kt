package com.moto.voice.network

import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class WebhookClient(
    private val url: String,
    private val authToken: String,
    timeoutSeconds: Int,
) {
    data class Result(
        val response: WebhookResponse?,
        val rawJson: String,
        val elapsedMs: Long,
        val error: String?,
    )

    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .build()

    fun send(text: String): Result {
        val t0 = System.currentTimeMillis()
        val body = gson.toJson(mapOf("text" to text))
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(url)
            .post(body)
            .apply { if (authToken.isNotBlank()) header("X-Auth-Token", authToken) }
            .build()
        return try {
            val resp = http.newCall(req).execute()
            val elapsed = System.currentTimeMillis() - t0
            val raw = resp.body?.string() ?: ""
            if (resp.isSuccessful) {
                val parsed = runCatching { gson.fromJson(raw, WebhookResponse::class.java) }.getOrNull()
                Result(parsed, raw, elapsed, null)
            } else {
                Result(null, raw, elapsed, "HTTP ${resp.code}")
            }
        } catch (e: Exception) {
            Result(null, "", System.currentTimeMillis() - t0, e.message ?: "error")
        }
    }
}
