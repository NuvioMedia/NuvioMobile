package com.nuvio.app.features.livetv.data

import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A logo the "Change logo" screen can offer. */
data class LogoChoice(
    val url: String,
    /** What it is: the channel's name in that playlist, guide or library. */
    val name: String,
    /** Where it's from: a playlist's name, a guide's, "tv-logos", "iptv-org". */
    val from: String,
    /** Its group in the list: [PLAYLISTS], [GUIDES], [LIBRARY] or [TMDB]. */
    val group: Int,
    /** How close it is to the channel (higher first). */
    val score: Int,
) {
    companion object {
        const val PLAYLISTS = 0
        const val GUIDES = 1
        const val LIBRARY = 2
        const val TMDB = 3
    }
}

/**
 * The two free logo libraries on GitHub, tv-logos (clean and consistent, strong on US, UK and
 * Europe) and iptv-org (the biggest, worldwide). Only their lists of logos (names and
 * addresses, no pictures) are fetched, and only when Change logo is opened; each list is kept
 * for a week. The logos themselves load as you scroll, like any other picture.
 */
internal object LogoLibrary {
    private const val MAX_AGE = 7L * 24 * 3600 * 1000
    private const val TV_LOGOS_TREE = "https://api.github.com/repos/tv-logo/tv-logos/git/trees/main?recursive=1"
    private const val TV_LOGOS_RAW = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/"
    private const val IPTV_ORG_LOGOS = "https://iptv-org.github.io/api/logos.json"
    private const val TV_LOGOS_FILE = "tv-logos.json"
    private const val IPTV_ORG_FILE = "iptv-org.json"
    private const val USER_AGENT = "NuvioPlusIPTV"

    /** One library logo: [key] is its name reduced for matching ("espn", "skysportsmainevent"). */
    class Entry(val key: String, val label: String, val url: String, val from: String)

    private val lock = Mutex()
    private var tvLogos: List<Entry>? = null
    private var iptvOrg: List<Entry>? = null

    /** Both libraries' lists (fetched if needed). A library that can't be reached, or is in [skip] ("tv-logos", "iptv-org"), is left out. */
    suspend fun entries(skip: Set<String> = emptySet()): List<Entry> = lock.withLock {
        withContext(Dispatchers.Default) {
            val a = if ("tv-logos" in skip) null else tvLogos
                ?: runCatching { loadTvLogos() }.getOrNull()?.also { tvLogos = it }
            val b = if ("iptv-org" in skip) null else iptvOrg
                ?: runCatching { loadIptvOrg() }.getOrNull()?.also { iptvOrg = it }
            a.orEmpty() + b.orEmpty()
        }
    }

    /** Makes sure the list file is on the device and less than a week old (an older one is kept when the fetch fails). */
    private suspend fun ensureFile(name: String, url: String) {
        val fresh = LiveTvFiles.exists(LiveTvDirs.LOGOS, name) &&
            LiveTvFiles.size(LiveTvDirs.LOGOS, name) > 0 &&
            LiveTvClock.nowMs() - LiveTvFiles.lastModified(LiveTvDirs.LOGOS, name) < MAX_AGE
        if (fresh) return
        val ok = runCatching { LiveTvFiles.download(url, LiveTvDirs.LOGOS, name, USER_AGENT) }.isSuccess
        if (!ok && !LiveTvFiles.exists(LiveTvDirs.LOGOS, name)) error("The logo list couldn't be downloaded")
    }

