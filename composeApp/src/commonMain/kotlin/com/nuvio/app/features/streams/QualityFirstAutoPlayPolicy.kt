package com.nuvio.app.features.streams

/**
 * Quality-first ranking for automatic playback.
 *
 * An explicit quality chosen by the user locks the first attempt to that exact
 * resolution. The fallback chain may step down through lower known resolutions,
 * then use unknown-quality sources as a last resort. It never adds a known tier
 * above the user's selection.
 */
object QualityFirstAutoPlayPolicy {

    fun availableQualities(
        streams: List<StreamItem>,
        debridEnabled: Boolean,
        p2pEnabled: Boolean = true,
    ): List<StreamAutoPlayPreferredQuality> {
        val availableHeights = streams.asSequence()
            .filter { it.isSelectableForQualityAutoPlay(debridEnabled, p2pEnabled) }
            .mapNotNull(::detectQualityHeight)
            .toSet()

        return StreamAutoPlayPreferredQuality.entries
            .filter { quality -> quality.targetHeight != null && quality.targetHeight in availableHeights }
            .sortedByDescending { it.targetHeight }
    }

    /**
     * For an explicit quality, only streams confidently classified at that exact
     * resolution are eligible. ANY means best-known quality first.
     */
    fun rankCandidates(
        streams: List<StreamItem>,
        requestedQuality: StreamAutoPlayPreferredQuality,
        debridEnabled: Boolean,
        preferredSourceOrder: List<String> = emptyList(),
        p2pEnabled: Boolean = true,
    ): List<StreamItem> {
        val targetHeight = requestedQuality.targetHeight
        val candidates = streams
            .filter { it.isSelectableForQualityAutoPlay(debridEnabled, p2pEnabled) }
            .distinctBy(::identityKey)
            .filter { stream ->
                val height = detectQualityHeight(stream)
                targetHeight == null || height == targetHeight
            }

        val sourceRank = preferredSourceOrder.withIndex().associate { it.value to it.index }
        // Parse tags once per candidate instead of running several regexes on every
        // comparator invocation. This keeps ranking responsive with large provider lists.
        val formatRanks = candidates.map(::formatPreferenceRank)
        val audioRanks = candidates.map(::audioPreferenceRank)
        return candidates.withIndex().sortedWith(
            compareBy<IndexedValue<StreamItem>> { indexed ->
                detectQualityHeight(indexed.value)?.let { height -> -height } ?: Int.MAX_VALUE
            }.thenBy { indexed ->
                sourceRank[indexed.value.addonName]
                    ?: sourceRank[indexed.value.addonId]
                    ?: Int.MAX_VALUE
            }.thenBy { indexed ->
                // Prefer premium source formats within a provider's configured tier.
                formatRanks[indexed.index]
            }.thenBy { indexed ->
                audioRanks[indexed.index]
            }.thenBy { it.index },
        ).map { it.value }
    }

    /**
     * Build the semi-automatic fallback chain. The selected resolution tier is
     * exhausted first, followed by lower known tiers. Unknown-quality sources are
     * last-resort candidates only; they are never classified as a confirmed tier.
     * ANY keeps the existing best-known-quality-first ordering.
     */
    fun rankCandidatesWithFallback(
        streams: List<StreamItem>,
        requestedQuality: StreamAutoPlayPreferredQuality,
        debridEnabled: Boolean,
        preferredSourceOrder: List<String> = emptyList(),
        p2pEnabled: Boolean = true,
    ): List<StreamItem> {
        val targetHeight = requestedQuality.targetHeight
            ?: return rankCandidates(
                streams = streams,
                requestedQuality = StreamAutoPlayPreferredQuality.ANY,
                debridEnabled = debridEnabled,
                preferredSourceOrder = preferredSourceOrder,
                p2pEnabled = p2pEnabled,
            )

        val knownQualityFallbacks = StreamAutoPlayPreferredQuality.entries
            .asSequence()
            .filter { quality -> quality.targetHeight != null && quality.targetHeight <= targetHeight }
            .sortedByDescending { it.targetHeight }
            .flatMap { quality ->
                rankCandidates(
                    streams = streams,
                    requestedQuality = quality,
                    debridEnabled = debridEnabled,
                    preferredSourceOrder = preferredSourceOrder,
                    p2pEnabled = p2pEnabled,
                ).asSequence()
            }
            .toList()

        // Do not silently drop metadata-poor but otherwise playable sources. Keep
        // them behind every confirmed tier so they cannot displace a known fallback.
        val unknownQualityFallbacks = rankCandidates(
            streams = streams.filter { detectQualityHeight(it) == null },
            requestedQuality = StreamAutoPlayPreferredQuality.ANY,
            debridEnabled = debridEnabled,
            preferredSourceOrder = preferredSourceOrder,
            p2pEnabled = p2pEnabled,
        )

        return (knownQualityFallbacks + unknownQualityFallbacks).distinctBy(::identityKey)
    }

    /**
     * Explicit quality selection starts as soon as the first source at that exact
     * tier is playable. If the selected tier has not appeared yet, keep fetching;
     * lower/unknown fallbacks are only allowed after discovery completes.
     *
     * ANY retains its existing best-available behaviour: start after discovery is
     * complete or once more than ten candidates are available from top-three sources.
     */
    fun shouldStartPlayback(
        requestedQuality: StreamAutoPlayPreferredQuality,
        selectedQualityCandidateCount: Int,
        topThreeCandidateCount: Int,
        searchFinished: Boolean,
    ): Boolean {
        if (searchFinished) return true
        return if (requestedQuality.targetHeight != null) {
            selectedQualityCandidateCount > 0
        } else {
            topThreeCandidateCount > 10
        }
    }

