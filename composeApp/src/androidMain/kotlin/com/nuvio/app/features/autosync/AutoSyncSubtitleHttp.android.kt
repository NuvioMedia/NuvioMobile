package com.nuvio.app.features.autosync

import com.nuvio.app.features.addons.AddonHttpClientProvider
import com.nuvio.app.features.addons.RawHttpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.text.Charsets

/**
 * AutoSync-only subtitle HTTP path.
 *
 * Reuses Nuvio's existing OkHttp client and connection pool, but uses OkHttp's
 * asynchronous API so cancelling an AutoSync coroutine immediately cancels the
 * underlying network call. This deliberately does not modify Nuvio's shared
 * httpRequestRaw() behavior used by plugins and other features.
 */
internal object AutoSyncSubtitleHttp {
    private const val READ_BUFFER_BYTES = 32 * 1024
    private const val INITIAL_BUFFER_BYTES = 64 * 1024
    private const val TRUNCATION_SUFFIX = "\n...[truncated]"

    suspend fun get(
        url: String,
        headers: Map<String, String>,
        maxResponseBodyBytes: Int,
    ): RawHttpResponse {
        val requestBuilder = Request.Builder()
            .url(url)
            .get()

        headers.forEach { (name, value) ->
            // Preserve Nuvio's raw-HTTP behavior: let OkHttp negotiate transparent
            // compression instead of forwarding caller-provided Accept-Encoding.
            if (!name.equals("Accept-Encoding", ignoreCase = true)) {
                requestBuilder.header(name, value)
            }
        }

        val call = AddonHttpClientProvider.get().newCall(requestBuilder.build())

        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                call.cancel()
            }

            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        if (!continuation.isActive) return

                        val failure =
                            if (call.isCanceled()) {
                                CancellationException(
                                    "Cancelled AutoSync subtitle HTTP request",
                                ).also { it.initCause(error) }
                            } else {
                                error
                            }
                        continuation.resumeWith(Result.failure(failure))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (!continuation.isActive) {
                            response.close()
                            return
                        }

                        try {
                            val result = response.use { current ->
                                RawHttpResponse(
                                    status = current.code,
                                    statusText = current.message,
                                    url = current.request.url.toString(),
                                    body = readResponseBodyLimited(
                                        body = current.body,
                                        maxBytes = maxResponseBodyBytes,
                                    ),
                                    headers = current.headers
                                        .toMultimap()
                                        .mapValues { (_, values) -> values.joinToString(",") }
                                        .mapKeys { (name, _) -> name.lowercase() },
                                )
                            }

                            if (continuation.isActive) {
                                continuation.resumeWith(Result.success(result))
                            }
                        } catch (error: Throwable) {
                            if (!continuation.isActive) return

                            val failure =
                                if (call.isCanceled() && error !is CancellationException) {
                                    CancellationException(
                                        "Cancelled AutoSync subtitle HTTP request",
                                    ).also { it.initCause(error) }
                                } else {
                                    error
                                }
                            continuation.resumeWith(Result.failure(failure))
                        }
                    }
                },
            )
        }
    }

    private fun readResponseBodyLimited(
        body: ResponseBody?,
        maxBytes: Int,
    ): String {
        if (body == null) return ""

        val limit = maxBytes.coerceAtLeast(0)
        val contentLength = body.contentLength()
        val initialCapacity =
            when {
                limit == 0 -> 0
                contentLength in 1..limit.toLong() -> contentLength.toInt()
                else -> minOf(limit, INITIAL_BUFFER_BYTES)
            }

        val output = ByteArrayOutputStream(initialCapacity)
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var remaining = limit
        var truncated = false

        body.byteStream().use { stream ->
            while (remaining > 0) {
                val read = stream.read(
                    buffer,
                    0,
                    minOf(buffer.size, remaining),
                )
                if (read < 0) break
                if (read == 0) continue

                output.write(buffer, 0, read)
                remaining -= read
            }

            if (remaining == 0) {
                truncated = stream.read() != -1
            }
        }

        val bytes = output.toByteArray()
        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        val decoded = try {
            String(bytes, charset)
        } catch (_: Exception) {
            String(bytes, Charsets.UTF_8)
        }

        return if (truncated) "$decoded$TRUNCATION_SUFFIX" else decoded
    }
}
