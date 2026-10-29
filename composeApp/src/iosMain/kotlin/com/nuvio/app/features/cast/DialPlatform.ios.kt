package com.nuvio.app.features.cast

internal actual object DialPlatform {
    actual suspend fun scanDevices(timeoutMs: Int): List<UnifiedCastDevice> = emptyList()
}
