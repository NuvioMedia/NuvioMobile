package com.nuvio.app.features.usenet

import java.net.URI
import java.security.MessageDigest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The Newznab endpoint: [UsenetIndexer.apiUrl] with [UsenetIndexer.apiPath] appended, keeping the
 * URL's query parameters. A URL that already ends with the path is used as is, so a pasted
 * complete endpoint still works.
 */
internal fun UsenetIndexer.endpointUrl(): HttpUrl? {
    val url = apiUrl.trim().toHttpUrlOrNull() ?: return null
    val path = apiPath.trim().trim('/')
    if (path.isEmpty()) return url
    val segments = path.split('/')
    if (url.pathSegments.filter { it.isNotEmpty() }.takeLast(segments.size) == segments) return url
    return url.newBuilder().addPathSegments(path).build()
}

/** [endpointUrl] as text; the URL as entered while it is invalid or has no path to add. */
internal val UsenetIndexer.endpoint: String
    get() = if (apiPath.isBlank()) apiUrl else endpointUrl()?.toString() ?: apiUrl

/**
 * What an indexer's state is kept under: a hash of its endpoint and key, so no secret is stored
 * and an edited indexer starts fresh.
 */
internal fun UsenetIndexer.stateKey(): String = MessageDigest.getInstance("SHA-256")
    .digest("$endpoint\n$apiKey".toByteArray())
    .joinToString("") { "%02x".format(it) }

internal fun UsenetProvider.validate() {
    require(name.isNotBlank() && host.isNotBlank()) { "Name and server host are required" }
    require(port in 1..65535 && connections in 1..4096) { "Invalid port or connection limit" }
    require(priority in USENET_PRIORITIES) { "Invalid priority" }
    require(!host.contains(Regex("[\\s/@?#]"))) { "Enter a server host without a URL or port" }
    require(!username.contains(':') && !(username + password).contains(Regex("[\\r\\n\\u0000]"))) {
        "Invalid provider credentials"
    }
    URI(serverUrl()) // Also validates IPv6 and host syntax.
}

internal fun UsenetIndexer.validate() {
    val url = endpointUrl()
    require(name.isNotBlank() && url != null) { "A name and valid HTTP(S) API URL are required" }
    require(priority in USENET_PRIORITIES) { "Invalid priority" }
    require(fetchResultsLimit == null || fetchResultsLimit in 0..UsenetSourceConfiguration.MAX_INDEXER_RESULTS) {
        "Invalid fetch limit"
    }
    require(!apiPath.contains(Regex("[?#\\\\\\s]")) && apiPath.split('/').none { it == "." || it == ".." }) {
        "Enter an API path such as /api"
    }
    require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) {
        "Use an API URL without user credentials or a fragment"
    }
}

internal fun UsenetSourceConfiguration.validate() {
    ranking.validate()
    require(providers.size <= 64 && indexers.size <= 20) { "Too many providers or indexers" }
    require(providers.map { it.id }.distinct().size == providers.size)
    require(indexers.map { it.id }.distinct().size == indexers.size)
    providers.forEach { it.validate() }
    indexers.forEach { it.validate() }
    require(providers.filter { it.enabled }.sumOf { it.connections } <= 4096) { "Total connections exceed 4096" }
    require(minResolution in listOf(0, 480, 720, 1080, 2160))
    require(
        maxSizeGb in 0..1000 && maxAgeDays in 0..10000 &&
            (maxResults == UsenetSourceConfiguration.UNLIMITED_RESULTS ||
                maxResults in 1..UsenetSourceConfiguration.MAX_LISTED_RESULTS) &&
            indexerResultsLimit in UsenetSourceConfiguration.AUTO_INDEXER_RESULTS..UsenetSourceConfiguration.MAX_INDEXER_RESULTS,
    )
}
