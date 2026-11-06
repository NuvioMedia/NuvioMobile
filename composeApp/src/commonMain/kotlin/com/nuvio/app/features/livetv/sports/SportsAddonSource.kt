package com.nuvio.app.features.livetv.sports

import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.addonTransportBaseUrl
import com.nuvio.app.features.addons.encodeAddonPathSegment
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.livetv.data.CivilTime
import com.nuvio.app.features.livetv.data.LiveTvTimeZones
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvClock
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Games from the sports addons you already have installed (HighFly, StremVerse, MediaFusion…).
 * Same behaviour as the TV app: every enabled addon is looked at, one counts as a sports addon
 * when it has catalogs of live games (not movies or series), a game on the Sports page is found
 * in its list by both team names and the start time, and that game's own streams are added to
 * the game's channel list; for a finished game, replays and highlights go in their own list.
 */
internal object SportsAddonSource {
    /** What one game has: streams to watch it now, and (finished games) replays and highlights. */
    data class Found(val live: List<ChannelMatch>, val vod: List<ChannelMatch>)

    /** One game as an addon lists it. */
    internal data class AddonGame(
        val addon: ManagedAddon,
        val type: String,
        val id: String,
        val name: String,
        val text: String,
        val nameNorm: String,
        val timeMs: Long?
    )

    private class Cached(val at: Long, val list: List<AddonGame>)

    private val cache = HashMap<String, Cached>()
    private val cacheLock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun find(ev: SportsEvent): Found = coroutineScope {
        val installed = AddonRepository.uiState.value.addons.filter { it.isActive }.distinctBy { it.manifestUrl }
        val parts = installed.map { a -> async { runCatching { forAddon(a, ev) }.getOrDefault(NO_RESULT) } }.map { it.await() }
        Found(parts.flatMap { it.live }, parts.flatMap { it.vod })
    }

    private suspend fun forAddon(a: ManagedAddon, ev: SportsEvent): Found {
        val catalogs = a.manifest?.catalogs.orEmpty()
        val live = catalogs.filter { isLiveCatalog(it) }.let { strong ->
            if (strong.isNotEmpty()) strong
            else catalogs.filter { it.type.equals("sport", true) && !isVodCatalog(it) && !needsGenre(it) }
        }
        val vod = catalogs.filter { isVodCatalog(it) && usableForVod(it) }
        if (live.isEmpty() && vod.isEmpty()) return NO_RESULT
        return if (ev.state == GameState.POST) Found(emptyList(), vodFor(a, vod, ev))
        else Found(liveFor(a, live, ev), emptyList())
    }

    // ------------------------------------------------------------------ live

    private suspend fun liveFor(a: ManagedAddon, cats: List<AddonCatalog>, ev: SportsEvent): List<ChannelMatch> {
        val hits = LinkedHashMap<String, AddonGame>()
        for (c in cats.take(MAX_CATALOGS)) {
            var found = gamesOf(a, c, emptyMap(), LIVE_TTL).filter { gameScore(ev, it, false) > 0 }
            if (found.isEmpty() && supportsSearch(c)) {
                for (q in searchWords(ev)) {
                    found = gamesOf(a, c, mapOf("search" to q), LIVE_TTL).filter { gameScore(ev, it, false) > 0 }
                    if (found.isNotEmpty()) break
                }
            }
            found.forEach { hits.putIfAbsent(it.id, it) }
            if (hits.size >= 3) break
        }
        val out = ArrayList<ChannelMatch>()
        for (g in hits.values.take(3)) {
            streamsOf(g).forEachIndexed { i, s ->
                out += ChannelMatch(
                    channel = channelFor(g, s, i, s.label),
                    score = ADDON_SCORE,
                    how = "From ${a.displayTitle}",
                    program = null,
                    quality = SportsMatcher.nameQuality(s.label),
                    catchup = false
                )
            }
        }
        return out.take(MAX_STREAMS)
    }

    // ------------------------------------------------------------------ replays and highlights