    /**
     * Prefer Blu-ray remux/disc sources, then Dolby Vision and HDR when candidates
     * are otherwise comparable. Resolution remains the primary ranking criterion,
     * so a format tag never overrides the requested quality ceiling.
     */
    private fun formatPreferenceRank(stream: StreamItem): Int {
        val metadata = listOf(
            stream.name,
            stream.title,
            stream.description,
            stream.behaviorHints.filename,
            stream.clientResolve?.stream?.raw?.parsed?.rawTitle,
            stream.clientResolve?.stream?.raw?.parsed?.parsedTitle,
            stream.clientResolve?.stream?.raw?.parsed?.quality,
        ).filterNotNull().joinToString(" ").lowercase()

        val bluRay = Regex("""\b(?:blu.?ray|bdremux|remux|bdrip|brrip)\b""").containsMatchIn(metadata)
        val dolbyVision = Regex("""\b(?:dolby.?vision|dovi)\b""").containsMatchIn(metadata)
        val hdr = Regex("""\b(?:hdr|hdr10\+?|hlg)\b""").containsMatchIn(metadata)
        // Disc/remux first; Dolby Vision next; HDR next; ordinary SDR last.
        return when {
            bluRay -> 0
            dolbyVision -> 1
            hdr -> 2
            else -> 3
        }
    }

    /** Prefer higher-fidelity audio when video quality and visual format are comparable. */
    private fun audioPreferenceRank(stream: StreamItem): Int {
        val metadata = listOf(
            stream.name,
            stream.title,
            stream.description,
            stream.behaviorHints.filename,
            stream.clientResolve?.stream?.raw?.parsed?.rawTitle,
            stream.clientResolve?.stream?.raw?.parsed?.parsedTitle,
        ).filterNotNull().joinToString(" ").lowercase()

        return when {
            Regex("""\b(?:truehd|dolby.?atmos|dts.?hd.?ma|dts.?x)\b""").containsMatchIn(metadata) -> 0
            Regex("""\b(?:flac|pcm|lpcm|dts.?hd|dts.?ma)\b""").containsMatchIn(metadata) -> 1
            Regex("""\b(?:eac3|e.?ac.?3|ddp|ac3|dolby.?digital.?plus)\b""").containsMatchIn(metadata) -> 2
            Regex("""\b(?:aac|mp3|stereo)\b""").containsMatchIn(metadata) -> 3
            else -> 4
        }
    }

    /**
     * Metadata precedence is intentional: parser resolution, filename, parsed
     * titles, parsed quality, then provider labels/descriptions. A loose "4K"
     * description must not override a concrete 1080p filename or 1920x1080 size.
     */
    fun detectQualityHeight(stream: StreamItem): Int? {
        val parsed = stream.clientResolve?.stream?.raw?.parsed

        parsed?.resolution?.let(::qualityHeightIn)?.let { return it }
        stream.behaviorHints.filename?.let(::qualityHeightIn)?.let { return it }
        parsed?.parsedTitle?.let(::qualityHeightIn)?.let { return it }
        parsed?.rawTitle?.let(::qualityHeightIn)?.let { return it }
        parsed?.quality?.let(::qualityHeightIn)?.let { return it }
        stream.name?.let(::qualityHeightIn)?.let { return it }
        stream.title?.let(::qualityHeightIn)?.let { return it }
        return stream.description?.let(::qualityHeightIn)
    }

    private fun qualityHeightIn(metadata: String): Int? {
        val dimensions = Regex("""(?i)\b\d{3,4}\s*[x×]\s*(4320|2160|1440|1080|720|480|360)\b""")
            .find(metadata)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (dimensions != null) return dimensions

        val markers = listOf(
            Regex("""(?i)(?:\b4320p?\b|\b8k\b)""") to 4320,
            Regex("""(?i)(?:\b2160p?\b|\b4k\b|\buhd\b)""") to 2160,
            Regex("""(?i)(?:\b1440p?\b|\b2k\b|\bqhd\b)""") to 1440,
            Regex("""(?i)(?:\b1080p?\b|\bfhd\b|\bfull\s*hd\b)""") to 1080,
            Regex("""(?i)\b720p\b""") to 720,
            Regex("""(?i)\b480p?\b""") to 480,
            Regex("""(?i)\b360p?\b""") to 360,
        )
        return markers.firstOrNull { (regex, _) -> regex.containsMatchIn(metadata) }?.second
    }

    private fun StreamItem.isSelectableForQualityAutoPlay(
        debridEnabled: Boolean,
        p2pEnabled: Boolean,
    ): Boolean =
        isSelectableForPlayback(debridEnabled) && (p2pEnabled || !isTorrentStream)

    private fun identityKey(stream: StreamItem): String {
        stream.playableDirectUrl?.let { return "url:$it" }
        stream.p2pInfoHash?.let { return "p2p:${it.lowercase()}:${stream.p2pFileIdx ?: -1}" }
        stream.serverTarget?.let { return "server:${it.item.encode()}:${stream.serverTarget?.mediaSourceId.orEmpty()}" }
        return "stream:${stream.addonId}:${stream.streamLabel}:${stream.behaviorHints.filename.orEmpty()}"
    }
}
