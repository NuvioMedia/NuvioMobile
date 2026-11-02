package com.nuvio.app.features.livetv.ondemand

import com.nuvio.app.features.livetv.data.CivilTime
import com.nuvio.app.features.livetv.model.LiveTvClock
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.concurrent.Volatile

enum class VodKind(val key: String) {
    MOVIE("movie"), SERIES("series");

    companion object {
        fun of(key: String): VodKind = if (key == SERIES.key) SERIES else MOVIE
    }
}

/**
 * What makes two catalog entries the same title: the TMDB id when there is one, otherwise the
 * name and year. Used to show each title once ("Merge duplicates"). The same SQL as the TV app's.
 */
internal const val VOD_DUP_KEY = "CASE WHEN tmdb IS NOT NULL AND tmdb != '' THEN 't' || tmdb ELSE 'n' || norm || '|' || IFNULL(year, '') END"

/** How On Demand lists are sorted; the names are what's saved, so a sort picked on a TV reads back here. */
enum class VodSort(val label: String) {
    DEFAULT("Provider's order"),
    NAME_AZ("A–Z"),
    NAME_ZA("Z–A"),
    NEWEST("Newest release"),
    OLDEST("Oldest release"),
    RECENTLY_ADDED("Recently added"),
    RATING("Highest rated");

    /** The SQL order. Titles with no year (or no rating) go at the end, so they don't fill the top. */
    internal fun orderBy(fallback: String): String = when (this) {
        DEFAULT -> fallback
        NAME_AZ -> "norm ASC"
        NAME_ZA -> "norm DESC"
        NEWEST -> "year IS NULL, year DESC, added DESC"
        OLDEST -> "year IS NULL, year ASC, added ASC"
        RECENTLY_ADDED -> "added DESC"
        RATING -> "(rating IS NULL OR rating = '' OR CAST(rating AS REAL) <= 0), CAST(rating AS REAL) DESC, norm ASC"
    }

    companion object {
        /** A saved choice ("NAME_AZ", or the TV's "SHUFFLE:<seed>", which has no order here). */
        fun parse(saved: String?): VodSort? {
            if (saved.isNullOrBlank()) return null
            val name = saved.substringBefore(':')
            return entries.firstOrNull { it.name == name }
        }
    }
}

/** One movie or series from a provider's On Demand catalog. */
data class VodItem(
    val playlistId: String,
    val kind: VodKind,
    /** stream_id for movies, series_id for series (an M3U entry: a hash of its link). */
    val id: String,
    val name: String,
    val icon: String?,
    val categoryId: String,
    /** Movies: the file type the provider serves (mp4, mkv…). */
    val ext: String?,
    val tmdbId: String?,
    val year: Int?,
    val rating: String?,
    val addedSec: Long,
    /** The provider's genres, as sent ("Action, Comedy"). Not every provider includes them. */
    val genre: String? = null,
    /** An M3U playlist's entry: the link itself (Xtream titles have none; their link is built). */
    val url: String? = null,
    /** With duplicates merged: how many copies of this title there are (qualities, providers). */
    val copies: Int = 1,
) {
    val uid: String get() = "${kind.key}:$playlistId:$id"

    /** The name without the provider's decorations. */
    val displayTitle: String get() = VodTitles.displayTitle(name)
}

data class VodCategory(
    val playlistId: String,
    val kind: VodKind,
    val id: String,
    val name: String,
    val count: Int,
) {
    /** Also the key used for hiding ("movie:<playlist>:<category>"). */
    val uid: String get() = "${kind.key}:$playlistId:$id"
}

/** One episode of a provider series, ready to play. */
data class VodEpisode(
    val season: Int,
    val episode: Int,
    val id: String,
    val ext: String,
    val title: String,
    val plot: String?,
    val image: String?,
    val url: String,
    val durationSecs: Int? = null,
)

/** What the provider says about a movie or series. */
data class VodInfo(
    val plot: String?,
    val cover: String?,
    val backdrop: String?,
    val genre: String?,
    val releaseDate: String?,
    val durationText: String?,
    val tmdbId: String? = null,
    val episodes: List<VodEpisode> = emptyList(),
)

data class OnDemandStatus(val loading: Boolean = false, val message: String? = null)

