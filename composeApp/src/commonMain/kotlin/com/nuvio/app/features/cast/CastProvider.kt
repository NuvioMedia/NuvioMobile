package com.nuvio.app.features.cast

import kotlinx.coroutines.flow.Flow

/**
 * Universal Cast provider abstraction.
 * Each protocol (DLNA, Chromecast, DIAL, AirPlay) implements this.
 * Allows UnifiedCastRepository to treat all TVs uniformly.
 */
interface CastProvider {
    val protocol: CastProtocol
    val displayName: String
    suspend fun discover(timeoutMs: Int): List<UnifiedCastDevice>
    suspend fun load(device: UnifiedCastDevice, media: CastMedia): Boolean
    suspend fun pause(device: UnifiedCastDevice): Boolean
    suspend fun resume(device: UnifiedCastDevice): Boolean
    suspend fun seek(device: UnifiedCastDevice, positionMs: Long): Boolean
    suspend fun stop(device: UnifiedCastDevice): Boolean
    suspend fun getPosition(device: UnifiedCastDevice): Long?
    suspend fun getCapabilities(device: UnifiedCastDevice): DeviceCapabilities
}

data class CastMedia(
    val url: String, // direct URL to TV (no proxy)
    val title: String,
    val subtitle: String? = null,
    val subtitleUrl: String? = null,
    val mimeType: String = "video/mp4",
    val durationMs: Long? = null,
    val startPositionMs: Long = 0L,
    val posterUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

data class DeviceCapabilities(
    val supportsHevc: Boolean = false,
    val supportsAv1: Boolean = false,
    val supportsMp4: Boolean = true,
    val supportsMkv: Boolean = false,
    val supportsHls: Boolean = false,
    val supportsAc3: Boolean = false,
    val rawProtocolInfo: String? = null,
)
