package com.nuvio.app.features.mdblist

import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.core.network.readBoundedResponseBody
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.http.encodedPath
import io.ktor.http.formUrlEncode

internal class MdbListNetworkEngine(
    private val configuration: MdbListConfiguration,
    private val client: HttpClient = createApiHttpClient(),
) : MdbListHttpEngine {
    override suspend fun execute(request: MdbListHttpRequest): MdbListHttpResponse =
        client.prepareRequest(mdbListRequestUrl(configuration, request)) {
            method = HttpMethod.parse(request.method.name)
            header("Accept", "application/json")
            header("User-Agent", "Nuvio/${configuration.appVersion}")
            request.accessToken?.let { header("Authorization", "Bearer $it") }
            if (request.method == MdbListHttpMethod.POST || request.method == MdbListHttpMethod.PUT) {
                header("Content-Type", if (request.form == null) "application/json" else "application/x-www-form-urlencoded")
                setBody(request.form?.toList()?.formUrlEncode() ?: request.body)
            }
        }.execute { response ->
            MdbListHttpResponse(
                response.status.value,
                readBoundedResponseBody(response.bodyAsChannel(), response.contentLength()),
                response.headers.entries().associate { (key, values) -> key to values.joinToString(",") },
            )
        }
}

internal fun mdbListRequestUrl(configuration: MdbListConfiguration, request: MdbListHttpRequest): String {
    require(request.path.startsWith('/') && !request.path.startsWith("//"))
    require(request.path.none { it == '?' || it == '#' || it == '\\' })
    val base = Url(configuration.baseUrl)
    require(base.protocol == URLProtocol.HTTPS || base.host in setOf("localhost", "127.0.0.1", "::1"))
    require(base.user.isNullOrEmpty() && base.password.isNullOrEmpty())
    return URLBuilder(base).apply {
        encodedPath = request.path
        parameters.clear()
        fragment = ""
        request.query.forEach { (key, value) -> parameters.append(key, value) }
    }.buildString()
}
