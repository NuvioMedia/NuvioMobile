package com.nuvio.app.features.trakt

/** Whether this platform can receive a Trakt redirect on a local loopback port. */
internal expect val traktLoopbackReceiverSupported: Boolean

/**
 * Listens once on 127.0.0.1:[port] for the browser's redirect to [path], answers it
 * with [responseHtml], and returns the request target (path and query). Requests for
 * any other path get a 404 and are ignored. Returns null if nothing arrives within
 * [timeoutMillis]. Throws if the port cannot be opened.
 */
internal expect suspend fun awaitTraktLoopbackRedirect(
    port: Int,
    path: String,
    timeoutMillis: Long,
    responseHtml: String,
): String?
