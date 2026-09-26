package com.nuvio.app.features.servers.mediabrowser

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf

internal class TestHttp(private val responder: (HttpRequestData) -> String = { error("Unexpected request ${it.url}") }) {
    val requests = mutableListOf<HttpRequestData>()

    val client = HttpClient(
        MockEngine { request ->
            requests += request
            respond(
                content = responder(request),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    )
}

internal val HttpRequestData.text: String
    get() = (body as TextContent).text
