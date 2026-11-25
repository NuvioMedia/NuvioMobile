package com.nuvio.app.features.audiosync

internal actual fun startAudioSyncThread(name: String, body: () -> Unit) {
    Thread(body, name).apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
        start()
    }
}
