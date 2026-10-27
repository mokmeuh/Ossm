package com.ossm.remote.natural

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Cancellable HTTP transport for an explicitly configured Ollama endpoint.
 * Callers should supply the private Tailscale address; there is no public/default URL.
 */
class OkHttpOllamaTransport(
    client: OkHttpClient,
    endpoint: HttpUrl,
    private val maxResponseBytes: Int = 262_144,
    private val callTimeoutMillis: Long = 300_000L,
) : OllamaTransport {
    private val client = client
    private val endpoint = endpoint.newBuilder().query(null).fragment(null).build()

    init {
        require(this.endpoint.scheme == "http" || this.endpoint.scheme == "https")
        require(this.endpoint.host.isNotBlank())
        require(maxResponseBytes in 1_024..1_048_576)
        require(callTimeoutMillis in 1_000L..600_000L)
    }

    override suspend fun get(path: String): String = execute(
        Request.Builder()
            .url(urlFor(path))
            .get()
            .build(),
    )

    override suspend fun post(path: String, body: String): String = execute(
        Request.Builder()
            .url(urlFor(path))
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build(),
    )

    private fun urlFor(path: String): HttpUrl = endpoint.newBuilder()
        .addPathSegments(path.trimStart('/'))
        .build()

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation: CancellableContinuation<String> ->
            val call = client.newCall(request)
            call.timeout().timeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(error)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!continuation.isActive) return@use
                            if (!it.isSuccessful) {
                                continuation.resumeWithException(
                                    OllamaHttpException(it.code),
                                )
                                return@use
                            }
                            val responseBody = it.body
                            if (responseBody == null) {
                                continuation.resumeWithException(IOException("Ollama response is empty"))
                                return@use
                            }
                            // Read UP TO the limit: readUtf8(n) demands exactly n bytes and threw
                            // EOFException on every normal (shorter) Ollama response.
                            val source = responseBody.source()
                            source.request(maxResponseBytes.toLong() + 1L)
                            if (source.buffer.size > maxResponseBytes) {
                                continuation.resumeWithException(IOException("Ollama response is oversized"))
                            } else {
                                continuation.resume(source.buffer.readUtf8())
                            }
                        }
                    } catch (error: IOException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(error)
                        }
                    }
                }
            })
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