/** A poster (and the proper title and TMDB id) found for a title, remembered between app starts. */
data class VodMatch(
    /** The poster's address; empty when nothing was found. */
    val poster: String,
    val title: String?,
    val tmdbId: String?,
    val checkedMs: Long,
) {
    val found: Boolean get() = poster.isNotBlank() || !tmdbId.isNullOrBlank()
}

/**
 * Cleaning provider titles, the TV app's way: the decorations providers add (language and
 * service tags in front, a year in brackets, quality tags) come off for display and for matching,
 * and the release year is read out of the name when the provider didn't send one.
 */
object VodTitles {
    /** Every provider's learned title tags, lower case (see [OnDemandStore.learnPrefixes]). */
    @Volatile var knownPrefixes: Set<String> = emptySet()
        set(value) { field = value; synchronized(cacheLock) { displayTitles.clear() } }

    private val cacheLock = SynchronizedObject()
    private val displayTitles = HashMap<String, String>()
    private const val CACHE_MAX = 5_000

    private val PREFIX = Regex("""^(\[[^\]]*\]|\|[^|]*\||[A-Z]{2,4}\s*[-|:]\s+)+""")
    private val YEAR_IN_BRACKETS_TRAIL = Regex("""\s*\((19|20)\d{2}\)""")
    private val YEAR_AT_END = Regex("""\s*[-|]\s*(19|20)\d{2}\s*$""")
    private val QUALITY_AT_END = Regex("""\s*[\[(]?\b(4k|uhd|fhd|hd|sd|1080p|720p|2160p|hdr|multi|multi-?sub|vostfr|dub|sub)\b[\])]?\s*$""", RegexOption.IGNORE_CASE)
    private val YEAR_WORD = Regex("""(19|20)\d{2}""")
    private val YEAR_ANYWHERE = Regex("""\b(19|20)\d{2}\b""")
    private val YEAR_IN_BRACKETS = Regex("""\((19|20)\d{2}\)""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val NOISE = Regex("""\b(4k|uhd|fhd|hd|sd|1080p|720p|2160p|multi|sub|dub|vostfr)\b""")
    private val SEGMENT = Regex("""^\s*(.{1,30}?)\s*(?:\s[-–—|]\s|\s?\|\s?|:\s)""")
    private val DOTTED_WORDS = Regex("""[\p{L}\p{N}]\.[\p{L}]""")
    private val DOT_BETWEEN = Regex("""(?<=[\p{L}\p{N}])\.(?=[\p{L}\p{N}])""")
    private val TOP_LIST_NUMBER = Regex("""^\s*(?:[a-z]{2,4}\s*-\s*)?top\s*[-#]?\s*\d{0,4}\s*[-.:)]?\s*""", RegexOption.IGNORE_CASE)
    private val TRAILING_BARE_YEAR = Regex("""\s(?:19|20)\d{2}\s*$""")
    private val EMOJI_FLAG = Regex("[\\x{1F1E6}-\\x{1F1FF}]")
    private val BRACKET_TAG = Regex("""[\[{(][^\[\]{}()]*[\]})]""")
    private val SHORT_CAPS = Regex("""[\[{(]\s*[A-Z]{2,4}\s*[\]})]""")
    private val RELEASE_WORDS = Regex(
        "\\b(uhd|fhd|hevc|h\\.?265|h\\.?264|x265|x264|hdr10\\+?|hdr|dolby\\s+vision|atmos|" +
            "multi[- ]?subs?|multisub|dual[- ]?audio|remux|web[- ]?dl|webrip|bluray|blu-ray|bdrip|dvdrip|hdrip|" +
            "vostfr|vost|subbed|dubbed|dublado|legendado|truefrench|hdlight|" +
            "10bit|8bit|60fps|50fps|aac|e?ac3|dts|ddp|[257]\\.1|hdtc|hdcam|camrip|1080p|720p|2160p|480p)\\b",
        RegexOption.IGNORE_CASE
    )
    private val TRAILING_LANG_TAG = Regex("""\s+(?:FR|EN|DE|ES|PT|NL|PL|TR|AR|RU|LAT|SUB|DUB|MULTI)$""")
    private val MULTI_SPACE = Regex("""\s{2,}""")
    private val TRAILING_YEAR = Regex("""(?:^|[\s\-|:,.])((?:19|20)\d{2})\s*$""")
    private val TRAILING_EXTRA = setOf("uk", "us", "usa", "au", "aus", "ca", "nz", "ie", "gb", "de", "fr", "es", "it", "nl")

    /** Lower case, letters and digits only, with years and quality tags taken out. */
    fun normalize(s: String): String = normalizeWords(s).joinToString("")

    fun normalizeWords(s: String): List<String> =
        s.replace(PREFIX, "")
            .replace(YEAR_IN_BRACKETS, " ")
            .lowercase()
            .replace(NOISE, " ")
            .split(NON_WORD)
            .filter { it.isNotBlank() }

    /** The bit before the first separator, lower case, or null. */
    internal fun leadingSegment(name: String): String? =
        SEGMENT.find(name)?.groupValues?.get(1)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /**
     * Looks like a provider tag even without learning it: one short "word" (no spaces), mostly
     * capitals, digits or symbols, like "4K-D+", "4k-NF", "EN", "NF", "UHD-DV". Real titles
     * ("Spider-Man", "Mission: Impossible") don't fit.
     */
    private fun looksLikeTag(segment: String): Boolean {
        val t = segment.trim()
        if (t.length !in 2..12 || t.any { it.isWhitespace() }) return false
        if (t.all { it.isDigit() }) return false // "2001 - A Space Odyssey"
        val upper = t.count { it.isUpperCase() }
        val lower = t.count { it.isLowerCase() }
        return upper >= lower && t.any { it.isLetterOrDigit() }
    }

    private fun stripTags(s: String): String {
        var out = s.trim()
        repeat(3) {
            val m = SEGMENT.find(out) ?: return out
            val seg = m.groupValues[1].trim()
            if (seg.lowercase() in knownPrefixes || looksLikeTag(seg)) {
                val rest = out.substring(m.range.last + 1).trim()
                if (rest.isBlank()) return out
                out = rest
            } else return out
        }
        return out
    }

    /**
     * The title without the provider's decorations: language / country / service tags in front
     * ("EN - ", "|EN| ", "[US] ", "NF - "), a year in brackets, and quality tags.
     * "EN  - Coyote vs. Acme (2026) 4K" becomes "Coyote vs. Acme".
     */
    fun displayTitle(s: String): String {
        synchronized(cacheLock) { displayTitles[s] }?.let { return it }
        val t = cleanTitle(s)
        synchronized(cacheLock) {
            if (displayTitles.size >= CACHE_MAX) displayTitles.clear()
            displayTitles[s] = t
        }
        return t
    }

    /**
     * Names written with dots instead of spaces, the way file names often are
     * ("EN-TOP -181.Mad.Max.Fury.Road.2015"): spaces back, a "TOP -181" list number in front and
     * the year at the end taken off, so it reads "Mad Max Fury Road" (the year is still used for
     * sorting). Other names are left as they are.
     */
    private fun undotted(s: String): String {
        if (DOTTED_WORDS.findAll(s).count() < 2 || s.count { it == ' ' } > s.count { it == '.' }) return s
        var t = s.replace(DOT_BETWEEN, " ").trim()
        t = t.replace(TOP_LIST_NUMBER, "").trim()
        val y = TRAILING_BARE_YEAR.find(t)
        if (y != null && t.substring(0, y.range.first).any { it.isLetter() }) t = t.substring(0, y.range.first).trim()
        return t.ifBlank { s }
    }

    private fun cleanTitle(raw: String): String {
        val s = undotted(raw)
        var t = stripTags(s).replace(PREFIX, "")
            .replace(YEAR_IN_BRACKETS_TRAIL, "")
            .replace(YEAR_AT_END, "")
            .replace(QUALITY_AT_END, "")
            .trim()
        // More decorations: flags, tags in brackets ("[MULTI-SUB]", "(VOSTFR)"), release words
        // anywhere ("WEB-DL", "x265", "5.1"), and a capital language tag at the very end
        // ("Movie FR"). Never down to nothing.
        val before = t
        t = t.replace(EMOJI_FLAG, " ")
            .replace(BRACKET_TAG) { m -> if (RELEASE_WORDS.containsMatchIn(m.value) || SHORT_CAPS.matches(m.value)) " " else m.value }
            .replace(RELEASE_WORDS, " ")
            .replace(TRAILING_LANG_TAG, "")
            .replace(MULTI_SPACE, " ")
            .trim()
            .trim('-', '–', '—', '|', ':', '.', '_', ' ')
        if (t.isBlank() || t.none { it.isLetterOrDigit() }) t = before
        return t.ifBlank { s.trim() }
    }

    /**
     * The core of a title for loose matching: cleaned of provider tags, with a trailing year or
     * country tag dropped. "EN - Big Brother UK 2023" and "Big Brother (2023)" both become
     * "bigbrother" (the year is then compared separately).
     */
    fun coreKey(name: String): String {
        val words = normalizeWords(displayTitle(name)).toMutableList()
        while (words.size > 1) {
            val last = words.last()
            if (last in TRAILING_EXTRA || YEAR_WORD.matches(last)) words.removeAt(words.lastIndex) else break
        }
        return words.joinToString("")
    }

    /** A year in a provider title ("Big Brother UK 2023"), if any. */
    fun yearInTitle(name: String): Int? = YEAR_ANYWHERE.findAll(name).lastOrNull()?.value?.toIntOrNull()

    /** The latest believable release year (next year, for titles listed early). */
    fun latestYear(now: Long = LiveTvClock.nowMs()): Int = CivilTime.atUtc(now).year + 1

    /**
     * A title's release year from its name, for sorting: only a year the provider added to the
     * name, in brackets ("Movie (2018)") or at the end ("Movie - 2018", "Movie 2018"). A number
     * that's part of the title ("2038 Origin Unknown", "1917", "2001: A Space Odyssey") isn't
     * one, and neither is a year still to come.
     */
    fun releaseYearInName(name: String, latest: Int = latestYear()): Int? {
        YEAR_IN_BRACKETS.findAll(name).lastOrNull()?.value?.trim('(', ')')?.toIntOrNull()
            ?.takeIf { it <= latest }?.let { return it }
        // At the end, after the title (quality tags like "4K" or "1080p" set aside).
        var rest = name.trim()
        repeat(3) { rest = rest.replace(QUALITY_AT_END, "").trim() }
        val m = TRAILING_YEAR.find(rest) ?: return null
        val before = rest.substring(0, m.range.first).trim()
        // Something has to come before it (a title that's only a year is a title).
        if (before.none { it.isLetter() }) return null
        return m.groupValues[1].toIntOrNull()?.takeIf { it <= latest }
    }

    /** A year in a provider's field ("2015", "2015-06-12"), if any. */
    fun yearOf(raw: String?): Int? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return null
        return YEAR_WORD.find(t)?.value?.toIntOrNull()
    }

    /** "4K", "Full HD", "HD" or "SD" from a provider title, with "HEVC" when it says so. */
    fun qualityOf(name: String): String? {
        val n = name.lowercase()
        return when {
            Regex("""\b(4k|uhd|2160p)\b""").containsMatchIn(n) -> "4K"
            Regex("""\b(fhd|1080p)\b""").containsMatchIn(n) -> "Full HD"
            Regex("""\b(hd|720p)\b""").containsMatchIn(n) -> "HD"
            Regex("""\b(sd|480p)\b""").containsMatchIn(n) -> "SD"
            else -> null
        }?.let { q -> if (Regex("""\b(hevc|x265|h265)\b""").containsMatchIn(n)) "$q HEVC" else q }
    }

    /**
     * Learns a provider's own title tags from its catalog names: a short bit before " - ", " | "
     * or ": " that starts many titles ("4K-D+ - Toy Story 5", "4k-NF - Roommates", "EN - …") is a
     * tag, not part of the name. Every provider labels things its own way, so this is learned
     * rather than listed.
     */
    fun learnPrefixes(names: Sequence<String>): Set<String> {
        val counts = HashMap<String, Int>()
        var total = 0
        for (n in names) {
            total++
            leadingSegment(n)?.let { counts[it] = (counts[it] ?: 0) + 1 }
        }
        val threshold = maxOf(15, total / 500)
        return counts.filter { (seg, n) -> n >= threshold && seg.split(' ').size <= 4 }.keys
    }
}
