package com.nuvio.app.features.livetv

import com.nuvio.app.features.addons.httpRequestRaw

/** Bounded binary GET using the application's configured Ktor platform client. */
internal suspend fun httpGetBytesWithHeaders(
    url: String,
    headers: Map<String, String>,
    maxResponseBodyBytes: Int,
): ByteArray {
    val response = httpRequestRaw(
        method = "GET",
        url = url,
        headers = headers,
        body = "",
        followRedirects = true,
        maxResponseBodyBytes = maxResponseBodyBytes,
    )
    check(response.status in 200..299) {
        "HTTP ${response.status} ${response.statusText}".trim()
    }
    check(!response.body.endsWith("\n...[truncated]")) {
        "HTTP response exceeds the safe size limit."
    }
    return response.bodyBytes
}
