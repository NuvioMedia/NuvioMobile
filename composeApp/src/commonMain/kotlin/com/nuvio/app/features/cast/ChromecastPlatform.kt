package com.nuvio.app.features.cast

internal expect object ChromecastPlatform {
    suspend fun scanDevices(timeoutMs: Int = 4000): List<UnifiedCastDevice>
    suspend fun castToDevice(device: UnifiedCastDevice, request: ChromecastMediaRequest): Boolean
    suspend fun pause(): Boolean
    suspend fun resume(): Boolean
    suspend fun seek(positionMs: Long): Boolean
    suspend fun stop(): Boolean
    suspend fun getPosition(): Long?
    suspend fun setVolume(volume: Float): Boolean
    suspend fun setActiveTrack(trackId: Long?): Boolean
    fun isConnected(): Boolean
    fun disconnect()
    fun initialize()
}
