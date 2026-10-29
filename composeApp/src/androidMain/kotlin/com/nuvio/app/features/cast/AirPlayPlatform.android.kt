package com.nuvio.app.features.cast

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual object AirPlayPlatform {
    actual suspend fun scanDevices(timeoutMs: Int): List<UnifiedCastDevice> = withContext(Dispatchers.IO) {
        // Android can discover AirPlay via mDNS _airplay._tcp using NsdManager
        // Stub for now - returns empty, but structure is ready for universal.
        // To enable: use NsdManager.discoverServices("_airplay._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        Log.d("AirPlayPlatform", "AirPlay scan stub - not yet implemented on Android (Apple TV via AirPlay needs NsdManager)")
        emptyList()
    }
}
