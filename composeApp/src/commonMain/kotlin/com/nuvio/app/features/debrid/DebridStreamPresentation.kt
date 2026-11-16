package com.nuvio.app.features.debrid

import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamItem
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

object DebridStreamPresentation {
    private val formatter = DebridStreamFormatter()

    fun apply(groups: List<AddonStreamGroup>, settings: DebridSettings): List<AddonStreamGroup> {
        if (!settings.canResolvePlayableLinks) return groups
        return groups.map { group ->
            val visibleStreams = group.streams
                .filterNot { stream -> stream.isInactiveResolverStream(settings) }
                .filterNot { stream -> stream.isUncachedDebridStream }
            val debridStreams = visibleStreams.filter { stream -> stream.isManagedDebridStream }
            if (debridStreams.isEmpty()) return@map group.copy(streams = visibleStreams)

            val shouldFormatStreams = settings.hasCustomStreamFormatting ||
                debridStreams.any { stream -> stream.badges.isNotEmpty() }
            val presentedDebridStreams = applyPreferencesWithFacts(debridStreams, settings)
                .map { (stream, facts) ->
                    if (shouldFormatStreams) {
                        formatter.format(stream, settings, facts)
                    } else {
                        stream
                    }
                }
            val passthroughStreams = visibleStreams.filterNot { stream -> stream.isManagedDebridStream }

            group.copy(streams = presentedDebridStreams + passthroughStreams)
        }
    }

    internal fun applyPreferences(streams: List<StreamItem>, settings: DebridSettings): List<StreamItem> =
        applyPreferencesWithFacts(streams, settings).map { it.first }

    /** Same as [applyPreferences], but keeps each stream's facts so callers don't recompute them. */
    internal fun applyPreferencesWithFacts(
        streams: List<StreamItem>,
        settings: DebridSettings,
    ): List<Pair<StreamItem, DebridStreamFacts>> {
        val preferences = DebridStreamMetadata.effectivePreferences(settings)
        val matchedStreams = streams.map { it to DebridStreamMetadata.facts(it, preferences) }
            .filter { (_, facts) -> facts.matchesFilters(preferences) }

        val orderedStreams = if (preferences.sortCriteria.isEmpty()) {
            matchedStreams
        } else {
            matchedStreams.sortedWith { left, right ->
                compareFacts(left.second, right.second, preferences.sortCriteria)
            }
        }

        return applyLimits(orderedStreams, preferences)
    }

    internal val StreamItem.isManagedDebridStream: Boolean
        get() {
            val status = debridCacheStatus
            return isAddonDebridCandidate && (isDirectDebridStream || (
                isTorrentStream &&
                    status != null &&
                    DebridProviders.byId(status.providerId)?.supports(DebridProviderCapability.LocalTorrentCacheCheck) == true &&
                    status.state != StreamDebridCacheState.CHECKING
            ))
        }

    private val StreamItem.isUncachedDebridStream: Boolean
        get() = isInstalledAddonStream &&
            DebridProviders.byId(debridCacheStatus?.providerId)?.supports(DebridProviderCapability.LocalTorrentCacheCheck) == true &&
            debridCacheStatus?.state == StreamDebridCacheState.NOT_CACHED

    private fun StreamItem.isInactiveResolverStream(settings: DebridSettings): Boolean {
        val streamProviderId = DebridProviders.byId(clientResolve?.service)?.id ?: return false
        val activeProviderId = settings.activeResolverProviderId ?: return false
        return isDirectDebridStream && streamProviderId != activeProviderId
    }

    private fun applyLimits(
        streams: List<Pair<StreamItem, DebridStreamFacts>>,
        preferences: DebridStreamPreferences,
    ): List<Pair<StreamItem, DebridStreamFacts>> {
        val resolutionCounts = mutableMapOf<DebridStreamResolution, Int>()
        val qualityCounts = mutableMapOf<DebridStreamQuality, Int>()
        val result = mutableListOf<Pair<StreamItem, DebridStreamFacts>>()
        for (stream in streams) {
            if (preferences.maxResults > 0 && result.size >= preferences.maxResults) break
            if (preferences.maxPerResolution > 0) {
                val count = resolutionCounts[stream.second.resolution] ?: 0
                if (count >= preferences.maxPerResolution) continue
            }
            if (preferences.maxPerQuality > 0) {
                val count = qualityCounts[stream.second.quality] ?: 0
                if (count >= preferences.maxPerQuality) continue
            }
            resolutionCounts[stream.second.resolution] = (resolutionCounts[stream.second.resolution] ?: 0) + 1
            qualityCounts[stream.second.quality] = (qualityCounts[stream.second.quality] ?: 0) + 1
            result += stream
        }
        return result
    }

