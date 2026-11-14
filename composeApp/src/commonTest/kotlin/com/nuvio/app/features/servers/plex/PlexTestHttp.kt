package com.nuvio.app.features.servers.plex

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Answers every request with the status and body [route] gives for it, and keeps the requests. */
internal class PlexTestHttp(private val route: (HttpRequestData) -> Pair<Int, String>) {
    private val lock = SynchronizedObject()
    private val recorded = mutableListOf<HttpRequestData>()

    val requests: List<HttpRequestData>
        get() = synchronized(lock) { recorded.toList() }

    fun requests(path: String): List<HttpRequestData> = requests.filter { it.url.encodedPath == path }

    val client = HttpClient(
        MockEngine { request ->
            synchronized(lock) { recorded += request }
            val (status, body) = route(request)
            respond(
                content = body,
                status = HttpStatusCode.fromValue(status),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    ) {
        expectSuccess = false
        followRedirects = false
    }
}

internal fun plexProvider(http: PlexTestHttp, now: () -> Long = { 1_000_000L }) =
    PlexProvider(http.client, now = now, clientId = { "device-1" })
