package com.nuvio.app.features.streams

/**
 * A key that stays the same from one episode of a release to the next, for streams whose add-on
 * does not provide a `bingeGroup` (the tag that says "same release, other episode").
 *
 * It is built only from things that describe the release rather than the episode: the add-on,
 * resolution, source (WEB-DL, BluRay, ...), codec, HDR, audio format, language flags and, when the
 * file name shows one, the release group. Episode numbers, titles, sizes and seeder counts are
 * ignored on purpose, so the key is equal across episodes. Returns null when nothing recognisable
 * is found, in which case no preference can be derived.
 */
internal fun StreamItem.releaseKey(): String? {
    val text = buildString {
        append(name.orEmpty()).append(' ')
        append(title.orEmpty()).append(' ')
        append(description.orEmpty()).append(' ')
        append(behaviorHints.filename.orEmpty())
    }.lowercase()

    val tokens = buildList {
        resolutionPattern.find(text)?.value?.let { add(if (it == "4k" || it == "uhd") "2160p" else it) }
        sourcePattern.find(text)?.value?.let { add(it.replace("-", "").replace("bluray", "bluray")) }
        codecPattern.find(text)?.value?.let {
            add(
                when (it.replace(".", "")) {
                    "x265", "h265", "hevc" -> "hevc"
                    "x264", "h264", "avc" -> "avc"
                    else -> it
                },
            )
        }
        hdrPattern.find(text)?.value?.let {
            add(
                when {
                    it.startsWith("dolby") || it == "dovi" -> "dv"
                    it == "hdr10" -> "hdr"
                    else -> it
                },
            )
        }
        audioPattern.find(text)?.value?.let {
            add(
                when (val normalized = it.replace(".", "").replace("-", "")) {
                    "dd+", "eac3" -> "ddp"
                    else -> normalized
                },
            )
        }
        languagePattern.findAll(text).map { it.value }.distinct().sorted().forEach(::add)
        releaseGroup(behaviorHints.filename)?.let { add("grp:$it") }
    }
    if (tokens.isEmpty()) return null
    return "release:$addonId|${tokens.joinToString("|")}"
}

private val resolutionPattern = Regex("\\b(2160p|4k|uhd|1440p|1080p|720p|576p|480p)\\b")
private val sourcePattern = Regex("\\b(remux|blu-?ray|bdrip|brrip|web-?dl|web-?rip|hdtv|dvdrip|hdrip)\\b")
private val codecPattern = Regex("\\b(x265|h\\.?265|hevc|x264|h\\.?264|avc|av1|vp9)\\b")
private val hdrPattern = Regex("\\b(dolby ?vision|dovi|hdr10\\+|hdr10|hdr|sdr)\\b")
private val audioPattern = Regex("\\b(atmos|truehd|dts-?hd(?:\\.?ma)?|dts|ddp|dd\\+|eac3|ac3|aac|opus|flac)\\b")
private val languagePattern = Regex("\\b(dual|multi|dubbed|vostfr)\\b")
private val groupPattern = Regex("-([a-z0-9]{2,12})(?:\\.[a-z0-9]{2,4})?$")
private val notAGroup = Regex("^(\\d{3,4}p|x26[45]|hevc|web|dl|rip|bluray|hdr|dv)$")

private fun releaseGroup(filename: String?): String? {
    val name = filename?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    val group = groupPattern.find(name)?.groupValues?.get(1) ?: return null
    return group.takeUnless { notAGroup.matches(it) }
}

/**
 * The stream's own `bingeGroup` when the add-on provides one, otherwise a release key derived from
 * its description (see [releaseKey]). Use this wherever "is this the same release" is decided.
 */
internal val StreamItem.effectiveBingeGroup: String?
    get() = behaviorHints.bingeGroup?.takeIf { it.isNotBlank() } ?: releaseKey()
