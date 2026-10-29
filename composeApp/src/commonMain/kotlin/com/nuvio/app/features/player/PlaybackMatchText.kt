package com.nuvio.app.features.player

internal fun playbackMatchText(info: PlayerMediaInfo, languages: List<String>): String = buildList {
    addAll(resolutionTokens(info))
    addAll(videoCodecTokens(info.videoCodec))
    if ("DV" in info.hdr) addAll(listOf("DV", "DoVi", "Dolby Vision"))
    if ("HDR10" in info.hdr) addAll(listOf("HDR", "HDR10"))
    if ("HLG" in info.hdr) addAll(listOf("HDR", "HLG"))
    (info.bitDepth ?: 10.takeIf { info.hdr.isNotEmpty() })?.let { add("${it}bit") }
    addAll(audioTokens(info.audioCodec, info.audioProfile))
    channelsToken(info.audioChannels)?.let(::add)
    addAll(languages)
}.distinct().joinToString(" ")

private fun resolutionTokens(info: PlayerMediaInfo): List<String> {
    val width = info.videoWidth ?: return emptyList()
    val height = info.videoHeight ?: return emptyList()
    return when {
        width >= 3800 || height >= 2100 -> listOf("2160p", "4K", "UHD")
        width >= 2500 || height >= 1400 -> listOf("1440p", "2K")
        width >= 1900 || height >= 1000 -> listOf("1080p", "FHD")
        width >= 1260 || height >= 700 -> listOf("720p")
        else -> listOf("${height}p", "SD")
    }
}

private fun videoCodecTokens(codec: String?): List<String> = when (codec?.uppercase()) {
    null -> emptyList()
    "HEVC", "DOLBY VISION" -> listOf("HEVC", "H.265", "H265", "x265")
    "AVC" -> listOf("AVC", "H.264", "H264", "x264")
    else -> listOf(codec)
}

private fun audioTokens(codec: String?, profile: String?): List<String> {
    val label = codec?.uppercase() ?: return emptyList()
    val profileLabel = profile.orEmpty().uppercase()
    return buildList {
        when {
            label.startsWith("E-AC-3") -> addAll(listOf("DD+", "DDP", "EAC3"))
            label == "AC-3" -> addAll(listOf("DD", "AC3"))
            label.startsWith("DTS") -> {
                add("DTS")
                if (label.contains("HD") || profileLabel.contains("DTS-HD")) add("DTS-HD")
                if (profileLabel.contains("MA")) add("DTS-HD MA")
                if (profileLabel.contains("DTS:X")) add("DTS:X")
            }
            else -> add(codec)
        }
        if (label.endsWith("JOC") || profileLabel.contains("ATMOS")) add("Atmos")
    }
}

private fun channelsToken(channels: Int?): String? = when (channels) {
    1 -> "1.0"
    2 -> "2.0"
    6 -> "5.1"
    8 -> "7.1"
    else -> null
}
