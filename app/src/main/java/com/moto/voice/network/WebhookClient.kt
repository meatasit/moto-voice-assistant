package com.moto.voice.network

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Thin webhook client. All threading is delegated to the caller's coroutine dispatcher;
 * the underlying OkHttp call is cancelled if the coroutine is cancelled.
 *
 * [call] accepts an [onProgress] callback that fires at fixed elapsed marks (3s and 10s)
 * so the pipeline can play "กำลังคิดค่ะ รอสักครู่นะคะ" / "อีกนิดนะคะ" while the LLM on
 * the home NAS is under load. Fire-and-forget: the pipeline decides whether to speak.
 * The progress job is cancelled the instant the HTTP call resolves.
 */
class WebhookClient(
    private val url: String,
    private val authToken: String,
    timeoutSeconds: Int,
) {
    /** Categorises failure so the pipeline can pick the right TTS message per spec §6. */
    enum class Kind { Network, Timeout, Http401, HttpOther, Parse }

    sealed class Result {
        abstract val rawJson: String
        abstract val elapsedMs: Long

        data class Success(
            val response: WebhookResponse,
            override val rawJson: String,
            override val elapsedMs: Long,
        ) : Result()

        data class Failure(
            val kind: Kind,
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

    /**
     * Suspend-friendly, cancellable webhook call.
     *
     * @param onProgress called on the same coroutine dispatcher at ~3s and ~10s marks
     *   if the request is still outstanding at that time. The elapsed value is the
     *   scheduled milestone, not the actual elapsed time.
     */
    suspend fun call(
        text: String,
        onProgress: suspend (elapsedMs: Long) -> Unit = {},
    ): Result = coroutineScope {
        val t0 = System.currentTimeMillis()
        val progressJob = launch {
            try {
                delay(PROGRESS_1_MS)
                onProgress(PROGRESS_1_MS)
                delay(PROGRESS_2_MS - PROGRESS_1_MS)
                onProgress(PROGRESS_2_MS)
            } catch (_: CancellationException) {
                // Normal path when the HTTP call finished before the milestone.
            }
        }
        try {
            doNetworkCall(text, t0)
        } finally {
            progressJob.cancel()
        }
    }

    private suspend fun doNetworkCall(text: String, t0: Long): Result = withContext(Dispatchers.IO) {
        val request = buildRequest(text)
        try {
            suspendCancellableCoroutine<Result> { cont ->
                val httpCall = http.newCall(request)
                cont.invokeOnCancellation { runCatching { httpCall.cancel() } }
                httpCall.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        val elapsed = System.currentTimeMillis() - t0
                        val kind = if (e is SocketTimeoutException) Kind.Timeout else Kind.Network
                        if (cont.isActive) cont.resume(
                            Result.Failure(kind, e.message ?: kind.name.lowercase(), "", elapsed)
                        )
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val elapsed = System.currentTimeMillis() - t0
                        val result = response.use { resp -> parseResponse(resp, elapsed) }
                        if (cont.isActive) cont.resume(result)
                    }
                })
            }
        } catch (e: SocketTimeoutException) {
            Result.Failure(Kind.Timeout, e.message ?: "timeout", "", System.currentTimeMillis() - t0)
        } catch (e: Exception) {
            Result.Failure(Kind.Network, e.message ?: "error", "", System.currentTimeMillis() - t0)
        }
    }

    private fun parseResponse(resp: Response, elapsed: Long): Result {
        val raw = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
        if (!resp.isSuccessful) {
            val kind = if (resp.code == 401) Kind.Http401 else Kind.HttpOther
            return Result.Failure(kind, "HTTP ${resp.code}", raw, elapsed)
        }
        val parsed = runCatching { gson.fromJson(raw, WebhookResponse::class.java) }
            .getOrElse { err ->
                val msg = if (err is JsonSyntaxException) "invalid JSON" else (err.message ?: "parse error")
                return Result.Failure(Kind.Parse, msg, raw, elapsed)
            }
        return if (parsed == null) Result.Failure(Kind.Parse, "empty response", raw, elapsed)
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

    private companion object {
        const val PROGRESS_1_MS = 3_000L
        const val PROGRESS_2_MS = 10_000L
    }
}