    /** tv-logos: the repository's file list. "countries/united-states/espn-us.png" -> "ESPN · United States". */
    private suspend fun loadTvLogos(): List<Entry> {
        ensureFile(TV_LOGOS_FILE, TV_LOGOS_TREE)
        val out = ArrayList<Entry>()
        val pathRegex = Regex(""""path"\s*:\s*"(countries/[^"]+?\.png)"""", RegexOption.IGNORE_CASE)
        LiveTvFiles.readChunks(LiveTvDirs.LOGOS, TV_LOGOS_FILE) { chunks ->
            // The list is read a piece at a time; the end of each piece is kept for the next one,
            // in case a path was cut in two.
            var carry = ""
            for (chunk in chunks) {
                val text = carry + chunk
                var last = 0
                for (m in pathRegex.findAll(text)) {
                    tvEntry(m.groupValues[1])?.let { out += it }
                    last = m.range.last + 1
                }
                carry = text.substring(maxOf(last, text.length - 400))
            }
        } ?: error("The tv-logos list is missing")
        return out
    }

    private fun tvEntry(path: String): Entry? {
        val parts = path.split('/')
        val file = parts.last().removeSuffix(".png").removeSuffix(".PNG")
        val country = parts.getOrNull(1)?.replace('-', ' ')?.split(' ')?.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        // "espn-us" / "sky-sports-main-event-uk": the last part is the country code.
        val words = file.split('-')
        val nameWords = if (words.size > 1 && words.last().length in 2..3) words.dropLast(1) else words
        val label = nameWords.joinToString(" ") { w -> if (w.length <= 4) w.uppercase() else w.replaceFirstChar { it.uppercase() } }
        val key = LiveTvRepository.normalize(nameWords.joinToString(""))
        if (key.isEmpty()) return null
        val url = TV_LOGOS_RAW + parts.joinToString("/") { it.encodeURLPathPart() }
        return Entry(key, label + (country?.let { " · $it" } ?: ""), url, "tv-logos")
    }

    /** iptv-org: its logos list. "ESPN.us" -> "ESPN (US)". */
    private suspend fun loadIptvOrg(): List<Entry> {
        ensureFile(IPTV_ORG_FILE, IPTV_ORG_LOGOS)
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        val scanner = FlatJsonScanner { f ->
            val ch = f["channel"]
            val u = f["url"]
            if (!ch.isNullOrBlank() && !u.isNullOrBlank() && seen.add(u)) {
                val name = ch.substringBeforeLast('.')
                val country = ch.substringAfterLast('.', "").uppercase()
                val key = LiveTvRepository.normalize(name)
                if (key.isNotEmpty()) {
                    val feed = f["feed"]?.takeIf { it.isNotBlank() }
                    val label = spaced(name) + (feed?.let { " · $it" } ?: "") + (if (country.isNotEmpty()) " ($country)" else "")
                    out += Entry(key, label, u, "iptv-org")
                }
            }
        }
        LiveTvFiles.readChunks(LiveTvDirs.LOGOS, IPTV_ORG_FILE) { chunks ->
            for (chunk in chunks) scanner.feedText(chunk)
            scanner.finish()
        } ?: error("The iptv-org list is missing")
        return out
    }

    /** "SkySportsMainEvent" -> "Sky Sports Main Event". */
    private fun spaced(name: String): String = buildString {
        name.forEachIndexed { i, c ->
            if (i > 0) {
                val p = name[i - 1]
                if ((p.isLowerCase() && (c.isUpperCase() || c.isDigit())) || (p.isDigit() && c.isLetter())) append(' ')
            }
            append(c)
        }
    }
}

/**
 * Title logos from TMDB (the artwork with a show's or film's name, on a clear background): what
 * people want as the logo of a 24/7 channel ("24/7 Friends", "Pixar Movies"). One search, then
 * the logos of the few best matches.
 */
internal object TmdbLogos {
    private const val IMG = "https://image.tmdb.org/t/p/w500"
    private val client by lazy { createApiHttpClient() }

    suspend fun find(name: String): List<LogoChoice> = withContext(Dispatchers.Default) {
        val key = TmdbSettingsRepository.effectiveApiKey()
        if (key.isBlank() || name.isBlank()) return@withContext emptyList()
        val q = name.trim().encodeURLParameter()
        val search = get("https://api.themoviedb.org/3/search/multi?api_key=$key&include_adult=false&query=$q")
            ?: return@withContext emptyList()
        val results = search["results"] as? JsonArray ?: return@withContext emptyList()
        val picks = results.mapNotNull { it as? JsonObject }
            .filter { it.str("media_type") == "tv" || it.str("media_type") == "movie" }
            .take(4)
        val out = ArrayList<LogoChoice>()
        picks.forEachIndexed { rank, r ->
            val type = r.str("media_type")
            val id = r["id"]?.jsonPrimitive?.intOrNull ?: 0
            if (id <= 0) return@forEachIndexed
            val title = r.str(if (type == "tv") "name" else "title").ifBlank { r.str("original_name") }
            val year = r.str(if (type == "tv") "first_air_date" else "release_date").take(4)
            val images = get("https://api.themoviedb.org/3/$type/$id/images?api_key=$key&include_image_language=en,null")
                ?: return@forEachIndexed
            val logos = images["logos"] as? JsonArray ?: return@forEachIndexed
            logos.mapNotNull { it as? JsonObject }
                .sortedByDescending { it["vote_average"]?.jsonPrimitive?.doubleOrNull ?: 0.0 }
                .take(8)
                .forEach { l ->
                    val path = l.str("file_path").takeIf { it.startsWith("/") } ?: return@forEach
                    // TMDB serves .svg logos as .svg only at "original"; ask for the PNG copy.
                    val url = IMG + path.replace(".svg", ".png")
                    val lang = l.str("iso_639_1").takeIf { it.isNotBlank() && it != "null" }?.uppercase()
                    out += LogoChoice(
                        url = url,
                        name = title + (if (year.isNotBlank()) " ($year)" else ""),
                        from = "TMDB · " + (if (type == "tv") "Show" else "Movie") + (lang?.let { " · $it" } ?: ""),
                        group = LogoChoice.TMDB,
                        score = 100 - rank * 10,
                    )
                }
        }
        out
    }

