package com.lagradost.cloudstream3.network

import com.lagradost.cloudstream3.USER_AGENT
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders

private val defaultHeaders = mapOf("user-agent" to USER_AGENT)

/** Header merge ABI shared by CloudStream's challenge interceptors. */
fun getHeaders(headers: Map<String, String>, cookie: Map<String, String>): Headers {
    val cookieHeader = if (cookie.isEmpty()) {
        emptyMap()
    } else {
        mapOf("Cookie" to cookie.entries.joinToString(" ") { "${it.key}=${it.value};" })
    }
    return (defaultHeaders + headers + cookieHeader).toHeaders()
}
