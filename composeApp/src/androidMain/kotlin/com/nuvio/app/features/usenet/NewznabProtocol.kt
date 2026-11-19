package com.nuvio.app.features.usenet

import java.io.StringReader
import java.text.Normalizer
import java.time.ZonedDateTime
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.w3c.dom.Element
import org.xml.sax.InputSource

internal data class UsenetSearchRequest(
    val imdbId: String? = null,
    val tmdbId: String? = null,
    val tvdbId: String? = null,
    val title: String? = null,
    /** Set when it differs from [title]: releases of foreign titles are often posted under it. */
    val originalTitle: String? = null,
    /** A movie's other names, its alternative titles and translations; null when unknown. */
    val otherTitles: List<String>? = null,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val series: Boolean = false
)

@kotlinx.serialization.Serializable
internal data class NewznabCapabilities(
    val movieParams: Set<String> = setOf("imdbid"),
    val tvParams: Set<String> = setOf("imdbid", "season", "ep"),
    val searchAvailable: Boolean = true,
    val limit: Int = 100
)

internal data class UsenetRelease(
    val title: String,
    val nzbUrl: String,
    val size: Long = 0,
    val publishedAt: Long = 0,
    val indexerId: String = "",
    val indexerName: String = "",
    val passworded: Boolean = false
) {
    // Read from the title once: filtering and sorting read these thousands of times per search.
    val resolution: Int = RESOLUTION.find(title)?.groupValues?.get(1)?.toInt()
        ?: if (FOUR_K.containsMatchIn(title)) 2160 else 0
    val rankingFacts: StreamRankingFacts by lazy(LazyThreadSafetyMode.PUBLICATION) { StreamRankingFacts.parse(title) }
    val lowQuality: Boolean = LOW_QUALITY.containsMatchIn(title)
    /** The seasons of a whole-season release (S01, Season 2, S01-S03); empty for an episode or a movie. */
    val packSeasons: List<Int> = NewznabProtocol.packSeasons(title)

    /**
     * The size of one episode of a season pack, given each season's episode count; the release size
     * otherwise, and when a season of the pack has no count. Only filters and sorting use it: the
     * real size is known after the NZB is read.
     */
    fun episodeSize(seasonEpisodes: Map<Int, Int>?): Long {
        if (packSeasons.isEmpty() || seasonEpisodes == null || size <= 0) return size
        val episodes = packSeasons.sumOf { seasonEpisodes[it]?.takeIf { count -> count > 0 } ?: return size }
        return size / episodes
    }
    /** A whole Blu-ray or DVD (disc folder or ISO): the feature is one clip among many, not one video. */
    val fullDisc: Boolean = FULL_DISC.containsMatchIn(title) && !NOT_DISC.containsMatchIn(title)
    /** A whole Blu-ray's label on its result; null for anything else, DVDs among them. */
    val blurayLabel: String? = if (!fullDisc || DVD.containsMatchIn(title)) null else {
        val uhd = resolution >= 2160 || UHD.containsMatchIn(title)
        (if (uhd) "UHD BLU-RAY" else "BLU-RAY") + if (ISO.containsMatchIn(title)) " ISO" else ""
    }

    private companion object {
        val RESOLUTION = Regex("(?i)(?<!\\d)(2160|1080|720|480|360)p?(?!\\d)")
        val FOUR_K = Regex("(?i)\\b4k\\b")
        val LOW_QUALITY = Regex("(?i)(?:^|[ ._-])(?:cam|hdcam|telesync|telecine|hdts|ts|tc|scr|dvdscr)(?:$|[ ._-])")
        val FULL_DISC = Regex("(?i)(?:^|[ ._(\\[-])(?:complete[ ._-]+(?:uhd[ ._-]+)?(?:blu-?ray|bd|dvd|mpeg)|bd(?:25|50|66|100)|bdmv|dvd[59]|iso)(?:$|[ ._)\\]-])")
        // Remuxes, encodes and rips of a disc are single videos, even when named after it.
        val DVD = Regex("(?i)(?:^|[ ._(\\[-])(?:dvd[59r]?|complete[ ._-]+dvd)(?:$|[ ._)\\]-])")
        val ISO = Regex("(?i)(?:^|[ ._(\\[-])iso(?:$|[ ._)\\]-])")
        val UHD = Regex("(?i)(?:^|[ ._(\\[-])uhd(?:$|[ ._)\\]-])")
        val NOT_DISC = Regex("(?i)(?:^|[ ._-])(?:remux|[xh][ .]?26[45]|av1|re-?encode|[a-z]*rip|web-?dl)(?:$|[ ._-])")
    }
}

