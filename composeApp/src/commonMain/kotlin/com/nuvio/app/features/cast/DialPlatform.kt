package com.nuvio.app.features.cast

/**
 * DIAL (Discovery and Launch) - used by Fire TV, Roku, WebOS, some Chromecast fallback.
 * SSDP ST: urn:dial-multiscreen-org:service:dial:1 , Application-URL header.
 * Stub for now - universal ready, actual launch via DIAL REST will be added later.
 */
internal expect object DialPlatform {
    suspend fun scanDevices(timeoutMs: Int): List<UnifiedCastDevice>
}