    private suspend fun vodFor(a: ManagedAddon, cats: List<AddonCatalog>, ev: SportsEvent): List<ChannelMatch> {
        val sport = SportsCatalog.league(ev.leagueId)?.sport ?: Sport.OTHER
        val hits = LinkedHashMap<String, Pair<AddonGame, String>>()
        for (c in cats.take(MAX_CATALOGS)) {
            val extra = LinkedHashMap<String, String>()
            val genre = c.extra.firstOrNull { it.name.equals("genre", true) }
            if (genre != null && genre.isRequired) {
                extra["genre"] = pickGenre(genre.options, sport) ?: continue
            }
            val kind = if (isHighlightCatalog(c)) "Highlights" else "Replay"
            var found = gamesOf(a, c, extra, VOD_TTL).filter { gameScore(ev, it, true) > 0 }
            if (found.isEmpty() && supportsSearch(c)) {
                for (q in searchWords(ev)) {
                    found = gamesOf(a, c, extra + ("search" to q), VOD_TTL).filter { gameScore(ev, it, true) > 0 }
                    if (found.isNotEmpty()) break
                }
            }
            // A recaps list with no search (HighFly's "Sport Recaps"): look a few pages further back.
            if (found.isEmpty() && !supportsSearch(c)) {
                var skip = 0
                var page = gamesOf(a, c, extra, VOD_TTL)
                repeat(3) {
                    if (found.isNotEmpty() || page.isEmpty()) return@repeat
                    skip += page.size
                    page = gamesOf(a, c, extra + ("skip" to skip.toString()), VOD_TTL)
                    found = page.filter { gameScore(ev, it, true) > 0 }
                }
            }
            found.forEach { hits.putIfAbsent(it.id, it to kind) }
            if (hits.size >= 6) break
        }
        val out = ArrayList<ChannelMatch>()
        for ((g, kind) in hits.values.take(6)) {
            val streams = streamsOf(g)
            streams.forEachIndexed { i, s ->
                val title = if (streams.size > 1) g.name + " · " + s.label else g.name
                out += ChannelMatch(
                    channel = channelFor(g, s, i, title),
                    score = ADDON_SCORE,
                    how = "$kind · ${a.displayTitle}",
                    program = null,
                    quality = SportsMatcher.nameQuality(s.label),
                    catchup = false
                )
            }
        }
        return out
    }

    // ------------------------------------------------------------------ talking to the addon

    private fun text(c: AddonCatalog) = (c.id + " " + c.name).lowercase()

    private fun isLiveCatalog(c: AddonCatalog): Boolean {
        if (c.type.lowercase() in NOT_SPORTS_TYPES) return false
        if (isVodCatalog(c) || needsGenre(c)) return false
        val t = text(c)
        if (SKIP_WORDS.any { t.contains(it) }) return false
        return LIVE_WORDS.any { t.contains(it) }
    }

    private fun isVodCatalog(c: AddonCatalog): Boolean {
        if (c.type.lowercase() in NOT_SPORTS_TYPES) return false
        val t = text(c)
        return VOD_WORDS.any { t.contains(it) }
    }

    private fun isHighlightCatalog(c: AddonCatalog): Boolean = text(c).contains("highlight")

    /** A catalog asking for a sport first (and for nothing else) can be used for replays; other required extras can't. */
    private fun usableForVod(c: AddonCatalog): Boolean =
        c.extra.none { it.isRequired && !it.name.equals("genre", true) && !it.name.equals("skip", true) }

    private fun needsGenre(c: AddonCatalog): Boolean =
        c.extra.any { it.isRequired && it.name.equals("genre", true) }

    private fun supportsSearch(c: AddonCatalog): Boolean = c.extra.any { it.name.equals("search", true) }

    private fun pickGenre(options: List<String>, sport: Sport): String? {
        if (options.isEmpty()) return null
        val wanted = GENRE_WORDS[sport].orEmpty()
        for (w in wanted) options.firstOrNull { it.trim().equals(w, true) }?.let { return it }
        return options.firstOrNull { it.trim().equals("all", true) }
    }

