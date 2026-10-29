package com.nuvio.app.features.cast

/**
 * AirPlay discovery via mDNS _airplay._tcp
 * Stub for iOS/Apple TV - universal ready. Actual implementation would use
 * NSNetServiceBrowser on iOS and JmDNS on Android.
 */
internal expect object AirPlayPlatform {
    suspend fun scanDevices(timeoutMs: Int): List<UnifiedCastDevice>
}
