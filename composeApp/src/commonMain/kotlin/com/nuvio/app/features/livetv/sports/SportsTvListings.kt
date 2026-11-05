package com.nuvio.app.features.livetv.sports

/**
 * One game in TheSportsDB's TV listings (nuvio-data's sportsdb/tv.json): its name, start, and
 * its channels by country (lowercase country name -> channel names).
 */
internal class TvListing(
    val name: String,
    val startMs: Long,
    val channels: Map<String, List<String>>
) {
    /** "Arsenal vs Chelsea" -> the words of each side (null when the name isn't two sides). */
    val sideA: List<String>?
    val sideB: List<String>?
    val words: List<String> = SportsTvListings.words(name)

    init {
        val parts = SIDES.split(name.trim(), 2)
        if (parts.size == 2) { sideA = SportsTvListings.words(parts[0]); sideB = SportsTvListings.words(parts[1]) }
        else { sideA = null; sideB = null }
    }

    private companion object {
        val SIDES = Regex("\\s+(?:vs\\.?|v|@|at)\\s+", RegexOption.IGNORE_CASE)
    }
}

/**
 * Foreign (and home) channels for games, from TheSportsDB's TV listings. ESPN names only US
 * networks; TheSportsDB lists a game's channels in dozens of countries. A game's channels from
 * YOUR country (the "Channels from" setting, else your Sports region) are added to its networks,
 * so the channel search finds Sky Sports or DAZN the same way it finds ESPN.
 *
 * Nothing here asks the guide or the network: the file is read like the other nuvio-data files,
 * and a game's listing is found by its teams' names and start time, once per game.
 */
internal object SportsTvListings {
    private const val HOUR = 3_600_000L
    /** A game's listing starts within this much of the game's own start. */
    private const val NEAR_MS = 3 * HOUR
    /** The most channels added to one game. */
    private const val MAX_ADDED = 6
    /** How the US is written in [countriesFor]'s set. */
    private val US_NAMES: Set<String> by lazy { namesOf(listOf("US")) }