    private fun JsonObject.str(key: String): String = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty()

    private suspend fun get(url: String): JsonObject? = runCatching {
        val response = client.get(url) { header("Accept", "application/json") }
        if (response.status.value != 200) null else Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }.getOrNull()
}

/**
 * A channel's name without what providers dress it up with, for finding its logo: tiny raised or
 * small-capital letters ("ESPN ᴿᴬᵂ", "Sky ʜᴅ"), fancy letters ("𝐄𝐒𝐏𝐍"), country and provider tags in
 * front ("US: ", "UK | ", "[VIP] "), brackets, emoji and quality words (HD, FHD, 4K, RAW, BACKUP…).
 */
internal object ChannelNameCleaner {
    /** Raised (superscript) and small-capital letters: always a tag, never part of the name. */
    private val TINY = Regex("[\\u02B0-\\u02FF\\u1D00-\\u1DBF\\u2070-\\u209F\\uA730-\\uA7FF\\u0262\\u026A\\u0274\\u0280\\u028F\\u0299\\u029C\\u029F]+")
    private val BRACKETS = Regex("""[\[({][^\])}]*[\])}]""")
    private val PIPES = Regex("""\|[^|]{0,12}\|""")
    private val FRONT_TAG = Regex("""^\s*(?:[A-Za-z]{2,3}\d?\s*[:|]\s*|[A-Za-z]{2,3}\s+-\s+)+""")
    private val QUALITY = Regex("""(?i)\b(FHD|UHD|HD|SD|HQ|4K|8K|HEVC|H\.?26[45]|RAW|BACKUP|VIP|MULTI|\d{2,3}\s?FPS|1080[pi]?|720p|2160p)\b""")
    private val SPACES = Regex("""\s+""")

    fun clean(name: String): String {
        // Plain names (most of them) skip the work.
        if (name.all { it.code < 128 && it != '|' && it != ':' && it != '[' && it != '(' && it != '{' } && !QUALITY.containsMatchIn(name)) return name.trim()
        var s = TINY.replace(name, " ")
        s = plainLetters(s)
        s = s.filter { c ->
            when (c.category) {
                CharCategory.OTHER_SYMBOL, CharCategory.MODIFIER_SYMBOL, CharCategory.SURROGATE,
                CharCategory.FORMAT, CharCategory.PRIVATE_USE -> false
                else -> true
            }
        }
        s = BRACKETS.replace(s, " ")
        s = PIPES.replace(s, " ")
        s = FRONT_TAG.replace(s, "")
        s = QUALITY.replace(s, " ")
        s = SPACES.replace(s, " ").trim().trim('-', '|', ':', '.', ' ')
        return s.ifBlank { name.trim() }
    }

    /** Mathematical bold / italic / script letters and digits ("𝐄𝐒𝐏𝐍") back to plain ones. */
    private fun plainLetters(s: String): String {
        if (s.none { it.isHighSurrogate() }) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                val cp = 0x10000 + ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00)
                when (cp) {
                    in 0x1D400..0x1D6A3 -> {
                        val idx = (cp - 0x1D400) % 52
                        sb.append(if (idx < 26) 'A' + idx else 'a' + (idx - 26))
                    }
                    in 0x1D7CE..0x1D7FF -> sb.append('0' + (cp - 0x1D7CE) % 10)
                    else -> { sb.append(c); sb.append(s[i + 1]) }
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