    private fun DebridStreamFacts.matchesFilters(preferences: DebridStreamPreferences): Boolean {
        if (preferences.requiredResolutions.isNotEmpty() && resolution !in preferences.requiredResolutions) return false
        if (resolution in preferences.excludedResolutions) return false
        if (preferences.requiredQualities.isNotEmpty() && quality !in preferences.requiredQualities) return false
        if (quality in preferences.excludedQualities) return false
        if (preferences.requiredVisualTags.isNotEmpty() && visualTags.none { it in preferences.requiredVisualTags }) return false
        if (visualTags.any { it in preferences.excludedVisualTags }) return false
        if (preferences.requiredAudioTags.isNotEmpty() && audioTags.none { it in preferences.requiredAudioTags }) return false
        if (audioTags.any { it in preferences.excludedAudioTags }) return false
        if (preferences.requiredAudioChannels.isNotEmpty() && audioChannels.none { it in preferences.requiredAudioChannels }) return false
        if (audioChannels.any { it in preferences.excludedAudioChannels }) return false
        if (preferences.requiredEncodes.isNotEmpty() && encode !in preferences.requiredEncodes) return false
        if (encode in preferences.excludedEncodes) return false
        if (preferences.requiredLanguages.isNotEmpty() && languages.none { it in preferences.requiredLanguages }) return false
        if (languages.isNotEmpty() && languages.all { it in preferences.excludedLanguages }) return false
        if (preferences.requiredReleaseGroups.isNotEmpty() && preferences.requiredReleaseGroups.none { releaseGroup.equals(it, ignoreCase = true) }) return false
        if (preferences.excludedReleaseGroups.any { releaseGroup.equals(it, ignoreCase = true) }) return false
        if (preferences.sizeMinGb > 0 && size != null && size < preferences.sizeMinGb.gigabytes()) return false
        if (preferences.sizeMaxGb > 0 && size != null && size > preferences.sizeMaxGb.gigabytes()) return false
        return true
    }

