package com.nuvio.app.features.trakt

// iOS signs in with the device-code flow; the loopback listener is Android-only.
internal actual val traktLoopbackReceiverSupported: Boolean = false

internal actual suspend fun awaitTraktLoopbackRedirect(
    port: Int,
    path: String,
    timeoutMillis: Long,
    responseHtml: String,
): String? = null
