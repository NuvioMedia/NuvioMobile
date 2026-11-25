package com.nuvio.app.features.audiosync

import platform.Foundation.NSQualityOfServiceUtility
import platform.Foundation.NSThread

internal actual fun startAudioSyncThread(name: String, body: () -> Unit) {
    val thread = NSThread(block = body)
    thread.name = name
    thread.qualityOfService = NSQualityOfServiceUtility
    thread.start()
}
