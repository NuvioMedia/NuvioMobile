package com.nuvio.app.features.player

data class PlayerMediaInfo(
    val videoCodec: String? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoFrameRate: Float? = null,
    val videoBitrate: Int? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
    val audioSampleRate: Int? = null,
    val audioProfiles: List<String> = emptyList(),
    val hdr: List<String> = emptyList(),
    val bitDepth: Int? = null,
)

internal fun mpvMediaInfo(property: (String) -> String?): PlayerMediaInfo =
    PlayerMediaInfo(
        videoCodec = property("current-tracks/video/codec")?.let(::mpvCodecLabel),
        videoWidth = property("video-params/w")?.toIntOrNull()?.takeIf { it > 0 },
        videoHeight = property("video-params/h")?.toIntOrNull()?.takeIf { it > 0 },
        videoFrameRate = property("container-fps")?.toFloatOrNull()?.takeIf { it > 0f },
        videoBitrate = property("video-bitrate")?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 },
        audioCodec = property("current-tracks/audio/codec")?.let(::mpvCodecLabel),
        audioChannels = property("audio-params/channel-count")?.toIntOrNull()?.takeIf { it > 0 },
        audioSampleRate = property("audio-params/samplerate")?.toIntOrNull()?.takeIf { it > 0 },
        audioProfiles = listOfNotNull(property("current-tracks/audio/codec-profile")),
        hdr = buildList {
            if (!property("current-tracks/video/dolby-vision-profile").isNullOrBlank()) add("DV")
            when (property("video-params/gamma")) {
                "pq" -> add("HDR10")
                "hlg" -> add("HLG")
            }
        },
        bitDepth = property("video-params/pixelformat")?.let(::mpvBitDepth),
    )

private fun mpvBitDepth(pixelFormat: String): Int? = when {
    pixelFormat.contains("12") -> 12
    pixelFormat.contains("10") -> 10
    else -> null
}

private fun mpvCodecLabel(codec: String): String? =
    when (val c = codec.trim().lowercase()) {
        "" -> null
        "h264" -> "AVC"
        "ac3" -> "AC-3"
        "eac3" -> "E-AC-3"
        "truehd" -> "TrueHD"
        "opus" -> "Opus"
        "vorbis" -> "Vorbis"
        "dts" -> "DTS"
        else -> when {
            c.startsWith("pcm") -> "PCM"
            else -> codec.trim().uppercase()
        }
    }
