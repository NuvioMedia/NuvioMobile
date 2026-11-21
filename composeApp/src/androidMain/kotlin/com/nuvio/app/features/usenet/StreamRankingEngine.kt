package com.nuvio.app.features.usenet

import com.google.re2j.Pattern
import java.util.Locale
import java.util.regex.Pattern as NativePattern

/** User-written profile patterns run on RE2J, which rejects lookaround and backreferences. */
internal actual fun isValidRankingPattern(value: String): Boolean =
    runCatching { Pattern.compile(value, Pattern.CASE_INSENSITIVE) }.isSuccess

/** Facts are independent of preferences and parsed once for each incoming release. */
internal data class StreamRankingFacts(
    val title: String,
    val resolution: Int,
    val quality: StreamReleaseQuality,
    val codec: String,
    val visual: Set<String>,
    val audio: Set<String>,
    val knownLanguages: Set<String>? = null,
    val group: String,
    val sizeBytes: Long? = null,
    val publishedAt: Long? = null,
    val indexer: String = "",
    val mediaType: String = "movie"
) {
    val languages: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        knownLanguages ?: StreamReleaseParser.languages(title)
    }
    companion object {
        fun parse(title: String): StreamRankingFacts = StreamReleaseParser.parse(title)
    }
}

/**
 * Its own fixed patterns run on java.util.regex, native on Android and about twice as fast as RE2J
 * for them; they stay linear in the bounded title, since none nests or leaves open a quantifier.
 * Patterns that users write in profiles stay on RE2J. `\z` ends the text as `$` does in RE2J
 * (java.util.regex's `$` also matches before a final line break).
 */
