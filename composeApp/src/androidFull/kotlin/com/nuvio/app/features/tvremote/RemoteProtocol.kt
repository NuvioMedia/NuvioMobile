package com.nuvio.app.features.tvremote

// Versioned LAN protocol. Never add stream URLs or source credentials here.
import kotlinx.serialization.Serializable

@Serializable
data class RemoteSnapshot(
    val version: Int = 1,
    val deviceId: String,
    val deviceName: String,
    val sessionId: String? = null,
    val revision: Long = 0,
    val title: String = "",
    val episodeTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val artwork: String? = null,
    val state: String = "idle",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val canSeek: Boolean = false,
)

@Serializable
data class RemoteCommand(val requestId: String, val sessionId: String, val action: String, val positionMs: Long? = null)

@Serializable
data class PairRequest(val deviceId: String, val secret: String)

@Serializable
data class PairResponse(val deviceId: String, val deviceName: String, val credential: String)