    private fun searchWords(ev: SportsEvent): List<String> =
        listOfNotNull(ev.home?.team?.name, ev.away?.team?.name).distinct().filter { it.length >= 3 }.take(2)
            .ifEmpty { listOf(ev.title.substringBefore(" vs ").take(30)).filter { it.length >= 3 } }

    /** The addon's address without "/manifest.json", and its "?config" tail. */
    private fun base(a: ManagedAddon): Pair<String, String> {
        val path = addonTransportBaseUrl(a.manifestUrl).trimEnd('/')
        val q = a.manifestUrl.indexOf('?')
        return path to (if (q >= 0) a.manifestUrl.substring(q) else "")
    }

    private fun enc(s: String): String = s.encodeAddonPathSegment()

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private suspend fun gamesOf(a: ManagedAddon, c: AddonCatalog, extra: Map<String, String>, ttl: Long): List<AddonGame> {
        val key = a.manifestUrl + "|" + c.type + "|" + c.id + "|" + extra.entries.joinToString("&") { it.key + "=" + it.value }
        val now = LiveTvClock.nowMs()
        cacheLock.withLock { cache[key] }?.let { hit -> if (now - hit.at < ttl) return hit.list }
        val (path, query) = base(a)
        val args = if (extra.isEmpty()) "" else "/" + extra.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
        val url = "$path/catalog/${enc(c.type)}/${enc(c.id)}$args.json$query"
        val list = try {
            val metas = json.parseToJsonElement(httpGetText(url)).jsonObject["metas"] as? JsonArray ?: return emptyList()
            metas.mapNotNull { el ->
                val m = el as? JsonObject ?: return@mapNotNull null
                val id = m.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = m.str("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val extraText = listOfNotNull(m.str("description"), m.str("releaseInfo"), m.str("released"))
                AddonGame(
                    addon = a,
                    type = m.str("type")?.takeIf { it.isNotBlank() } ?: c.type,
                    id = id,
                    name = name,
                    text = SportsCatalog.norm(name + " " + extraText.joinToString(" ")),
                    nameNorm = SportsCatalog.norm(name),
                    timeMs = parseTime(extraText + name)
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            return emptyList()
        }
        cacheLock.withLock {
            if (cache.size > 200) cache.clear()
            cache[key] = Cached(now, list)
        }
        return list
    }

    private data class AddonStream(val url: String, val label: String, val headers: Map<String, String>)

    private suspend fun streamsOf(g: AddonGame): List<AddonStream> {
        val (path, query) = base(g.addon)
        val url = "$path/stream/${enc(g.type)}/${enc(g.id)}.json$query"
        return try {
            val arr = json.parseToJsonElement(httpGetText(url)).jsonObject["streams"] as? JsonArray ?: return emptyList()
            arr.mapNotNull { el ->
                val s = el as? JsonObject ?: return@mapNotNull null
                val link = s.str("url")?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return@mapNotNull null
                val first = listOfNotNull(s.str("name"), s.str("title") ?: s.str("description")?.lineSequence()?.firstOrNull())
                    .map { it.trim() }.filter { it.isNotEmpty() }
                    .joinToString(" · ").take(70)
                val headers = (((s["behaviorHints"] as? JsonObject)?.get("proxyHeaders") as? JsonObject)?.get("request") as? JsonObject)
                    ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty()
                AddonStream(link, first.ifBlank { g.addon.displayTitle }, headers)
            }.distinctBy { it.url }.take(MAX_STREAMS)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun channelFor(g: AddonGame, s: AddonStream, index: Int, name: String): LiveChannel = LiveChannel(
        key = "addon:" + g.addon.manifestUrl.hashCode().toUInt().toString(16) + ":" + g.id + ":" + index,
        sourceId = SOURCE_ID,
        sourceName = g.addon.displayTitle,
        name = name,
        tvgId = null,
        tvgName = null,
        logo = g.addon.manifest?.logoUrl,
        groupId = SOURCE_ID,
        group = g.addon.displayTitle,
        number = 0,
        url = s.url,
        headers = s.headers
    )

    const val SOURCE_ID = "addon:sports"
    private val NO_RESULT = Found(emptyList(), emptyList())
    /** Above a guess, below a channel you picked yourself. */
    const val ADDON_SCORE = 100

    private const val LIVE_TTL = 90_000L
    private const val VOD_TTL = 10 * 60_000L
    private const val MAX_CATALOGS = 6
    private const val MAX_STREAMS = 12

    private val NOT_SPORTS_TYPES = setOf("movie", "series", "anime")
    private val VOD_WORDS = listOf("replay", "recap", "highlight", "catchup", "catch up", "catch-up")
    private val SKIP_WORDS = listOf("iptv", "channel", "24/7", "news", "vod", "music", "radio")
    private val LIVE_WORDS = listOf("live", "today", "event", "match", "game", "fixture", "upcoming", "tonight")

    private val GENRE_WORDS: Map<Sport, List<String>> = mapOf(
        Sport.FOOTBALL to listOf("football", "soccer"),
        Sport.AMERICAN_FOOTBALL to listOf("american football", "nfl"),
        Sport.BASKETBALL to listOf("basketball", "nba"),
        Sport.HOCKEY to listOf("hockey", "ice hockey", "nhl"),
        Sport.BASEBALL to listOf("baseball", "mlb"),
        Sport.RUGBY to listOf("rugby"),
        Sport.FIGHTING to listOf("fight", "boxing", "mma", "ufc"),
        Sport.RACING to listOf("motorsport", "racing", "f1", "formula 1"),
        Sport.GOLF to listOf("golf"),
        Sport.TENNIS to listOf("tennis"),
        Sport.CRICKET to listOf("cricket")
    )

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val DAY_STAMP = Regex(
        "(\\d{1,2})\\s+([A-Za-z]{3})[A-Za-z]*\\s+(\\d{4})\\D{1,6}(\\d{1,2}):(\\d{2})\\s*" +
            "((?:UTC|GMT)\\s?[+-]\\d{1,2}(?::?\\d{2})?|[A-Za-z]+/[A-Za-z_]+(?:/[A-Za-z_]+)?|[A-Z]{3,5})",
        RegexOption.IGNORE_CASE
    )
    private val ISO_STAMP = Regex("(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2})")
    private val SIDE_SPLIT = Regex("\\s(?:vs|v|versus|at)\\s")
    private val OFFSET_ZONE = Regex("(?:UTC|GMT)\\s?([+-])(\\d{1,2})(?::?(\\d{2}))?", RegexOption.IGNORE_CASE)

    /** Zone names that mean one fixed offset (hours from UTC). Ambiguous ones (IST, CST in Asia…) aren't here. */
    private val NAMED_ZONES = mapOf(
        "UTC" to 0.0, "GMT" to 0.0, "Z" to 0.0, "UT" to 0.0, "WET" to 0.0,
        "EST" to -5.0, "EDT" to -4.0, "CST" to -6.0, "CDT" to -5.0,
        "MST" to -7.0, "MDT" to -6.0, "PST" to -8.0, "PDT" to -7.0,
        "AKST" to -9.0, "AKDT" to -8.0, "HST" to -10.0,
        "BST" to 1.0, "WEST" to 1.0, "CET" to 1.0, "CEST" to 2.0, "EET" to 2.0, "EEST" to 3.0, "MSK" to 3.0,
        "AWST" to 8.0, "ACST" to 9.5, "ACDT" to 10.5, "AEST" to 10.0, "AEDT" to 11.0, "NZST" to 12.0, "NZDT" to 13.0,
        "JST" to 9.0, "KST" to 9.0
    )

    /** [y]-[mo] (0-based month)-[d] [h]:[mi] read as UTC. */
    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        (CivilTime.daysFromCivil(y, mo + 1, d) * 86_400L + h * 3_600L + mi * 60L) * 1_000L

    /** A time written in a named zone ("UTC", "EST", "GMT+1", "America/New_York"), in UTC; null when the zone isn't one we can be sure of. */
    internal fun zonedToUtc(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: String): Long? {
        val z = zone.trim()
        if (z.contains('/')) {
            if (!LiveTvTimeZones.isKnown(z)) return null
            return CivilTime.toEpochMs(y, mo + 1, d, h, mi, 0, z)
        }
        OFFSET_ZONE.matchEntire(z)?.let { m ->
            val sign = if (m.groupValues[1] == "-") -1 else 1
            val mins = m.groupValues[2].toInt() * 60 + (m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: 0)
            return utc(y, mo, d, h, mi) - sign * mins * 60_000L
        }
        val hours = NAMED_ZONES[z.uppercase()] ?: return null
        return utc(y, mo, d, h, mi) - (hours * 3_600_000L).toLong()
    }

    /** The start time an addon writes into a game ("06 Nov 2026 · 01:15 UTC", in another zone, or an ISO date), in UTC. */
    internal fun parseTime(texts: List<String>): Long? {
        for (t in texts) {
            DAY_STAMP.find(t)?.let { m ->
                val mo = MONTHS.indexOf(m.groupValues[2].lowercase())
                if (mo >= 0) {
                    zonedToUtc(m.groupValues[3].toInt(), mo, m.groupValues[1].toInt(), m.groupValues[4].toInt(), m.groupValues[5].toInt(), m.groupValues[6])
                        ?.let { return it }
                }
            }
            ISO_STAMP.find(t)?.let { m ->
                return utc(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt(), m.groupValues[4].toInt(), m.groupValues[5].toInt())
            }
        }
        return null
    }

    private fun teamNames(t: TeamInfo): List<String> =
        listOf(t.fullName, t.name).map { SportsCatalog.norm(it) }.filter { it.trim().length >= 4 }.distinct()

    /** One side of an addon's title is this team: "Leeds" is Leeds United, "Baltimore Ravens" is the Ravens. */
    private fun sideIs(t: TeamInfo, side: String): Boolean {
        val s = side.trim()
        if (s.length < 3) return false
        return teamNames(t).any { n -> n == side || (s.length >= 4 && n.contains(side)) || side.contains(n) }
    }

    /**
     * How well an addon's game fits [ev]: 0 = not the same game. Two-team games need both teams
     * and the start time (within 5 hours; 36 for replays, which are often posted later); other
     * events (a race, a fight card) need most of their words.
     */
    internal fun gameScore(ev: SportsEvent, g: AddonGame, replay: Boolean): Int {
        val away = ev.away?.team
        val home = ev.home?.team
        val teamsOk: Boolean
        if (away != null && home != null) {
            val sides = SIDE_SPLIT.split(g.nameNorm.trim().let { " $it " }).map { " ${it.trim()} " }
            teamsOk = if (sides.size == 2) {
                (sideIs(away, sides[0]) && sideIs(home, sides[1])) || (sideIs(away, sides[1]) && sideIs(home, sides[0]))
            } else {
                teamNames(away).any { g.nameNorm.contains(it) } && teamNames(home).any { g.nameNorm.contains(it) }
            }
        } else {
            val words = SportsCatalog.norm(ev.title).trim().split(" ").filter { it.length >= 4 }.distinct()
            if (words.size < 2) return 0
            val hit = words.count { g.nameNorm.contains(" $it ") }
            teamsOk = hit >= 2 && hit * 10 >= words.size * 6
        }
        if (!teamsOk) return 0
        val t = g.timeMs
        val window = (if (replay) 36L else 5L) * 3_600_000L
        return when {
            t != null && ev.hasTime -> if (kotlin.math.abs(t - ev.startMs) <= window) 100 else 0
            // No time on one side: live games must be on around now; a replay must be recent.
            else -> if (kotlin.math.abs(LiveTvClock.nowMs() - ev.startMs) <= (if (replay) 10L * 24 else 20L) * 3_600_000L) 60 else 0
        }
    }
}
