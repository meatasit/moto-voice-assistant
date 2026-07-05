package com.moto.voice.network

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Thin webhook client. Prefer [call] for lifecycle-aware coroutine callers.
 * All threading is delegated to the caller's coroutine dispatcher; the underlying OkHttp
 * call is cancelled if the coroutine is cancelled.
 */
class WebhookClient(
    private val url: String,
    private val authToken: String,
    timeoutSeconds: Int,
) {
    sealed class Result {
        abstract val rawJson: String
        abstract val elapsedMs: Long

        data class Success(
            val response: WebhookResponse,
            override val rawJson: String,
            override val elapsedMs: Long,
        ) : Result()

        data class Failure(
            val error: String,
            override val rawJson: String,
            override val elapsedMs: Long,
        ) : Result()
    }

    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .build()

    /** Suspend-friendly, cancellable webhook call. Network IO runs on [Dispatchers.IO]. */
    suspend fun call(text: String): Result = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val request = buildRequest(text)
        try {
            suspendCancellableCoroutine { cont ->
                val httpCall = http.newCall(request)
                cont.invokeOnCancellation { runCatching { httpCall.cancel() } }
                httpCall.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        val elapsed = System.currentTimeMillis() - t0
                        if (cont.isActive) cont.resume(
                            Result.Failure(e.message ?: "network error", "", elapsed)
                        )
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val elapsed = System.currentTimeMillis() - t0
                        val result = response.use { resp -> parseResponse(resp, elapsed) }
                        if (cont.isActive) cont.resume(result)
                    }
                })
            }
        } catch (e: Exception) {
            Result.Failure(e.message ?: "error", "", System.currentTimeMillis() - t0)
        }
    }

    private fun parseResponse(resp: Response, elapsed: Long): Result {
        val raw = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
        if (!resp.isSuccessful) return Result.Failure("HTTP ${resp.code}", raw, elapsed)
        val parsed = runCatching { gson.fromJson(raw, WebhookResponse::class.java) }
            .getOrElse { err ->
                val msg = if (err is JsonSyntaxException) "invalid JSON" else (err.message ?: "parse error")
                return Result.Failure(msg, raw, elapsed)
            }
        return if (parsed == null) Result.Failure("empty response", raw, elapsed)
        else Result.Success(parsed, raw, elapsed)
    }

    private fun buildRequest(text: String): Request {
        val body = gson.toJson(mapOf("text" to text))
            .toRequestBody("application/json".toMediaType())
        return Request.Builder()
            .url(url)
            .post(body)
            .apply { if (authToken.isNotBlank()) header("X-Auth-Token", authToken) }
            .build()
    }
}