private object StreamReleaseParser {
    private fun pattern(value: String) = NativePattern.compile(value, NativePattern.CASE_INSENSITIVE)
    private fun token(value: String) = pattern("(?:^|[^a-z0-9])(?:$value)(?:\\z|[^a-z0-9])")
    private val resolution = pattern("(?:^|[^0-9])(2160|1440|1080|720|576|480|360)p?(?:\\z|[^0-9])")
    private val uhd = token("4k|uhd")
    private val remux = token("remux")
    private val bluray = token("blu[ ._-]?ray|b[dr]rip|bdremux|b[dr]mv")
    private val webDl = token("web[ ._-]?dl|webdl")
    private val web = token("web")
    private val webRip = token("web[ ._-]?rip")
    private val hdtv = token("hdtv")
    private val dvd = token("dvd|dvdrip|dvdremux")
    private val cam = token("cam|hdcam|telesync|telecine|hdts|ts|tc|scr|dvdscr")
    private val hevc = token("hevc|[xh][ ._-]?265|h265")
    private val avc = token("avc|[xh][ ._-]?264|h264")
    private val av1 = token("av1")
    private val dv = token("dv|dovi|dolby[ ._-]?vision")
    private val hdr = token("hdr|hdr10|hdr10plus|hdr10\\+|hlg")
    private val hdrPlus = token("hdr10plus|hdr10\\+")
    private val sdr = token("sdr")
    private val atmos = token("atmos")
    private val trueHd = token("true[ ._-]?hd")
    private val dtsHd = token("dts[ ._-]?(?:hd|x)")
    private val ddp = token("ddp|e[ ._-]?ac3|dd\\+")
    private val aac = token("aac")
    private val group = pattern("-((?:D-Z0N3)|[a-z0-9][a-z0-9._]{1,31})\\z")
    private val animeGroup = pattern("^\\[([a-z][a-z0-9 _-]{1,30})\\]")
    private val extension = pattern("\\.(?:mkv|mp4|m2ts|ts|nzb)\\z")
    private val languagePatterns = linkedMapOf(
        "en" to token("english|eng|en"), "ja" to token("japanese|jpn|ja"), "de" to token("german|ger|deu|de"),
        "fr" to token("french|fre|fra|vff|vfq|truefrench|fr"), "nl" to token("dutch|nld|nederlands|nl"),
        "es" to token("spanish|spa|castellano|es"), "it" to token("italian|ita|it"), "pt" to token("portuguese|por|ptbr|pt"),
        "ko" to token("korean|kor|ko"), "zh" to token("chinese|chi|zho|mandarin|zh"), "hi" to token("hindi|hin|hi"),
        "ru" to token("russian|rus|ru"), "uk" to token("ukrainian|ukr"), "ar" to token("arabic|ara"),
        "pl" to token("polish|pol"), "tr" to token("turkish|tur"), "multi" to token("multi|multi[ ._-]?audio"),
        "dual" to token("dual|dual[ ._-]?audio")
    )
    private fun NativePattern.has(text: String) = matcher(text).find()
    fun languages(title: String): Set<String> = languagePatterns.filterValues { it.has(title) }.keys.toSet()
    fun parse(title: String): StreamRankingFacts {
        // Bound custom/indexer input before applying even linear-time matchers.
        val text = extension.matcher(title.take(2048)).replaceFirst("")
        val match = resolution.matcher(text)
        val pixels = if (match.find()) match.group(1).toInt() else if (uhd.has(text)) 2160 else 0
        val quality = when {
            cam.has(text) -> StreamReleaseQuality.CAM
            remux.has(text) -> StreamReleaseQuality.REMUX
            bluray.has(text) -> StreamReleaseQuality.BLURAY
            webRip.has(text) -> StreamReleaseQuality.WEBRIP
            webDl.has(text) -> StreamReleaseQuality.WEB_DL
            hdtv.has(text) -> StreamReleaseQuality.HDTV
            dvd.has(text) -> StreamReleaseQuality.DVD
            web.has(text) -> StreamReleaseQuality.WEB_DL
            else -> StreamReleaseQuality.UNKNOWN
        }
        val hasDv = dv.has(text)
        val hasHdr = hdr.has(text)
        val visual = buildSet {
            if (hasDv) add(if (hasHdr) "HDR_DV" else "DV_ONLY")
            if (hasDv) add("DV")
            if (hasHdr) add("HDR")
            if (hdrPlus.has(text)) add("HDR10_PLUS")
            if (sdr.has(text)) add("SDR")
            if (!hasDv && !hasHdr) add("NO_HDR_LABEL")
        }
        val audio = buildSet {
            if (atmos.has(text)) add("ATMOS")
            if (trueHd.has(text)) add("TRUEHD")
            if (dtsHd.has(text)) add("DTS_HD")
            if (ddp.has(text)) add("DDP")
            if (aac.has(text)) add("AAC")
        }
        val suffix = group.matcher(text)
        val prefix = animeGroup.matcher(text)
        val releaseGroup = when {
            suffix.find() -> suffix.group(1)
            prefix.find() -> prefix.group(1)
            else -> ""
        }
        return StreamRankingFacts(title = title.take(2048), resolution = pixels, quality = quality,
            codec = when { hevc.has(text) -> "HEVC"; av1.has(text) -> "AV1"; avc.has(text) -> "AVC"; else -> "UNKNOWN" },
            visual = visual, audio = audio, group = releaseGroup)
    }
}

internal data class StreamRankingCandidate<T>(val value: T, val facts: StreamRankingFacts, val key: String,
    val reliability: Int = 1, val indexerOrder: Int = 0)
internal data class StreamRankingResult<T>(val candidate: StreamRankingCandidate<T>, val score: Int,
    val tier: String?, val matches: List<String>, val overflow: Boolean = false)

