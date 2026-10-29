package com.nuvio.app.features.cast

internal actual object AirPlayPlatform {
    actual suspend fun scanDevices(timeoutMs: Int): List<UnifiedCastDevice> = emptyList()
}
