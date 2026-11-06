package com.nuvio.app.features.autosync

import com.nuvio.app.features.addons.RawHttpResponse

/**
 * AutoSync-only subtitle HTTP path.
 *
 * Uses the platform's subtitle client through [autoSyncHttpGet], so cancelling an AutoSync
 * coroutine immediately cancels the underlying network call. This deliberately does not modify
 * Nuvio's shared httpRequestRaw() behavior used by plugins and other features.
 */
internal object AutoSyncSubtitleHttp {
    private const val READ_BUFFER_BYTES = 32 * 1024
    private const val TRUNCATION_SUFFIX = "\n...[truncated]"

    suspend fun get(
        url: String,
        headers: Map<String, String>,
        maxResponseBodyBytes: Int,
        languageHint: String? = null,
    ): RawHttpResponse {
        // Preserve Nuvio's raw-HTTP behavior: let the client negotiate transparent
        // compression instead of forwarding caller-provided Accept-Encoding.
        val requestHeaders = headers.filterKeys { !it.equals("Accept-Encoding", ignoreCase = true) }

        return autoSyncHttpGet(
            url = url,
            headers = requestHeaders,
            kind = AutoSyncHttpClientKind.SUBTITLE,
            timeoutMs = null,
        ) { response ->
            val limited = readBodyLimited(response.body, maxResponseBodyBytes)
            // Addon files can use legacy encodings without declaring a charset. Detect from the
            // bytes before decoding so accented letters are not irreversibly replaced with U+FFFD.
            val decoded = SubtitleCharsetDetector.decode(limited.bytes, languageHint = languageHint)
            RawHttpResponse(
                status = response.status,
                statusText = response.statusText,
                url = response.finalUrl,
                body = if (limited.truncated) "$decoded$TRUNCATION_SUFFIX" else decoded,
                headers = response.headers,
            )
        }
    }

    private class LimitedBody(val bytes: ByteArray, val truncated: Boolean)

    private suspend fun readBodyLimited(body: AutoSyncHttpBody, maxBytes: Int): LimitedBody {
        val limit = maxBytes.coerceAtLeast(0)
        val chunks = ArrayList<ByteArray>()
        var total = 0
        var truncated = false
        val buffer = ByteArray(READ_BUFFER_BYTES)

        while (total < limit) {
            val read = body.read(buffer, 0, minOf(buffer.size, limit - total))
            if (read < 0) break
            if (read == 0) continue
            chunks += buffer.copyOf(read)
            total += read
        }
        if (total == limit) {
            val probe = ByteArray(1)
            var read: Int
            do {
                read = body.read(probe, 0, 1)
            } while (read == 0)
            truncated = read > 0
        }

        val bytes = ByteArray(total)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(bytes, offset)
            offset += chunk.size
        }
        return LimitedBody(bytes, truncated)
    }
}