/** Compiled once per settings snapshot, never inside a comparison or on a video player's tick. */
internal class StreamRankingEngine(val profile: StreamRankingProfile) {
    private class Condition(val source: StreamRankingCondition) {
        private val regex = if (source.operator == StreamRankingOperator.MATCHES) Pattern.compile(source.value, Pattern.CASE_INSENSITIVE) else null
        private val values = source.value.split(',').map { it.trim().lowercase(Locale.ROOT) }.toSet()
        fun matches(facts: StreamRankingFacts, now: Long): Boolean {
            val numeric = when (source.field) {
                StreamRankingField.RESOLUTION -> facts.resolution.toDouble()
                StreamRankingField.SIZE_GB -> facts.sizeBytes?.toDouble()?.div(GIB)
                StreamRankingField.AGE_DAYS -> facts.publishedAt?.let { (now - it).coerceAtLeast(0).toDouble() / 86_400_000 }
                else -> null
            }
            if (source.field in NUMERIC_FIELDS) {
                if (numeric == null) return false
                val requested = source.value.toDouble()
                return when (source.operator) {
                    StreamRankingOperator.IS -> numeric == requested
                    StreamRankingOperator.IS_NOT -> numeric != requested
                    StreamRankingOperator.AT_LEAST -> numeric >= requested
                    StreamRankingOperator.AT_MOST -> numeric <= requested
                    else -> false
                }
            }
            val actual = when (source.field) {
                StreamRankingField.TITLE -> listOf(facts.title)
                StreamRankingField.GROUP -> listOf(facts.group).filter { it.isNotEmpty() }
                StreamRankingField.QUALITY -> listOf(facts.quality.name)
                StreamRankingField.CODEC -> listOf(facts.codec)
                StreamRankingField.VISUAL -> facts.visual
                StreamRankingField.AUDIO -> facts.audio
                StreamRankingField.LANGUAGE -> facts.languages
                StreamRankingField.INDEXER -> listOf(facts.indexer).filter { it.isNotEmpty() }
                StreamRankingField.MEDIA_TYPE -> listOf(facts.mediaType)
                else -> emptyList()
            }
            if (actual.isEmpty()) return false
            return when (source.operator) {
                StreamRankingOperator.IS -> actual.any { it.lowercase(Locale.ROOT) in values }
                StreamRankingOperator.IS_NOT -> actual.none { it.lowercase(Locale.ROOT) in values }
                StreamRankingOperator.CONTAINS -> actual.any { it.contains(source.value, ignoreCase = true) }
                StreamRankingOperator.MATCHES -> actual.any { regex!!.matcher(it).find() }
                else -> false
            }
        }
    }
    private class Rule(val source: StreamRankingRule) {
        private val conditions = source.conditions.map(::Condition)
        fun matches(facts: StreamRankingFacts, now: Long) = if (source.matchAny) conditions.any { it.matches(facts, now) }
            else conditions.all { it.matches(facts, now) }
    }
    init { profile.validate() }
    private val rules = profile.rules.filter { it.enabled }.map(::Rule)
    private val scoring = rules.filter { it.source.action == StreamRankingAction.BOOST }
    private val filtering = rules.filter { it.source.action != StreamRankingAction.BOOST }