/** [items] counts the page's items, usable or not; [total] is null when the indexer reports none. */
internal data class NewznabPage(val releases: List<UsenetRelease>, val total: Int? = null, val items: Int = releases.size)

/** Protocol functions are independent of Android and never include credentials in errors. */
internal object NewznabProtocol {
    fun apiUrl(indexer: UsenetIndexer, action: String): HttpUrl.Builder = indexer.endpoint.toHttpUrl()
        .newBuilder().setQueryParameter("apikey", indexer.apiKey)
        .setQueryParameter("t", action).setQueryParameter("o", "xml")

    /** [wholeSeason] drops the episode from an ID search so one request covers a season. */
    fun searchUrl(indexer: UsenetIndexer, request: UsenetSearchRequest,
        caps: NewznabCapabilities, offset: Int = 0, wholeSeason: Boolean = false): HttpUrl? {
        val params = if (request.series) caps.tvParams else caps.movieParams
        val builder = apiUrl(indexer, if (request.series) "tvsearch" else "movie")
        val idParam = when {
            request.series && !("season" in params && "ep" in params) -> null
            request.imdbId != null && "imdbid" in params -> "imdbid" to request.imdbId.removePrefix("tt")
            // TVDB is the TV identifier Newznab indexers support most widely.
            request.series && request.tvdbId != null && "tvdbid" in params -> "tvdbid" to request.tvdbId
            request.tmdbId != null && "tmdbid" in params -> "tmdbid" to request.tmdbId
            else -> null
        }
        // Season-wide text searches are too broad to share between episodes.
        if (wholeSeason && (!request.series || idParam == null)) return null
        if (idParam != null) {
            builder.setQueryParameter(idParam.first, idParam.second)
        } else {
            // Query the way release names are spelled: "Amélie" and "Marvel's" are posted as Amelie and Marvels.
            val title = request.title?.let { releaseSpelling(it).replace(NON_WORD, " ").trim() }
                ?.takeIf { it.isNotBlank() } ?: return null
            if ("q" !in params) {
                if (!caps.searchAvailable) return null
                builder.setQueryParameter("t", "search")
            }
            val query = if (request.series) {
                title + if (request.season != null && request.episode != null) {
                    " S%02dE%02d".format(Locale.ROOT, request.season, request.episode)
                } else ""
            } else title
            builder.setQueryParameter("q", query)
            if (!request.series && request.year != null && "year" in params) {
                builder.setQueryParameter("year", request.year.toString())
            }
        }
        if (request.series) {
            // Never turn an episode lookup into an unbounded whole-series ID lookup.
            if (request.season == null || request.episode == null) return null
            if (idParam != null && !("season" in params && "ep" in params)) return null
            if ("season" in params) builder.setQueryParameter("season", request.season.toString())
            if ("ep" in params && !wholeSeason) builder.setQueryParameter("ep", request.episode.toString())
        }
        return builder.setQueryParameter("cat", if (request.series) "5000" else "2000")
            .setQueryParameter("limit", caps.limit.coerceIn(1, 100).toString())
            .setQueryParameter("offset", offset.toString()).build()
    }

    /** Whether [url] names the content by id rather than by title. */
    fun isIdSearch(url: HttpUrl) = ID_PARAMS.any { url.queryParameter(it) != null }

    private val ID_PARAMS = listOf("imdbid", "tvdbid", "tmdbid")

