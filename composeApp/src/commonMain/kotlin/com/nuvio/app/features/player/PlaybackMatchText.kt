package com.nuvio.app.features.player

internal fun playbackMatchText(info: PlayerMediaInfo, languages: List<String>): String = buildList {
    addAll(resolutionTokens(info))
    addAll(videoCodecTokens(info.videoCodec))
    if ("DV" in info.hdr) addAll(listOf("DV", "DoVi", "Dolby Vision"))
    if ("HDR10" in info.hdr) addAll(listOf("HDR", "HDR10"))
    if ("HLG" in info.hdr) addAll(listOf("HDR", "HLG"))
    (info.bitDepth ?: 10.takeIf { info.hdr.isNotEmpty() })?.let { add("${it}bit") }
    addAll(audioTokens(info.audioCodec, info.audioProfiles))
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
    "HEVC" -> listOf("HEVC", "H.265", "H265", "x265")
    "AVC" -> listOf("AVC", "H.264", "H264", "x264")
    else -> listOf(codec)
}

private fun audioTokens(codec: String?, profiles: List<String>): List<String> {
    val label = codec?.uppercase() ?: return emptyList()
    val profileLabels = profiles.map { it.uppercase() }
    return buildList {
        when {
            label.startsWith("E-AC-3") -> addAll(listOf("DD+", "DDP", "EAC3"))
            label == "AC-3" -> addAll(listOf("DD", "AC3"))
            label == "AC-4" -> addAll(listOf("AC-4", "AC4"))
            label.startsWith("DTS") -> {
                add("DTS")
                if (label.contains("HD") || profileLabels.any { it.contains("DTS-HD") }) add("DTS-HD")
                if (profileLabels.any { it.contains("MA") }) add("DTS-HD MA")
                if (profileLabels.any { it.contains("DTS:X") }) add("DTS:X")
            }
            else -> add(codec)
        }
        if (label.endsWith("JOC") || profileLabels.any { it.contains("ATMOS") }) add("Atmos")
    }
}

private fun channelsToken(channels: Int?): String? = when (channels) {
    1 -> "1.0"
    2 -> "2.0"
    6 -> "5.1"
    8 -> "7.1"
    else -> null
}

private val REFINE_DTS_HD_MA = Regex("""dts-?hd[ ._-]?ma\b|master[ ._-]?audio""", RegexOption.IGNORE_CASE)
private val REFINE_DTS_X = Regex("""\bdts[ ._:-]?x\b""", RegexOption.IGNORE_CASE)
private val REFINE_HDR10_PLUS = Regex("""\bhdr10\+""", RegexOption.IGNORE_CASE)

/**
 * Refine the decoder-derived playback text with details the decoder cannot report
 * but that the filename or track label can confirm. Only adds tokens when the decoder
 * already confirms the base format (e.g. Atmos is only added when E-AC-3 or TrueHD
 * is already playing).
 */
internal fun refinePlaybackText(decoderText: String, metadata: List<String>): String {
    if (decoderText.isBlank() || metadata.isEmpty()) return decoderText
    val joined = metadata.joinToString(" ")
    val metaLower = joined.lowercase()
    val extra = mutableListOf<String>()
    val hasEac3OrTruehd = decoderText.contains("DD+") || decoderText.contains("DDP")
        || decoderText.contains("EAC3") || decoderText.contains("TrueHD")
    if (hasEac3OrTruehd && !decoderText.contains("Atmos") && metaLower.contains("atmos")) {
        extra += "Atmos"
    }
    if (decoderText.contains("DTS-HD") && !decoderText.contains("DTS-HD MA")
        && REFINE_DTS_HD_MA.containsMatchIn(joined)) {
        extra += "DTS-HD MA"
    }
    if (decoderText.contains("DTS") && !decoderText.contains("DTS:X")
        && REFINE_DTS_X.containsMatchIn(joined)) {
        extra += "DTS:X"
    }
    if (decoderText.contains("HDR10") && !decoderText.contains("HDR10+")
        && REFINE_HDR10_PLUS.containsMatchIn(joined)) {
        extra += "HDR10+"
    }
    if (extra.isEmpty()) return decoderText
    return "$decoderText ${extra.joinToString(" ")}"
}