    fun <T> rank(candidates: List<StreamRankingCandidate<T>>, now: Long = System.currentTimeMillis()): List<StreamRankingResult<T>> {
        var accepted = candidates
        for (rule in filtering) {
            val filtered = accepted.filter {
                rule.matches(it.facts, now) == (rule.source.action == StreamRankingAction.REQUIRE)
            }
            if (rule.source.minimumRemaining == 0 || filtered.count { it.reliability < 2 } >= rule.source.minimumRemaining) accepted = filtered
        }
        data class Ranked<T>(val result: StreamRankingResult<T>, val keys: Map<StreamRankingKey, Double?>)
        val ranked = accepted.map { candidate ->
            val facts = candidate.facts
            val matching = scoring.filter { it.matches(facts, now) }
            val score = matching.sumOf { it.source.score }
            val tier = profile.tiers.indexOfFirst {
                (it.resolutions.isEmpty() || facts.resolution in it.resolutions) &&
                    (it.qualities.isEmpty() || facts.quality in it.qualities)
            }
            val language = profile.preferredLanguages.indexOfFirst { it in facts.languages }
            val preferredSize = if (facts.mediaType == "series") profile.preferredEpisodeSizeGb else profile.preferredMovieSizeGb
            val keys = mapOf(
                StreamRankingKey.TIER to (if (tier < 0) 0 else profile.tiers.size - tier).toDouble(),
                StreamRankingKey.SCORE to score.toDouble(),
                StreamRankingKey.LANGUAGE to (if (language < 0) 0 else profile.preferredLanguages.size - language).toDouble(),
                StreamRankingKey.SIZE_PREFERENCE to (if (preferredSize > 0 && facts.sizeBytes != null && facts.sizeBytes > preferredSize * GIB) 0.0 else 1.0),
                StreamRankingKey.RESOLUTION to facts.resolution.takeIf { it > 0 }?.toDouble(),
                StreamRankingKey.QUALITY to QUALITY_VALUES[facts.quality]?.toDouble(),
                StreamRankingKey.SIZE to facts.sizeBytes?.toDouble(),
                StreamRankingKey.AGE to facts.publishedAt?.toDouble(),
                StreamRankingKey.INDEXER to -candidate.indexerOrder.toDouble()
            )
            Ranked(StreamRankingResult(candidate, score, profile.tiers.getOrNull(tier)?.name, matching.map { it.source.name }), keys)
        }.sortedWith { left, right ->
            var compared = left.result.candidate.reliability.compareTo(right.result.candidate.reliability)
            for (sort in profile.sort) {
                if (compared != 0) break
                val a = left.keys[sort.key]
                val b = right.keys[sort.key]
                // Missing metadata stays last in both directions, without pretending unknown means zero.
                compared = when { a == null && b == null -> 0; a == null -> 1; b == null -> -1
                    else -> a.compareTo(b) * if (sort.descending) -1 else 1 }
            }
            if (compared == 0) compared = left.result.candidate.facts.title.compareTo(right.result.candidate.facts.title, ignoreCase = true)
            if (compared == 0) compared = left.result.candidate.key.compareTo(right.result.candidate.key)
            compared
        }.map { it.result }
        if (profile.topPerBucket == 0) return ranked
        val counts = mutableMapOf<Triple<Int, Int, StreamReleaseQuality>, Int>()
        val leaders = mutableListOf<StreamRankingResult<T>>()
        val overflow = mutableListOf<StreamRankingResult<T>>()
        ranked.forEach {
            val key = Triple(it.candidate.reliability, it.candidate.facts.resolution, it.candidate.facts.quality)
            val count = counts.getOrDefault(key, 0)
            counts[key] = count + 1
            if (count < profile.topPerBucket) leaders += it else overflow += it.copy(overflow = true)
        }
        // Reliability is authoritative even when adding variety; known dead releases cannot jump the queue.
        return (leaders + overflow).sortedBy { it.candidate.reliability }
    }

    companion object {
        const val GIB = 1073741824.0
        private val NUMERIC_FIELDS = setOf(StreamRankingField.SIZE_GB, StreamRankingField.AGE_DAYS, StreamRankingField.RESOLUTION)
        private val QUALITY_VALUES = mapOf(StreamReleaseQuality.REMUX to 7, StreamReleaseQuality.BLURAY to 6,
            StreamReleaseQuality.WEB_DL to 5, StreamReleaseQuality.WEBRIP to 4, StreamReleaseQuality.HDTV to 3,
            StreamReleaseQuality.DVD to 2, StreamReleaseQuality.CAM to 1)
    }
}

/** A small process cache; editing a profile replaces its compiled matchers instead of growing it forever. */
internal object StreamRankingEngines {
    private val engines = object : LinkedHashMap<StreamRankingProfile, StreamRankingEngine>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<StreamRankingProfile, StreamRankingEngine>?) = size > 8
    }
    @Synchronized fun get(profile: StreamRankingProfile): StreamRankingEngine = engines.getOrPut(profile) { StreamRankingEngine(profile) }
}