    fun parse(raw: String): List<TvListing> {
        val arr = JSONObject(raw).optJSONArray("events") ?: return emptyList()
        val out = ArrayList<TvListing>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("n").trim()
            val start = parseIso(o.optString("t")) ?: continue
            val c = o.optJSONObject("c") ?: continue
            val byCountry = HashMap<String, List<String>>()
            for (k in c.keys()) {
                val a = c.optJSONArray(k) ?: continue
                val list = (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() }
                if (list.isNotEmpty()) byCountry[countryKey(k)] = list
            }
            if (name.isNotEmpty() && byCountry.isNotEmpty()) out += TvListing(name, start, byCountry)
        }
        return out
    }

    /** "2026-11-08T16:30:00Z" -> millis. */
    private fun parseIso(s: String): Long? = SportsDates.parseIso(s)

    // ------------------------------------------------------------------ countries

    private fun countryKey(name: String): String = name.trim().lowercase().removePrefix("the ").trim()

    /** ISO code -> the country as TheSportsDB writes it, with other short forms channel names use. */
    private val COUNTRIES: List<Triple<String, String, List<String>>> = listOf(
        Triple("US", "United States", listOf("us", "usa")),
        Triple("GB", "United Kingdom", listOf("uk", "gb")),
        Triple("IE", "Ireland", listOf("ie")),
        Triple("CA", "Canada", listOf("ca")),
        Triple("AU", "Australia", listOf("au")),
        Triple("NZ", "New Zealand", listOf("nz")),
        Triple("ES", "Spain", listOf("es")),
        Triple("FR", "France", listOf("fr")),
        Triple("DE", "Germany", listOf("de")),
        Triple("IT", "Italy", listOf("it")),
        Triple("PT", "Portugal", listOf("pt")),
        Triple("NL", "The Netherlands", listOf("nl", "netherlands")),
        Triple("BE", "Belgium", listOf("be")),
        Triple("AT", "Austria", listOf("at")),
        Triple("CH", "Switzerland", listOf("ch")),
        Triple("SE", "Sweden", listOf("se")),
        Triple("NO", "Norway", listOf("no")),
        Triple("DK", "Denmark", listOf("dk")),
        Triple("FI", "Finland", listOf("fi")),
        Triple("IS", "Iceland", listOf("is")),
        Triple("PL", "Poland", listOf("pl")),
        Triple("CZ", "Czechia", listOf("cz")),
        Triple("SK", "Slovakia", listOf("sk")),
        Triple("HU", "Hungary", listOf("hu")),
        Triple("RO", "Romania", listOf("ro")),
        Triple("BG", "Bulgaria", listOf("bg")),
        Triple("GR", "Greece", listOf("gr")),
        Triple("TR", "Turkey", listOf("tr")),
        Triple("HR", "Croatia", listOf("hr")),
        Triple("RS", "Serbia", listOf("rs")),
        Triple("SI", "Slovenia", listOf("si")),
        Triple("UA", "Ukraine", listOf("ua")),
        Triple("RU", "Russia", listOf("ru")),
        Triple("IN", "India", listOf("in")),
        Triple("QA", "Qatar", listOf("qa")),
        Triple("SA", "Saudi Arabia", listOf("sa", "ksa")),
        Triple("AE", "United Arab Emirates", listOf("ae", "uae")),
        Triple("EG", "Egypt", listOf("eg")),
        Triple("ZA", "South Africa", listOf("za")),
        Triple("BR", "Brazil", listOf("br")),
        Triple("MX", "Mexico", listOf("mx")),
        Triple("AR", "Argentina", listOf("ar")),
        Triple("CO", "Colombia", listOf("co")),
        Triple("CL", "Chile", listOf("cl")),
        Triple("JP", "Japan", listOf("jp")),
        Triple("KR", "South Korea", listOf("kr")),
        Triple("CN", "China", listOf("cn")),
        Triple("SG", "Singapore", listOf("sg")),
        Triple("IL", "Israel", listOf("il"))
    )

    private val BY_ISO = COUNTRIES.associateBy { it.first }
    private val BY_NAME = COUNTRIES.associateBy { countryKey(it.second) }

    /** Countries for each language choice ("lang:es" in Channels from). */
    private val LANGUAGE_COUNTRIES = mapOf(
        "es" to listOf("ES", "MX", "AR", "CO", "CL"),
        "fr" to listOf("FR", "BE"),
        "de" to listOf("DE", "AT", "CH"),
        "it" to listOf("IT"),
        "pt" to listOf("PT", "BR"),
        "nl" to listOf("NL", "BE"),
        "pl" to listOf("PL"),
        "tr" to listOf("TR"),
        "ar" to listOf("QA", "SA", "AE", "EG"),
        "ru" to listOf("RU")
    )

    private fun namesOf(isos: Collection<String>): Set<String> = isos.mapNotNull { BY_ISO[it] }
        .flatMap { listOf(countryKey(it.second)) + (if (it.first == "US") listOf("usa") else emptyList()) }.toSet()

    /**
     * The countries whose channels are added to games: the "Channels from" choice, else your Sports
     * region (and, for "Somewhere else", the phone's own country). Lowercase, as the file's keys.
     */
    fun countriesFor(s: SportsSettings): Set<String> {
        val english = namesOf(listOf("GB", "US", "IE", "CA", "AU", "NZ"))
        return when (val choice = s.channelRegion) {
            "" -> when (val region = SportsCatalog.regionOf(s)) {
                "US" -> namesOf(listOf("US"))
                "UK" -> namesOf(listOf("GB"))
                "IE" -> namesOf(listOf("IE"))
                "CA" -> namesOf(listOf("CA"))
                "AU" -> namesOf(listOf("AU", "NZ"))
                else -> namesOf(listOf(SportsPlatform.countryCode().uppercase().let { if (it == "UK") "GB" else it }))
            }
            ChannelRegions.ENGLISH -> english
            "UK" -> namesOf(listOf("GB"))
            "US" -> namesOf(listOf("US"))
            "IE" -> namesOf(listOf("IE"))
            "CA" -> namesOf(listOf("CA"))
            "AU" -> namesOf(listOf("AU", "NZ"))
            else -> if (choice.startsWith("lang:")) namesOf(LANGUAGE_COUNTRIES[choice.removePrefix("lang:")].orEmpty()) else emptySet()
        }
    }

    // ------------------------------------------------------------------ names

    private val HD_WORDS = Regex("(?i)\\b[fu]?hd\\b")

    /** "BeIn Sports HD 1 France" -> "BeIn Sports 1": no quality mark, no country on the end. */
    fun cleanChannel(raw: String, countryKey: String): String {
        var s = raw.replace(HD_WORDS, " ").replace(Regex("\\s+"), " ").trim()
        val info = BY_NAME[countryKey]
        val suffixes = (if (info != null) listOf(countryKey, info.second.lowercase()) + info.third else listOf(countryKey))
            .distinct().sortedByDescending { it.length }
        for (suf in suffixes) {
            if (s.length > suf.length + 1 && s.endsWith(" $suf", ignoreCase = true)) { s = s.dropLast(suf.length + 1).trim(); break }
        }
        return s
    }

    private val GENERIC = setOf(
        "fc", "cf", "afc", "sc", "ac", "cd", "fk", "sk", "club", "the", "and", "of", "de", "la", "le", "el",
        "real", "city", "united", "town", "county", "st", "vs", "at"
    )
    /** Words that say what kind of event it is, not which one (races, tournaments). */
    private val EVENT_NOISE = setOf(
        "grand", "prix", "race", "qualifying", "practice", "sprint", "round", "championship", "series", "cup",
        "open", "final", "finals", "formula", "live", "match", "game", "league", "season", "week", "day"
    )

    /** The words of [text] that tell one team (or event) from another. */
    fun words(text: String): List<String> =
        SportsCatalog.norm(text).trim().split(' ').filter { it.length >= 3 && it.any { c -> c.isLetter() } && it !in GENERIC }.distinct()

    private fun same(a: String, b: String): Boolean {
        if (a == b) return true
        val (short, long) = if (a.length <= b.length) a to b else b to a
        if (short.length >= 3 && long.startsWith(short)) return true
        var n = 0
        while (n < short.length && short[n] == long[n]) n++
        return n >= 5
    }

    private fun anyWord(a: List<String>, b: List<String>) = a.any { x -> b.any { y -> same(x, y) } }

    private fun teamWords(side: Side): List<String> =
        words(side.team.fullName + " " + side.team.name + " " + (side.team.location ?: ""))

    // ------------------------------------------------------------------ matching

    /** The listing that's this game: both teams named (either way round) and a start close to its own. */
    private fun listingFor(ev: SportsEvent, near: List<TvListing>): TvListing? {
        val home = ev.home
        val away = ev.away
        if (home != null && away != null) {
            val h = teamWords(home)
            val a = teamWords(away)
            if (h.isEmpty() || a.isEmpty()) return null
            return near.filter { l ->
                val x = l.sideA
                val y = l.sideB
                x != null && y != null && x.isNotEmpty() && y.isNotEmpty() &&
                    ((anyWord(x, h) && anyWord(y, a)) || (anyWord(x, a) && anyWord(y, h)))
            }.minByOrNull { kotlin.math.abs(it.startMs - ev.startMs) }
        }
        // A race, a tournament, a fight card: its own name's distinctive words.
        val mine = words(ev.title).filter { it !in EVENT_NOISE }
        if (mine.isEmpty()) return null
        return near.filter { l -> kotlin.math.abs(l.startMs - ev.startMs) <= 2 * HOUR }
            .filter { l -> anyWord(l.words.filter { it !in EVENT_NOISE }, mine) }
            .minByOrNull { kotlin.math.abs(it.startMs - ev.startMs) }
    }

    /** What to add to games, kept between calls: the same games are checked again on every score update. */
    class Cache {
        internal var listings: List<TvListing>? = null
        internal var countries: Set<String>? = null
        internal val extras = HashMap<String, List<String>>()
    }

    /**
     * [events] with the channels TheSportsDB lists for them in [countries] added to their networks
     * (named like the guide names them, without a quality mark or country). Games without a
     * listing, or with nothing new, come back as they were.
     */
    fun addChannels(events: List<SportsEvent>, listings: List<TvListing>, countries: Set<String>, cache: Cache): List<SportsEvent> {
        if (listings.isEmpty() || countries.isEmpty() || events.isEmpty()) return events
        if (cache.listings !== listings || cache.countries != countries) {
            cache.listings = listings; cache.countries = countries; cache.extras.clear()
        }
        // Listings by start hour, so a game only looks at the ones around its own start.
        val byHour = HashMap<Long, MutableList<TvListing>>()
        var indexed = false
        fun nearTo(ev: SportsEvent): List<TvListing> {
            if (!indexed) { listings.forEach { byHour.getOrPut(it.startMs / HOUR) { ArrayList() }.add(it) }; indexed = true }
            val h = ev.startMs / HOUR
            return (h - 3..h + 3).flatMap { byHour[it].orEmpty() }.filter { kotlin.math.abs(it.startMs - ev.startMs) <= NEAR_MS }
        }
        return events.map { ev ->
            if (!ev.hasTime) return@map ev
            val key = ev.id + "|" + ev.startMs
            fun norm(n: String) = n.lowercase().filter { c -> c.isLetterOrDigit() }
            // Your countries' channels for this game, as TheSportsDB lists them.
            val mine = cache.extras.getOrPut(key) {
                val l = listingFor(ev, nearTo(ev)) ?: return@getOrPut emptyList()
                val seen = HashSet<String>()
                val out = ArrayList<String>()
                for ((country, names) in l.channels) {
                    if (country !in countries) continue
                    for (raw in names) {
                        val name = cleanChannel(raw, country)
                        if (name.length < 2) continue
                        if (seen.add(norm(name)) && out.size < MAX_ADDED) out += name
                    }
                }
                out
            }
            if (mine.isEmpty()) return@map ev
            if (countries.any { it in US_NAMES }) {
                // US: the schedule's own channels (national, then home team's) stay first; anything new goes after.
                val have = ev.broadcasts.mapTo(HashSet()) { norm(it) }
                val extra = mine.filter { have.add(norm(it)) }
                if (extra.isEmpty()) ev else ev.copy(broadcasts = ev.broadcasts + extra)
            } else {
                // Elsewhere the schedule's channels are American: your own country's go first, so the
                // game card shows where you'd watch it.
                val first = mine.mapTo(HashSet()) { norm(it) }
                ev.copy(broadcasts = mine + ev.broadcasts.filter { norm(it) !in first })
            }
        }
    }
}