    fun capabilities(xml: String): NewznabCapabilities {
        val root = document(xml)
        require(root.localName == "caps") { "Invalid indexer capabilities response" }
        fun params(tag: String): Set<String> {
            val element = root.getElementsByTagName(tag).item(0) as? Element ?: return emptySet()
            if (element.getAttribute("available") != "yes") return emptySet()
            return element.getAttribute("supportedParams").split(',').map { it.trim().lowercase() }.toSet()
        }
        val limits = root.getElementsByTagName("limits").item(0) as? Element
        val search = root.getElementsByTagName("search").item(0) as? Element
        return NewznabCapabilities(params("movie-search"), params("tv-search"),
            search?.getAttribute("available") == "yes",
            limits?.getAttribute("max")?.toIntOrNull()?.coerceIn(1, 100) ?: 100)
    }

    fun releases(xml: String, indexer: UsenetIndexer): NewznabPage {
        val root = document(xml)
        require(root.localName == "rss") { "Invalid indexer search response" }
        val items = root.getElementsByTagName("item")
        val results = (0 until items.length).mapNotNull { i ->
            val item = items.item(i) as Element
            fun text(tag: String) = item.getElementsByTagName(tag).item(0)?.textContent?.trim().orEmpty()
            val attrs = item.getElementsByTagNameNS("*", "attr")
            val attributes = (0 until attrs.length).associate { j ->
                val attr = attrs.item(j) as Element
                attr.getAttribute("name").lowercase() to attr.getAttribute("value")
            }
            val enclosures = item.getElementsByTagName("enclosure")
            val enclosure = (0 until enclosures.length).map { enclosures.item(it) as Element }
                .firstOrNull { it.getAttribute("type").contains("nzb", ignoreCase = true) }
            val download = enclosure?.getAttribute("url")?.takeIf { it.isNotBlank() }
                ?: text("link").takeIf { it.isNotBlank() && enclosures.length == 0 } ?: return@mapNotNull null
            val url = indexer.endpoint.toHttpUrl().resolve(download) ?: return@mapNotNull null
            val title = text("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val published = listOfNotNull(attributes["usenetdate"], text("pubDate")).firstNotNullOfOrNull { date ->
                runCatching { ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
                    ?: runCatching { Instant.parse(date).toEpochMilli() }.getOrNull()
                    ?: date.toLongOrNull()?.takeIf { it > 0 && it < Long.MAX_VALUE / 1000 }?.let {
                        if (it < 100000000000L) it * 1000 else it
                    }
            } ?: 0
            UsenetRelease(title, url.toString(),
                (attributes["size"]?.toLongOrNull() ?: enclosure?.getAttribute("length")?.toLongOrNull() ?: 0).coerceAtLeast(0),
                published, indexer.id, indexer.name, attributes["password"]?.lowercase() in setOf("1", "2", "true", "yes"))
        }
        val response = root.getElementsByTagNameNS("*", "response").item(0) as? Element
        return NewznabPage(results, response?.getAttribute("total")?.toIntOrNull(), items.length)
    }

    /**
     * Whether [release] names only years more than one from [year]. Festival and release years
     * often differ by one, and so do the years releases carry. Year-like [titleWords]
     * (Blade Runner 2049, 1917) are not release years.
     */
    private fun contradictsYear(release: String, year: Int, titleWords: Collection<String>): Boolean {
        val years = RELEASE_YEAR.findAll(release).map { it.groupValues[1] }.toList()
        return years.none { it.toInt() in year - 1..year + 1 } && years.any { it !in titleWords }
    }

    /**
     * Aggregators may fall back to text search. Reject contradictory filename metadata.
     * [requireNumbering] rejects unnumbered series releases, which only an episode search vouches for.
     */
    fun matches(release: UsenetRelease, request: UsenetSearchRequest, idSearch: Boolean,
        requireNumbering: Boolean = false): Boolean {
        // Umlauts are posted both ways: König as Koenig or Konig.
        val titles = listOfNotNull(request.title, request.originalTitle)
            .flatMap { listOf(words(it), words(it, umlauts = false)) }
            .filter { it.isNotEmpty() }.distinct().map { it.split(' ') }
        if (!idSearch) {
            val name = words(release.title).split(' ')
            val title = titles.firstOrNull { isNamedAs(name, it, request.series) } ?: return false
            if (!request.series && request.year != null && contradictsYear(release.title, request.year, title)) return false
        } else if (!request.series && request.year != null &&
            // Indexers mis-tag IDs too: a 2011 sequel filed under the 2001 film. The name's year decides.
            contradictsYear(release.title, request.year, titles.flatten())) return false
        if (request.series) {
            val episodes = EPISODES.flatMap { it.findAll(release.title).toList() }
            if (episodes.isNotEmpty()) return episodes.any {
                it.groupValues[1].toInt() == request.season && request.episode != null &&
                    names(request.episode, it, release.title)
            }
            // A pack's seasons, ranges spanned (S01-S10 holds S05), as its size per episode counts them.
            val seasons = release.packSeasons
            if (seasons.isNotEmpty()) return request.season in seasons
            // Obfuscated ID-matched releases rely on the engine's file selection.
            return idSearch && !requireNumbering
        }
        return true
    }

    /**
     * The [releases] of a movie's ID search that are of [movie], by the year and the title their
     * names give. Indexers mis-tag IDs, sometimes with a film of the same year: The Ring (2002)
     * came back for The Two Towers (2002). Releases are named in many languages and shortened
     * (LOTR The Two Towers), so a release passes when most of its title's words are in the
     * movie's title or original title, or when its title holds most of the words of any of the
     * movie's names, translations among them (Der Herr der Ringe Die zwei Tuerme). Only the
     * movie's own titles count the first way: a short title would otherwise pass on one word of
     * a long translation (The Ring, In de ban van de ring). A name with no year (obfuscated) or
     * no title before it (1917) passes, and every name passes when the movie's names are unknown.
     */
    fun idResultsOf(movie: UsenetSearchRequest, releases: List<UsenetRelease>): List<UsenetRelease> {
        val ofYear = releases.filter { matches(it, movie, idSearch = true) }
        val others = movie.otherTitles ?: return ofYear
        val own = nameWords(listOfNotNull(movie.title, movie.originalTitle)).ifEmpty { return ofYear }
        val names = (own + nameWords(others)).distinct()
        return ofYear.filter { release ->
            val title = titleWords(release.title)
            title.isEmpty() || names.any { name -> title.count { it in name } * 2 > name.size } ||
                own.any { name -> title.count { it in name } * 2 > title.size }
        }
    }

    /**
     * The words of each of [names] as releases spell them, articles left out: umlauts both ways,
     * and without the letters some posts lose (Amélie posted as Amlie).
     */
    private fun nameWords(names: List<String>): List<Set<String>> = names.flatMap { name ->
        listOf(words(name), words(name, umlauts = false), words(name.filter { it.code < 128 || !it.isLetter() }))
    }.map { name -> name.split(' ').filter { it.isNotEmpty() && it !in ARTICLES }.toSet() }
        .filter { name -> name.any { word -> word.any(Char::isLetter) } }.distinct()

    /**
     * The words of the title a release name gives before its year or first tag, articles left out.
     * None when the name gives no year, as obfuscated names do: their ID is trusted.
     */
    private fun titleWords(release: String): Set<String> =
        if (!RELEASE_YEAR.containsMatchIn(release)) emptySet() else words(release).split(' ')
            .takeWhile { it.isNotEmpty() && it !in RELEASE_TAGS && !RELEASE_MARKER.matches(it) }
            .filter { it !in ARTICLES }.toSet()

    /**
     * Whether release [name] is named after [title] rather than after another title that contains it:
     * The Science of Interstellar is not Interstellar, and The Rookie Feds is not The Rookie. Before
     * the title only articles may come; after it, the year, the episode or release tags.
     *
     * Series names often keep a prefix the metadata title drops (Marvels Daredevil); the episode
     * number vouches for those, except an article before a one-word title (The Batman is not Batman).
     * A movie may carry a subtitle before its year (Star Wars Episode IV A New Hope 1977), which the
     * year check then judges, so a sequel only passes if it came out within a year.
     */
    private fun isNamedAs(name: List<String>, title: List<String>, series: Boolean): Boolean =
        (0..name.size - title.size).any { i ->
            if (name.subList(i, i + title.size) != title) return@any false
            val before = name.subList(0, i)
            val leading = if (series) title.size > 1 || before.isEmpty() || before.any { it !in ARTICLES }
                else before.all { it in ARTICLES }
            val after = name.subList(i + title.size, name.size)
            val marker = after.indexOfFirst { it in RELEASE_TAGS || RELEASE_MARKER.matches(it) }
            val trailing = marker == 0 || after.isEmpty() || (!series && marker > 0 && YEAR.matches(after[marker]))
            leading && trailing
        }

    // Compiled once: matching reads every title of every result page.
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val APOSTROPHES = Regex("['’‘`´ʼ]")
    private val RELEASE_YEAR = Regex("(?<![\\p{L}\\p{N}])((?:19|20)\\d{2})(?![\\p{L}\\p{N}])")
    /** The episodes after the first: E02E03 lists them, -E03 or -03 spans a range. */
    private const val MORE_EPISODES = "((?:(?:[ ._]*E|[ ._]*-[ ._]*E?)\\d{1,3})+(?=$|[ ._-]))?"
    private val EPISODES = listOf(
        Regex("(?i)S(\\d{1,3})[ ._-]*E(\\d{1,3})(?!\\d)$MORE_EPISODES"),
        Regex("(?i)(?<!\\d)(\\d{1,2})x(\\d{1,3})(?!\\d)$MORE_EPISODES")
    )
    private val RELEASE_TAG = Regex("(?i)(?:^|[ ._-])(?:(?:2160|1080|720|576|480)[pi]|4k|uhd|web-?dl|web-?rip|blu-?ray|bdrip|hdtv|remux|" +
        "[xh][ .]?26[45]|hevc|proper|repack)(?=$|[ ._-])")
    private val NEXT_EPISODE = Regex("(?i)([ ._]*-[ ._]*E?|[ ._]*E)(\\d{1,3})")
    /** An episode named by the time it shows, as in S01E04-10-00.A.M. */
    private val CLOCK_NAME = Regex("(?i)^[ ._-]*\\d{1,2}[ ._:-]\\d{2}[ ._-]*[AP]\\.?M(?![A-Z])")
    // The separator after the number is not consumed, so S01-S03 names both seasons.
    private val SEASON = Regex("(?i)(?:^|[ ._-])(?:S|Season[ ._-]*)(\\d{1,3})(?=$|[ ._-])")
    /** A hyphen between two seasons spans the ones between, as in S01-S03, S01-03 or Season 1-3. */
    private val SEASON_RANGE = Regex("(?i)(?:^|[ ._-])(?:S|Season[ ._-]*)(\\d{1,3})[ ._]*-[ ._]*(?:S|Season[ ._-]*)?(\\d{1,3})(?=$|[ ._-])")
    private const val MAX_SEASON_SPAN = 50

    /**
     * A one-episode title with its episode left out, and that episode: an upload's other episodes
     * share the first. Titles naming several episodes, or none, have none.
     */
    fun episodeTemplate(title: String): Pair<String, Int>? {
        val matches = EPISODES.flatMap { it.findAll(title).toList() }
        val match = matches.singleOrNull()?.takeIf { moreEpisodes(it, title).isEmpty() } ?: return null
        // The episode's own name, between its number and the first technical tag, differs per episode.
        val rest = title.substring(match.range.last + 1)
        val tags = RELEASE_TAG.find(rest)?.let { rest.substring(it.range.first) } ?: rest
        return title.substring(0, match.range.first) + "S${match.groupValues[1].toInt()}E?" + tags to match.groupValues[2].toInt()
    }

    /** The seasons a release title names without naming an episode: a season pack. */
    fun packSeasons(title: String): List<Int> {
        if (EPISODES.any { it.containsMatchIn(title) }) return emptyList()
        val spanned = SEASON_RANGE.findAll(title).flatMap {
            val first = it.groupValues[1].toInt()
            val last = it.groupValues[2].toInt()
            if (last > first && last - first < MAX_SEASON_SPAN) (first..last).asSequence() else sequenceOf(first, last)
        }
        return (spanned + SEASON.findAll(title).map { it.groupValues[1].toInt() }).distinct().toList()
    }

    /**
     * Whether a release numbered by [match] in [title] holds [episode]. E01E02E03 lists three
     * episodes; only a hyphen spans the ones between, as in E01-E03, so E01E03 is not E02.
     */
    private fun names(episode: Int, match: MatchResult, title: String): Boolean {
        var previous = match.groupValues[2].toInt()
        if (episode == previous) return true
        for ((number, ranged) in moreEpisodes(match, title)) {
            if (episode == number || ranged && episode in previous..number) return true
            previous = number
        }
        return false
    }

    /**
     * The episodes [match] numbers after its first, and whether a hyphen spans to each. Each must
     * follow the one before, and one without an E has at least the first's digits. Names that tell
     * the time, as in S01E02-8-00.A.M, S01E08-2-00.P.M or S01E04-10-00.A.M, number no other episode.
     */
    private fun moreEpisodes(match: MatchResult, title: String): List<Pair<Int, Boolean>> {
        val first = match.groupValues[2]
        val more = match.groupValues[3]
        if (CLOCK_NAME.containsMatchIn(title.substring(match.groups[2]!!.range.last + 1))) return emptyList()
        var previous = first.toInt()
        val episodes = mutableListOf<Pair<Int, Boolean>>()
        for (next in NEXT_EPISODE.findAll(more)) {
            val separator = next.groupValues[1]
            val number = next.groupValues[2].toInt()
            val marked = separator.contains('E', ignoreCase = true)
            if (number <= previous || !marked && next.groupValues[2].length < first.length) break
            episodes += number to ('-' in separator)
            previous = number
        }
        return episodes
    }

    private val ARTICLES = setOf("the", "a", "an")
    private val YEAR = Regex("(?:19|20)\\d{2}")
    private val RELEASE_MARKER = Regex("(?:19|20)\\d{2}|s\\d{1,3}(?:e\\d{1,4})*|e\\d{1,4}|\\d{1,2}x\\d{1,3}|" +
        "\\d{3,4}[pi]|[48]k|\\d{1,2}bit|season|complete")
    /** Words that start the tags of a release name: language, edition, source, codec, country. */
    private val RELEASE_TAGS = setOf("german", "french", "truefrench", "vostfr", "ita", "italian", "spanish",
        "castellano", "latino", "multi", "dl", "dual", "dubbed", "subbed", "nordic", "dutch", "polish", "russian",
        "hindi", "korean", "japanese", "bluray", "bdrip", "brrip", "webrip", "web", "webdl", "hdtv", "hdrip",
        "dvdrip", "dvd", "remux", "uhd", "hdr", "hdr10", "dv", "dovi", "sdr", "x264", "x265", "h264", "h265",
        "hevc", "avc", "xvid", "av1", "imax", "extended", "unrated", "remastered", "proper", "repack", "internal",
        "limited", "directors", "uncut", "theatrical", "cam", "ts", "telesync", "hdcam", "dvdscr", "screener",
        "3d", "amzn", "nf", "dsnp", "hmax", "atvp", "us", "uk", "au", "ca", "nz")

    /**
     * Spells [value] the way release names do: Latin letters without accents and no apostrophes.
     * [umlauts] writes ä, ö, ü and ø as ae, oe, ue and oe; otherwise as a, o, u and o, the other common spelling.
     */
    fun releaseSpelling(value: String, umlauts: Boolean = true): String {
        val letters = buildString {
            for (c in value) {
                val lower = c.lowercaseChar()
                val spelled = (if (umlauts) UMLAUTS[lower] else null) ?: LETTERS[lower]
                if (spelled == null) append(c)
                else append(if (c.isUpperCase()) spelled.replaceFirstChar { it.uppercaseChar() } else spelled)
            }
        }
        // Only marks on Latin letters: in other scripts (kana voicing) they are part of the letter.
        val unaccented = withoutLatinMarks(Normalizer.normalize(letters, Normalizer.Form.NFD))
        return Normalizer.normalize(unaccented, Normalizer.Form.NFC).replace(APOSTROPHES, "")
    }

    /**
     * Drops the combining marks that follow a Latin letter. Done without a regex: Android's
     * regex engine does not support script names such as the IsLatin property.
     */
    private fun withoutLatinMarks(decomposed: String): String = buildString {
        var afterLatin = false
        for (c in decomposed) {
            if (c.isCombiningMark()) {
                if (!afterLatin) append(c)
            } else {
                afterLatin = c.isLatinLetter()
                append(c)
            }
        }
    }

    /** Lowercase words of a title or release name, for comparing one with the other. */
    private fun words(value: String, umlauts: Boolean = true) = releaseSpelling(value, umlauts).lowercase(Locale.ROOT)
        .replace("&", " and ").replace(NON_WORD, " ").trim()

    /** Whether [value] has a Latin letter, the script indexers search and name releases in. */
    fun hasLatinLetter(value: String) = value.any { it.isLatinLetter() }

    private fun Char.isLatinLetter() = isLetter() && Character.UnicodeScript.of(code) == Character.UnicodeScript.LATIN

    private fun Char.isCombiningMark() = category.let { it == CharCategory.NON_SPACING_MARK || it == CharCategory.COMBINING_SPACING_MARK || it == CharCategory.ENCLOSING_MARK }

    private val UMLAUTS = mapOf('ä' to "ae", 'ö' to "oe", 'ü' to "ue", 'ø' to "oe")
    private val LETTERS = mapOf('ß' to "ss", 'æ' to "ae", 'œ' to "oe", 'ø' to "o", 'đ' to "d", 'ð' to "d",
        'ł' to "l", 'þ' to "th", 'ı' to "i")

    /**
     * Keeps one copy of a release found by several indexers: the one from the highest
     * indexer priority (then list order) that passes the filters. Names are compared
     * ignoring case and punctuation, since indexers write separators differently.
     */
    fun withoutDuplicates(releases: List<UsenetRelease>, config: UsenetSourceConfiguration,
        now: Long = System.currentTimeMillis(), seasonEpisodes: Map<Int, Int>? = null,
        series: Boolean = false): List<UsenetRelease> {
        val order = config.indexers.mapIndexed { i, indexer -> indexer.id to i }.toMap()
        fun name(release: UsenetRelease) =
            release.title.lowercase(Locale.ROOT).replace(NON_WORD, " ").trim()
        return releases.groupBy(::name).values.map { copies ->
            // A preferred copy hidden by a filter (no size, passworded) must not hide the others.
            copies.filter { arrange(listOf(it), config, now, seasonEpisodes, series).isNotEmpty() }.ifEmpty { copies }
                .minBy { order[it.indexerId] ?: Int.MAX_VALUE }
        }
    }

    /**
     * [seasonEpisodes] lets a season pack be filtered and sorted by its size per episode.
     * [rank] orders before the sort and before the result limit, so releases known to play are never
     * the ones cut and unplayable ones are cut first.
     */
    fun arrange(releases: List<UsenetRelease>, config: UsenetSourceConfiguration,
        now: Long = System.currentTimeMillis(), seasonEpisodes: Map<Int, Int>? = null, series: Boolean = false,
        rank: (UsenetRelease) -> Int = { 0 }): List<UsenetRelease> {
        val priority = config.indexers.mapIndexed { i, indexer -> indexer.id to i }.toMap()
        val filtered = releases.filter {
            !it.passworded && (!config.excludeLowQuality || !it.lowQuality) && (!config.excludeFullDisc || !it.fullDisc) &&
                (config.minResolution == 0 || it.resolution >= config.minResolution) &&
                (config.maxSizeGb == 0 || it.episodeSize(seasonEpisodes) in 1..config.maxSizeGb.toLong() * 1024 * 1024 * 1024) &&
                (config.maxAgeDays == 0 || it.publishedAt > 0 && now - it.publishedAt <= config.maxAgeDays.toLong() * 86400000)
        }
        config.ranking.profile()?.let { profile ->
            val useLanguages = profile.preferredLanguages.isNotEmpty() || profile.rules.any { rule ->
                rule.enabled && rule.conditions.any { it.field == StreamRankingField.LANGUAGE }
            }
            val candidates = filtered.distinctBy { it.nzbUrl }.map { release ->
                val perEpisodeKnown = release.packSeasons.isEmpty() ||
                    release.packSeasons.all { (seasonEpisodes?.get(it) ?: 0) > 0 }
                StreamRankingCandidate(release, release.rankingFacts.copy(
                    knownLanguages = if (useLanguages) release.rankingFacts.languages else null,
                    sizeBytes = release.episodeSize(seasonEpisodes).takeIf { it > 0 && perEpisodeKnown },
                    publishedAt = release.publishedAt.takeIf { it > 0 }, indexer = release.indexerName,
                    mediaType = if (series) "series" else "movie"), release.nzbUrl,
                    reliability = rank(release), indexerOrder = priority[release.indexerId] ?: Int.MAX_VALUE)
            }
            return StreamRankingEngines.get(profile).rank(candidates, now).take(config.resultLimit).map { it.candidate.value }
        }
        val primary = when (config.sort) {
            UsenetSort.QUALITY -> compareByDescending<UsenetRelease> { it.resolution }
            UsenetSort.LARGEST -> compareByDescending<UsenetRelease> { it.episodeSize(seasonEpisodes) }
            UsenetSort.SMALLEST -> compareBy<UsenetRelease> {
                it.episodeSize(seasonEpisodes).takeIf { size -> size > 0 } ?: Long.MAX_VALUE
            }
            UsenetSort.NEWEST -> compareByDescending<UsenetRelease> { it.publishedAt }
            UsenetSort.INDEXER -> compareBy<UsenetRelease> { priority[it.indexerId] ?: Int.MAX_VALUE }
        }
        return filtered.sortedWith(compareBy(rank).then(primary).thenBy { priority[it.indexerId] ?: Int.MAX_VALUE }
            .thenByDescending { it.resolution }.thenByDescending { it.episodeSize(seasonEpisodes) }.thenBy { it.title }.thenBy { it.nzbUrl })
            .distinctBy { it.nzbUrl }.take(config.resultLimit)
    }

    private fun document(xml: String): Element {
        require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)) { "Invalid indexer XML" }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
        val root = factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
        if (root.localName == "error") {
            val code = root.getAttribute("code")
            if (code in setOf("429", "500", "501")) throw NewznabQuotaException()
            if (code in setOf("100", "101", "102")) throw NewznabAuthException()
            if (code in setOf("200", "201", "202", "203")) throw NewznabUnsupportedSearchException()
            error("Indexer returned an API error")
        }
        return root
    }

    /**
     * Checks the answer to a key check's search. Results accept the key, and so does a search refused
     * only for its parameters (Newznab 200-203). Anything else, such as a login page, fails.
     */
    fun requireSearchAnswer(xml: String) {
        val root = try { document(xml) } catch (_: NewznabUnsupportedSearchException) { return }
        require(root.localName == "rss") { "Invalid indexer search response" }
    }

    /** Some indexers pair an HTTP error status with a Newznab quota or authentication error document. */
    fun throwIfKnownError(xml: String) {
        try { document(xml) }
        catch (e: NewznabQuotaException) { throw e }
        catch (e: NewznabAuthException) { throw e }
        catch (_: Exception) { }
    }
}

/** The indexer reported its request or download quota as spent (Newznab 429/500/501). */
internal class NewznabQuotaException : IllegalStateException("Indexer rate limit reached")

/** The indexer accepted the request but not its search parameters (Newznab 200-203). */
internal class NewznabUnsupportedSearchException : IllegalStateException("Indexer does not support this search")

/** The indexer rejected the API key or account (Newznab 100-102). */
internal class NewznabAuthException : IllegalStateException("Indexer authentication failed")