    private fun compareFacts(
        left: DebridStreamFacts,
        right: DebridStreamFacts,
        criteria: List<DebridStreamSortCriterion>,
    ): Int {
        for (criterion in criteria) {
            val comparison = compareKey(left, right, criterion)
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun compareKey(
        left: DebridStreamFacts,
        right: DebridStreamFacts,
        criterion: DebridStreamSortCriterion,
    ): Int {
        val direction = if (criterion.direction == DebridStreamSortDirection.ASC) 1 else -1
        return when (criterion.key) {
            DebridStreamSortKey.RESOLUTION -> left.resolutionRank.compareTo(right.resolutionRank) * -direction
            DebridStreamSortKey.QUALITY -> left.qualityRank.compareTo(right.qualityRank) * -direction
            DebridStreamSortKey.VISUAL_TAG -> left.visualRank.compareTo(right.visualRank) * -direction
            DebridStreamSortKey.AUDIO_TAG -> left.audioRank.compareTo(right.audioRank) * -direction
            DebridStreamSortKey.AUDIO_CHANNEL -> left.channelRank.compareTo(right.channelRank) * -direction
            DebridStreamSortKey.ENCODE -> left.encodeRank.compareTo(right.encodeRank) * -direction
            DebridStreamSortKey.SIZE -> (left.size ?: 0L).compareTo(right.size ?: 0L) * direction
            DebridStreamSortKey.LANGUAGE -> left.languageRank.compareTo(right.languageRank) * -direction
            DebridStreamSortKey.RELEASE_GROUP -> left.releaseGroup.compareTo(right.releaseGroup, ignoreCase = true)
        }
    }
}

internal object DebridStreamMetadata {
    private val tokenRegexLock = SynchronizedObject()
    private val tokenRegexCache = HashMap<String, Regex>()
    private val dolbyVisionRegex = Regex("(^|[^a-z0-9])(dv|dovi|dolby[ ._-]?vision)([^a-z0-9]|\$)")
    private val hdrRegex = Regex("(^|[^a-z0-9])(hdr|hdr10|hdr10plus|hdr10\\+|hlg)([^a-z0-9]|\$)")
    private val releaseGroupRegex = Regex("-([a-z0-9][a-z0-9._]{1,24})($|\\.)", RegexOption.IGNORE_CASE)
    private val nonAlphanumericRegex = Regex("[^a-z0-9]")
    private val nonAlphanumericOrPlusRegex = Regex("[^a-z0-9+]")
    private val resolutionTokens = listOf(
        resolutionRegex("2160p?", "4k", "uhd") to DebridStreamResolution.P2160,
        resolutionRegex("1440p?", "2k") to DebridStreamResolution.P1440,
        resolutionRegex("1080p?", "fhd") to DebridStreamResolution.P1080,
        resolutionRegex("720p?", "hd") to DebridStreamResolution.P720,
        resolutionRegex("576p?") to DebridStreamResolution.P576,
        resolutionRegex("480p?", "sd") to DebridStreamResolution.P480,
        resolutionRegex("360p?") to DebridStreamResolution.P360,
    )

    // Words release names use for each language besides its code. Short words that are common in
    // titles ("por", "chi", "fra") are left out so a title is not read as a language.
    private val languageAliases = mapOf(
        DebridStreamLanguage.EN to listOf("eng", "english"),
        DebridStreamLanguage.HI to listOf("hin", "hindi"),
        DebridStreamLanguage.IT to listOf("ita", "italian", "italiano"),
        DebridStreamLanguage.ES to listOf("spa", "esp", "spanish", "espanol", "castellano"),
        DebridStreamLanguage.FR to listOf("fre", "french", "truefrench"),
        DebridStreamLanguage.DE to listOf("ger", "deu", "german", "deutsch"),
        DebridStreamLanguage.PT to listOf("portuguese", "portugues"),
        DebridStreamLanguage.PL to listOf("pol", "polish"),
        DebridStreamLanguage.CS to listOf("cze", "czech"),
        DebridStreamLanguage.LA to listOf("latino"),
        DebridStreamLanguage.JA to listOf("jpn", "japanese"),
        DebridStreamLanguage.KO to listOf("kor", "korean"),
        DebridStreamLanguage.ZH to listOf("chinese"),
    )
    private val languageRegexes = DebridStreamLanguage.entries.map { language ->
        language to languageRegex(listOf(language.code) + languageAliases[language].orEmpty())
    }
    private val nonAlphanumericRunRegex = Regex("[^a-z0-9]+")

    fun effectivePreferences(settings: DebridSettings): DebridStreamPreferences {
        val default = DebridStreamPreferences()
        if (settings.streamPreferences != default) return settings.streamPreferences.normalized()
        if (
            settings.streamMaxResults == 0 &&
            settings.streamSortMode == DebridStreamSortMode.DEFAULT &&
            settings.streamMinimumQuality == DebridStreamMinimumQuality.ANY &&
            settings.streamDolbyVisionFilter == DebridStreamFeatureFilter.ANY &&
            settings.streamHdrFilter == DebridStreamFeatureFilter.ANY &&
            settings.streamCodecFilter == DebridStreamCodecFilter.ANY
        ) {
            return default
        }
        var preferences = default.copy(
            maxResults = settings.streamMaxResults,
            sortCriteria = when (settings.streamSortMode) {
                DebridStreamSortMode.DEFAULT -> default.sortCriteria
                DebridStreamSortMode.QUALITY_DESC -> listOf(
                    DebridStreamSortCriterion(DebridStreamSortKey.RESOLUTION, DebridStreamSortDirection.DESC),
                    DebridStreamSortCriterion(DebridStreamSortKey.QUALITY, DebridStreamSortDirection.DESC),
                    DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.DESC),
                )
                DebridStreamSortMode.SIZE_DESC -> listOf(
                    DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.DESC),
                )
                DebridStreamSortMode.SIZE_ASC -> listOf(
                    DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.ASC),
                )
            },
            requiredResolutions = DebridStreamResolution.defaultOrder.filter {
                it.value >= settings.streamMinimumQuality.minResolution && it != DebridStreamResolution.UNKNOWN
            },
        )
        preferences = when (settings.streamDolbyVisionFilter) {
            DebridStreamFeatureFilter.ANY -> preferences
            DebridStreamFeatureFilter.EXCLUDE -> preferences.copy(
                excludedVisualTags = preferences.excludedVisualTags + listOf(
                    DebridStreamVisualTag.DV,
                    DebridStreamVisualTag.DV_ONLY,
                    DebridStreamVisualTag.HDR_DV,
                ),
            )
            DebridStreamFeatureFilter.ONLY -> preferences.copy(
                requiredVisualTags = preferences.requiredVisualTags + listOf(
                    DebridStreamVisualTag.DV,
                    DebridStreamVisualTag.DV_ONLY,
                    DebridStreamVisualTag.HDR_DV,
                ),
            )
        }
        preferences = when (settings.streamHdrFilter) {
            DebridStreamFeatureFilter.ANY -> preferences
            DebridStreamFeatureFilter.EXCLUDE -> preferences.copy(
                excludedVisualTags = preferences.excludedVisualTags + listOf(
                    DebridStreamVisualTag.HDR,
                    DebridStreamVisualTag.HDR10,
                    DebridStreamVisualTag.HDR10_PLUS,
                    DebridStreamVisualTag.HLG,
                    DebridStreamVisualTag.HDR_ONLY,
                    DebridStreamVisualTag.HDR_DV,
                ),
            )
            DebridStreamFeatureFilter.ONLY -> preferences.copy(
                requiredVisualTags = preferences.requiredVisualTags + listOf(
                    DebridStreamVisualTag.HDR,
                    DebridStreamVisualTag.HDR10,
                    DebridStreamVisualTag.HDR10_PLUS,
                    DebridStreamVisualTag.HLG,
                    DebridStreamVisualTag.HDR_ONLY,
                    DebridStreamVisualTag.HDR_DV,
                ),
            )
        }
        return when (settings.streamCodecFilter) {
            DebridStreamCodecFilter.ANY -> preferences
            DebridStreamCodecFilter.H264 -> preferences.copy(requiredEncodes = listOf(DebridStreamEncode.AVC))
            DebridStreamCodecFilter.HEVC -> preferences.copy(requiredEncodes = listOf(DebridStreamEncode.HEVC))
            DebridStreamCodecFilter.AV1 -> preferences.copy(requiredEncodes = listOf(DebridStreamEncode.AV1))
        }.normalized()
    }

    fun facts(stream: StreamItem, preferences: DebridStreamPreferences): DebridStreamFacts {
        val parsed = stream.clientResolve?.stream?.raw?.parsed
        val searchText = streamSearchText(stream)
        val resolution = streamResolution(parsed?.resolution, parsed?.quality, searchText)
        val quality = streamQuality(parsed?.quality, searchText)
        val visualTags = streamVisualTags(parsed?.hdr.orEmpty(), searchText)
        val audioTags = streamAudioTags(parsed?.audio.orEmpty(), searchText)
        val audioChannels = streamAudioChannels(parsed?.channels.orEmpty(), searchText)
        val encode = streamEncode(parsed?.codec, searchText)
        val languages = parsed?.languages.orEmpty().mapNotNull { languageFor(it) }.ifEmpty {
            val languageText = languageSearchText(stream)
            languageRegexes.filter { (_, regex) -> regex.containsMatchIn(languageText) }.map { it.first }
        }
        val releaseGroup = parsed?.group?.takeIf { it.isNotBlank() } ?: releaseGroupFromText(searchText)
        return DebridStreamFacts(
            resolution = resolution,
            quality = quality,
            visualTags = visualTags,
            audioTags = audioTags,
            audioChannels = audioChannels,
            encode = encode,
            languages = languages,
            releaseGroup = releaseGroup,
            size = streamSize(stream),
            resolutionRank = rank(resolution, preferences.preferredResolutions),
            qualityRank = rank(quality, preferences.preferredQualities),
            visualRank = rankAny(visualTags, preferences.preferredVisualTags),
            audioRank = rankAny(audioTags, preferences.preferredAudioTags),
            channelRank = rankAny(audioChannels, preferences.preferredAudioChannels),
            encodeRank = rank(encode, preferences.preferredEncodes),
            languageRank = if (languages.isEmpty()) Int.MAX_VALUE else languages.minOf { rank(it, preferences.preferredLanguages) },
        )
    }

    private fun streamResolution(vararg values: String?): DebridStreamResolution =
        values.firstNotNullOfOrNull { resolutionValue(it) } ?: DebridStreamResolution.UNKNOWN

    private fun resolutionValue(value: String?): DebridStreamResolution? {
        val normalized = value?.lowercase().orEmpty()
        return resolutionTokens.firstOrNull { (regex, _) -> regex.containsMatchIn(normalized) }?.second
    }

    private fun streamQuality(parsedQuality: String?, searchText: String): DebridStreamQuality {
        val text = listOfNotNull(parsedQuality, searchText).joinToString(" ").lowercase()
        return when {
            text.contains("remux") -> DebridStreamQuality.BLURAY_REMUX
            text.contains("blu-ray") || text.contains("bluray") || text.contains("bdrip") || text.contains("brrip") -> DebridStreamQuality.BLURAY
            text.contains("web-dl") || text.contains("webdl") -> DebridStreamQuality.WEB_DL
            text.contains("webrip") || text.contains("web-rip") -> DebridStreamQuality.WEBRIP
            text.contains("hdrip") -> DebridStreamQuality.HDRIP
            text.contains("hd-rip") || text.contains("hcrip") -> DebridStreamQuality.HD_RIP
            text.contains("dvdrip") -> DebridStreamQuality.DVDRIP
            text.contains("hdtv") -> DebridStreamQuality.HDTV
            text.hasToken("cam") -> DebridStreamQuality.CAM
            text.hasToken("ts") -> DebridStreamQuality.TS
            text.hasToken("tc") -> DebridStreamQuality.TC
            text.hasToken("scr") -> DebridStreamQuality.SCR
            else -> DebridStreamQuality.UNKNOWN
        }
    }

    private fun streamVisualTags(parsedHdr: List<String>, searchText: String): List<DebridStreamVisualTag> {
        val text = (parsedHdr + searchText).joinToString(" ").lowercase()
        val tags = mutableListOf<DebridStreamVisualTag>()
        val hasDv = parsedHdr.any { it.isDolbyVisionToken() } || dolbyVisionRegex.containsMatchIn(searchText)
        val hasHdr = parsedHdr.any { it.isHdrToken() } || hdrRegex.containsMatchIn(searchText)
        if (hasDv && hasHdr) tags += DebridStreamVisualTag.HDR_DV
        if (hasDv && !hasHdr) tags += DebridStreamVisualTag.DV_ONLY
        if (hasHdr && !hasDv) tags += DebridStreamVisualTag.HDR_ONLY
        if (text.contains("hdr10+") || text.contains("hdr10plus")) tags += DebridStreamVisualTag.HDR10_PLUS
        if (text.contains("hdr10")) tags += DebridStreamVisualTag.HDR10
        if (hasDv) tags += DebridStreamVisualTag.DV
        if (hasHdr) tags += DebridStreamVisualTag.HDR
        if (text.hasToken("hlg")) tags += DebridStreamVisualTag.HLG
        if (text.contains("10bit") || text.contains("10 bit")) tags += DebridStreamVisualTag.TEN_BIT
        if (text.hasToken("3d")) tags += DebridStreamVisualTag.THREE_D
        if (text.hasToken("imax")) tags += DebridStreamVisualTag.IMAX
        if (text.hasToken("ai")) tags += DebridStreamVisualTag.AI
        if (text.hasToken("sdr")) tags += DebridStreamVisualTag.SDR
        if (text.contains("h-ou")) tags += DebridStreamVisualTag.H_OU
        if (text.contains("h-sbs")) tags += DebridStreamVisualTag.H_SBS
        return tags.distinct().ifEmpty { listOf(DebridStreamVisualTag.UNKNOWN) }
    }

    private fun streamAudioTags(parsedAudio: List<String>, searchText: String): List<DebridStreamAudioTag> {
        val text = (parsedAudio + searchText).joinToString(" ").lowercase()
        val tags = mutableListOf<DebridStreamAudioTag>()
        if (text.hasToken("atmos")) tags += DebridStreamAudioTag.ATMOS
        if (text.contains("dd+") || text.contains("ddp") || text.contains("dolby digital plus")) tags += DebridStreamAudioTag.DD_PLUS
        if (text.hasToken("dd") || text.contains("ac3") || text.contains("dolby digital")) tags += DebridStreamAudioTag.DD
        if (text.contains("dts:x") || text.contains("dtsx")) tags += DebridStreamAudioTag.DTS_X
        if (text.contains("dts-hd ma") || text.contains("dtshd ma")) tags += DebridStreamAudioTag.DTS_HD_MA
        if (text.contains("dts-hd") || text.contains("dtshd")) tags += DebridStreamAudioTag.DTS_HD
        if (text.contains("dts-es") || text.contains("dtses")) tags += DebridStreamAudioTag.DTS_ES
        if (text.hasToken("dts")) tags += DebridStreamAudioTag.DTS
        if (text.contains("truehd") || text.contains("true hd")) tags += DebridStreamAudioTag.TRUEHD
        if (text.hasToken("opus")) tags += DebridStreamAudioTag.OPUS
        if (text.hasToken("flac")) tags += DebridStreamAudioTag.FLAC
        if (text.hasToken("aac")) tags += DebridStreamAudioTag.AAC
        return tags.distinct().ifEmpty { listOf(DebridStreamAudioTag.UNKNOWN) }
    }

    private fun streamAudioChannels(parsedChannels: List<String>, searchText: String): List<DebridStreamAudioChannel> {
        val text = (parsedChannels + searchText).joinToString(" ").lowercase()
        val channels = mutableListOf<DebridStreamAudioChannel>()
        if (text.hasToken("7.1")) channels += DebridStreamAudioChannel.CH_7_1
        if (text.hasToken("6.1")) channels += DebridStreamAudioChannel.CH_6_1
        if (text.hasToken("5.1") || text.hasToken("6ch")) channels += DebridStreamAudioChannel.CH_5_1
        if (text.hasToken("2.0")) channels += DebridStreamAudioChannel.CH_2_0
        return channels.distinct().ifEmpty { listOf(DebridStreamAudioChannel.UNKNOWN) }
    }

    private fun streamEncode(parsedCodec: String?, searchText: String): DebridStreamEncode {
        val text = listOfNotNull(parsedCodec, searchText).joinToString(" ").lowercase()
        return when {
            text.hasToken("av1") -> DebridStreamEncode.AV1
            text.hasToken("hevc") || text.hasToken("h265") || text.hasToken("x265") -> DebridStreamEncode.HEVC
            text.hasToken("avc") || text.hasToken("h264") || text.hasToken("x264") -> DebridStreamEncode.AVC
            text.hasToken("xvid") -> DebridStreamEncode.XVID
            text.hasToken("divx") -> DebridStreamEncode.DIVX
            else -> DebridStreamEncode.UNKNOWN
        }
    }

    private fun languageFor(value: String): DebridStreamLanguage? {
        val normalized = value.trim().lowercase()
        return DebridStreamLanguage.entries.firstOrNull {
            normalized == it.code ||
                normalized == it.label.lowercase() ||
                normalized in languageAliases[it].orEmpty()
        }
    }

    private fun releaseGroupFromText(text: String): String =
        releaseGroupRegex
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()

    private fun <T> rank(value: T, preferred: List<T>): Int {
        val index = preferred.indexOf(value)
        return if (index >= 0) index else Int.MAX_VALUE
    }

    private fun <T> rankAny(values: List<T>, preferred: List<T>): Int =
        values.minOfOrNull { rank(it, preferred) } ?: Int.MAX_VALUE

    private fun resolutionRegex(vararg tokens: String): Regex =
        Regex("(^|[^a-z0-9])(${tokens.joinToString("|")})([^a-z0-9]|\$)")

    private fun languageRegex(tokens: List<String>): Regex =
        Regex("(^|[^a-z0-9])(${tokens.joinToString("|") { Regex.escape(it) }})([^a-z0-9]|\$)")

    /** The receiver must already be lowercase: every caller builds its text with lowercase(). */
    private fun String.hasToken(token: String): Boolean {
        val regex = synchronized(tokenRegexLock) {
            tokenRegexCache.getOrPut(token) {
                Regex("(^|[^a-z0-9])${Regex.escape(token.lowercase())}([^a-z0-9]|\$)")
            }
        }
        return regex.containsMatchIn(this)
    }

    private fun String.isDolbyVisionToken(): Boolean {
        val normalized = lowercase().replace(nonAlphanumericRegex, "")
        return normalized == "dv" || normalized == "dovi" || normalized == "dolbyvision"
    }

    private fun String.isHdrToken(): Boolean {
        val normalized = lowercase().replace(nonAlphanumericOrPlusRegex, "")
        return normalized == "hdr" ||
            normalized == "hdr10" ||
            normalized == "hdr10+" ||
            normalized == "hdr10plus" ||
            normalized == "hlg"
    }

    private fun streamSize(stream: StreamItem): Long? =
        stream.clientResolve?.stream?.raw?.size
            ?: stream.behaviorHints.videoSize
            ?: stream.debridCacheStatus?.cachedSize

    private fun streamSearchText(stream: StreamItem): String {
        val parsed = stream.clientResolve?.stream?.raw?.parsed
        val parsedTexts = listOfNotNull(
            parsed?.resolution,
            parsed?.quality,
            parsed?.codec,
            parsed?.hdr?.joinToString(" "),
            parsed?.audio?.joinToString(" "),
        )
        return (streamTexts(stream) + parsedTexts).joinToString(" ").lowercase()
    }

    private fun streamTexts(stream: StreamItem): List<String> {
        val resolve = stream.clientResolve
        val raw = resolve?.stream?.raw
        return listOfNotNull(
            stream.name,
            stream.title,
            stream.description,
            stream.behaviorHints.filename,
            stream.debridCacheStatus?.cachedName,
            resolve?.torrentName,
            resolve?.filename,
            raw?.torrentName,
            raw?.filename,
        )
    }

    // Removes the media title from each text before languages are matched, so title words
    // ("The French Dispatch", "The Italian Job") are not read as the stream language. Only the
    // first match per text is removed: a release name repeats the title only as a language word.
    private fun languageSearchText(stream: StreamItem): String {
        val resolve = stream.clientResolve
        val titleRegexes = listOfNotNull(resolve?.stream?.raw?.parsed?.parsedTitle, resolve?.title)
            .mapNotNull { titleRegex(it) }
            .distinctBy { it.pattern }
            .sortedByDescending { it.pattern.length }
        return streamTexts(stream).joinToString(" ") { text ->
            titleRegexes.fold(text.lowercase()) { remaining, regex -> regex.replaceFirst(remaining, " ") }
        }
    }

    private fun titleRegex(title: String): Regex? {
        val words = title.lowercase().split(nonAlphanumericRunRegex).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        return Regex("(^|[^a-z0-9])${words.joinToString("[^a-z0-9]+")}(?=[^a-z0-9]|\$)")
    }
}

internal data class DebridStreamFacts(
    val resolution: DebridStreamResolution,
    val quality: DebridStreamQuality,
    val visualTags: List<DebridStreamVisualTag>,
    val audioTags: List<DebridStreamAudioTag>,
    val audioChannels: List<DebridStreamAudioChannel>,
    val encode: DebridStreamEncode,
    val languages: List<DebridStreamLanguage>,
    val releaseGroup: String,
    val size: Long?,
    val resolutionRank: Int,
    val qualityRank: Int,
    val visualRank: Int,
    val audioRank: Int,
    val channelRank: Int,
    val encodeRank: Int,
    val languageRank: Int,
)

private fun Int.gigabytes(): Long = this * 1_000_000_000L
