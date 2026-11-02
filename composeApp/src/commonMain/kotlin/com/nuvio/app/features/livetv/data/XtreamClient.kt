package com.nuvio.app.features.livetv.data

import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.core.network.readBoundedResponseBody
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.livetv.model.PlaylistAccount
import com.nuvio.app.features.livetv.model.PlaylistSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What player_api.php says about an account (user_info and server_info). */
internal data class XtreamAccountInfo(
    /** The account's details worth showing; null when the login was refused. */
    val account: PlaylistAccount?,
    /** "auth" was 0: wrong username or password. */
    val authFailed: Boolean,
    /** user_info.status ("Active", "Expired", "Banned", …). */
    val status: String,
    /** Stream formats this account may use ("ts", "m3u8", "rtmp"); empty when the panel doesn't say. */
    val allowedFormats: Set<String>,
    /** server_info.timezone, when given. */
    val serverTimezone: String?
) {
    /** Why this login can't be used, in plain words, or null when it can. */
    fun loginError(nowMs: Long = LiveTvClock.nowMs()): String? {
        if (authFailed) return "Login failed: check the username and password"
        if (status.isNotBlank() && !status.equals("Active", ignoreCase = true)) return "Account $status"
        val exp = account?.expiresAtMs ?: 0L
        if (exp > 0 && exp < nowMs) {
            val t = CivilTime.at(exp, LiveTvTimeZones.SYSTEM)
            return "Account expired on ${t.year}-${t.month.toString().padStart(2, '0')}-${t.day.toString().padStart(2, '0')}"
        }
        return null
    }
}

/** One live channel from get_live_streams, with the fields the TV app writes into its M3U. */
internal data class XtreamLiveStream(
    val streamId: String,
    val name: String,
    val epgChannelId: String?,
    val icon: String?,
    val categoryId: String?,
    val num: String?,
    val tvArchive: Boolean,
    val tvArchiveDays: String?
)

/** One title from get_vod_streams (a movie) or get_series. */
internal data class XtreamVodItem(
    val isSeries: Boolean,
    /** stream_id for a movie, series_id for a series. */
    val id: String,
    val name: String,
    val icon: String?,
    val categoryId: String,
    val containerExtension: String?,
    val tmdbId: String?,
    val year: String?,
    val rating: String?,
    val addedSec: Long,
    val genre: String?
)

/** A login was refused, or the account can't be used (expired, banned). */
internal class XtreamLoginError(message: String) : Exception(message)

/**
 * The Xtream Codes API (player_api.php), the way the TV app calls it: account check, live
 * categories and streams (written out as an M3U so the rest of Live TV treats a login like any
 * playlist), the movie and series lists for On Demand, and the provider's guide (xmltv.php).
 */
internal class XtreamClient(
    private val client: HttpClient = createApiHttpClient()
) {

    // ---------- account ----------

    /** player_api.php with no action: user_info and server_info. */
    suspend fun accountInfo(pl: PlaylistSource): XtreamAccountInfo {
        require(pl.xtreamBase().isNotBlank()) { "No server set" }
        require(pl.xtreamUsername.isNotBlank() && pl.xtreamPassword.isNotBlank()) { "Username and password are required" }
        return accountInfo(pl.xtreamApiUrl(), pl.userAgent)
    }

    /** Account details behind any player_api.php link (also one built from an M3U channel's link). */
    suspend fun accountInfo(apiUrl: String, userAgent: String): XtreamAccountInfo {
        val info = parseObjectOrNull(getText(apiUrl, userAgent)) ?: error("Not an Xtream API response")
        val ui = info.optJSONObject("user_info")
        if (ui == null && !info.containsKey("user_info")) error("Not an Xtream API response")
        return XtreamAccountInfo(
            account = ui?.let { accountOf(it) },
            authFailed = ui?.optString("auth") == "0",
            status = ui?.optString("status").orEmpty(),
            // Which stream formats this account may use (some panels turn off .ts and only
            // allow .m3u8; asking for .ts then fails on every channel with "bad http status").
            allowedFormats = ui?.optJSONArray("allowed_output_formats")?.strings()?.map { it.lowercase() }?.toSet() ?: emptySet(),
            // Catch-up links are in the server's local time: remember its zone for catch-up.
            serverTimezone = info.optJSONObject("server_info")?.optString("timezone")?.takeIf { it.isNotBlank() }
        )
    }

    /** Xtream user_info → the account details worth showing (null when the login was refused). */
    fun accountOf(ui: JsonObject, nowMs: Long = LiveTvClock.nowMs()): PlaylistAccount? {
        if (ui.optString("auth") == "0") return null
        val exp = ui.optString("exp_date").toLongOrNull()?.takeIf { it > 0 }?.times(1000) ?: 0L
        return PlaylistAccount(
            expiresAtMs = exp,
            status = ui.optString("status"),
            maxConnections = ui.optString("max_connections").toIntOrNull() ?: 0,
            activeConnections = ui.optString("active_cons").toIntOrNull() ?: 0,
            checkedAtMs = nowMs
        )
    }

    /**
     * The server's time zone, learned from the server a channel's link points to (its
     * player_api.php, with the username and password in the link). Null when the link isn't an
     * Xtream one or the server doesn't say.
     */
    suspend fun serverTimezoneFor(channelUrl: String, userAgent: String): String? {
        val m = xtreamLink.find(channelUrl) ?: return null
        val (host, user, pass) = m.destructured
        return runCatching {
            val url = "$host/player_api.php?username=" + user.encodeURLParameter() + "&password=" + pass.encodeURLParameter()
            parseObjectOrNull(getText(url, userAgent))?.optJSONObject("server_info")?.optString("timezone")?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * An M3U link from an Xtream provider (…/get.php?username=…&password=…): the same server's
     * player_api.php has the account's expiry date and connections. Null for other links.
     */
    fun m3uAccountUrl(url: String): String? = runCatching {
        val u = Url(url.trim())
        if (!u.encodedPath.endsWith("get.php", ignoreCase = true)) return@runCatching null
        val q = u.encodedQuery.ifBlank { return@runCatching null }.split('&')
            .associate { it.substringBefore('=').lowercase() to it.substringAfter('=', "") }
        val user = q["username"]?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val pass = q["password"] ?: return@runCatching null
        val port = if (u.specifiedPort > 0) ":${u.specifiedPort}" else ""
        "${u.protocol.name}://${u.host}$port/player_api.php?username=$user&password=$pass"
    }.getOrNull()

    // ---------- live ----------

    /** get_live_categories: category id → name. Empty when the panel has none (or refuses). */
    suspend fun liveCategories(pl: PlaylistSource): Map<String, String> = categories(pl, "get_live_categories")

    /** get_live_streams, both as a list and as the TV app's keyed-object form. */
    suspend fun liveStreams(pl: PlaylistSource): List<XtreamLiveStream> {
        val text = getText("${pl.xtreamApiUrl()}&action=get_live_streams", pl.userAgent)
        return flatObjects(text).mapNotNull { f ->
            val streamId = f["stream_id"]
            if (streamId.isNullOrBlank()) return@mapNotNull null
            XtreamLiveStream(
                streamId = streamId,
                name = (f["name"] ?: "Channel $streamId").replace("\n", " ").trim(),
                epgChannelId = f["epg_channel_id"],
                icon = f["stream_icon"],
                categoryId = f["category_id"],
                num = f["num"],
                tvArchive = f["tv_archive"] == "1",
                tvArchiveDays = f["tv_archive_duration"]?.takeIf { it != "0" }
            )
        }
    }

    /**
     * Loads an Xtream login through the API the way the TV app does (account check, categories,
     * then live streams) and returns the channels as an M3U playlist, line for line what the TV
     * writes to disk, so [M3uParser] and everything after it treat a login like any playlist.
     * Throws [XtreamLoginError] when the login itself is the problem; anything else is a network
     * or panel failure the caller may fall back from (the TV then tries get.php).
     */
    suspend fun livePlaylistM3u(pl: PlaylistSource, onProgress: ((Int) -> Unit)? = null): LivePlaylist {
        val info = accountInfo(pl)
        info.loginError()?.let { throw XtreamLoginError(it) }
        val categories = runCatching { liveCategories(pl) }.getOrDefault(emptyMap())
        val streams = liveStreams(pl)
        val ext = streamExtension(pl, info.allowedFormats)
        val base = pl.xtreamBase()
        val user = pl.xtreamUsername.trim().encodeURLParameter()
        val pass = pl.xtreamPassword.trim().encodeURLParameter()
        val out = StringBuilder(streams.size * 160 + 16)
        out.append("#EXTM3U\n")
        var count = 0
        streams.forEach { s ->
            val attrs = buildString {
                fun attr(k: String, v: String?) {
                    if (!v.isNullOrBlank()) append(" $k=\"${v.replace("\"", "'")}\"")
                }
                attr("tvg-id", s.epgChannelId)
                attr("tvg-name", s.name)
                attr("tvg-logo", s.icon)
                attr("group-title", categories[s.categoryId] ?: "Uncategorized")
                attr("tvg-chno", s.num)
                if (s.tvArchive) {
                    attr("catchup", "xc")
                    attr("catchup-days", s.tvArchiveDays ?: "1")
                }
            }
            out.append("#EXTINF:-1").append(attrs).append(',').append(s.name).append('\n')
            out.append(base).append("/live/").append(user).append('/').append(pass).append('/').append(s.streamId).append('.').append(ext).append('\n')
            count++
            if (count % 500 == 0) onProgress?.invoke(count)
        }
        if (count == 0) error("The provider returned no live channels")
        return LivePlaylist(out.toString(), count, info)
    }

    /** The result of [livePlaylistM3u]. */
    class LivePlaylist(val m3u: String, val channelCount: Int, val account: XtreamAccountInfo)

    /** The stream file extension for this login: its own choice, or what the account allows. */
    fun streamExtension(pl: PlaylistSource, allowedFormats: Set<String>): String = when (pl.streamFormat) {
        "ts" -> "ts"
        "m3u8" -> "m3u8"
        // Auto: .ts unless the provider only allows HLS.
        else -> if (allowedFormats.isNotEmpty() && "ts" !in allowedFormats && "m3u8" in allowedFormats) "m3u8" else "ts"
    }

    // ---------- On Demand ----------

    /** get_vod_categories / get_series_categories, in the provider's order (blank names become "Other"). */
    suspend fun vodCategories(pl: PlaylistSource, series: Boolean): List<Pair<String, String>> {
        val action = if (series) "get_series_categories" else "get_vod_categories"
        return runCatching {
            jsonArrayOf(getText("${pl.xtreamApiUrl()}&action=$action", pl.userAgent)).mapNotNull { e ->
                val c = e as? JsonObject ?: return@mapNotNull null
                c.optString("category_id") to c.optString("category_name").ifBlank { "Other" }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * get_vod_streams (movies) or get_series, the whole list or one category ([categoryId]).
     * The same fields the TV app keeps for On Demand.
     */
    suspend fun vodItems(pl: PlaylistSource, series: Boolean, categoryId: String? = null): List<XtreamVodItem> {
        val action = if (series) "get_series" else "get_vod_streams"
        var url = "${pl.xtreamApiUrl()}&action=$action"
        if (categoryId != null) url += "&category_id=" + categoryId.encodeURLParameter()
        val text = getText(url, pl.userAgent)
        return flatObjects(text).mapNotNull { f ->
            val id = (if (series) f["series_id"] else f["stream_id"]) ?: return@mapNotNull null
            val name = f["name"]?.trim().orEmpty().ifBlank { f["title"].orEmpty() }
            if (name.isBlank()) return@mapNotNull null
            XtreamVodItem(
                isSeries = series,
                id = id,
                name = name,
                icon = (if (series) f["cover"] else f["stream_icon"])?.takeIf { it.startsWith("http") },
                categoryId = f["category_id"].orEmpty(),
                containerExtension = f["container_extension"]?.takeIf { it.isNotBlank() },
                tmdbId = (f["tmdb"] ?: f["tmdb_id"])?.takeIf { it.isNotBlank() && it != "0" },
                year = listOf(f["year"], f["releaseDate"], f["release_date"]).firstNotNullOfOrNull { yearOf(it) }?.toString(),
                rating = f["rating"]?.takeIf { it.isNotBlank() && it != "0" },
                addedSec = (f["added"] ?: f["last_modified"])?.toLongOrNull() ?: 0L,
                genre = f["genre"]?.trim()?.takeIf { it.isNotBlank() }
            )
        }
    }

    /** get_vod_info / get_series_info for one title, as the panel sends it. */
    suspend fun vodInfo(pl: PlaylistSource, series: Boolean, id: String): JsonObject? {
        val action = if (series) "get_series_info&series_id=$id" else "get_vod_info&vod_id=$id"
        return parseObjectOrNull(getText("${pl.xtreamApiUrl()}&action=$action", pl.userAgent))
    }

    // ---------- guide ----------

    /** The provider's XMLTV guide (xmltv.php), as text. Null for a playlist that isn't a login. */
    suspend fun epgXml(pl: PlaylistSource): String? {
        val url = pl.xtreamEpgUrl() ?: return null
        return getText(url, pl.userAgent, maxBytes = GUIDE_MAX_BYTES)
    }

    // ---------- plumbing ----------

    private suspend fun categories(pl: PlaylistSource, action: String): Map<String, String> = runCatching {
        val out = HashMap<String, String>()
        jsonArrayOf(getText("${pl.xtreamApiUrl()}&action=$action", pl.userAgent)).forEach { e ->
            val c = e as? JsonObject ?: return@forEach
            out[c.optString("category_id")] = c.optString("category_name")
        }
        out
    }.getOrDefault(emptyMap())

    /**
     * A panel's list as flat string maps (nested values skipped), whether it sends a list or,
     * as some do, the items keyed by number ({"0": {...}, "1": {...}}). Anything that isn't JSON
     * (an HTML error page, an empty body) is an error, like the TV app's reader.
     */
    internal fun flatObjects(text: String): List<Map<String, String>> {
        val root = liveTvJson.parseToJsonElement(text)
        val items: Collection<JsonElement> = when (root) {
            is JsonArray -> root
            is JsonObject -> root.values
            else -> emptyList()
        }
        return items.mapNotNull { (it as? JsonObject)?.let(::flat) }
    }

    private fun flat(o: JsonObject): Map<String, String> {
        val f = HashMap<String, String>(o.size)
        o.forEach { (k, v) ->
            if (v is JsonPrimitive && v !is JsonNull) f[k] = v.content
        }
        return f
    }

    private fun jsonArrayOf(text: String): JsonArray = (liveTvJson.parseToJsonElement(text) as? JsonArray) ?: JsonArray(emptyList())

    private fun parseObjectOrNull(text: String): JsonObject? =
        runCatching { liveTvJson.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private fun yearOf(raw: String?): Int? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return null
        return Regex("""(19|20)\d{2}""").find(t)?.value?.toIntOrNull()
    }

    /** GETs [url] with the playlist's user agent (or the default one) and returns the body. */
    suspend fun getText(url: String, userAgent: String, maxBytes: Int = API_MAX_BYTES): String =
        client.prepareGet(url) {
            header("User-Agent", userAgent.ifBlank { DEFAULT_UA })
            header("Accept", "*/*")
            timeout {
                requestTimeoutMillis = 120_000
                socketTimeoutMillis = 60_000
            }
        }.execute { response ->
            val code = response.status.value
            if (code !in 200..299) error(httpError(code))
            val text = readBoundedResponseBody(response.bodyAsChannel(), response.contentLength(), maxBytes)
            if (text.isEmpty()) error("Empty response")
            text
        }

    companion object {
        const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

        /** Big providers list tens of thousands of titles; their JSON runs well past 16 MB. */
        const val API_MAX_BYTES = 256 * 1024 * 1024
        const val GUIDE_MAX_BYTES = 512 * 1024 * 1024

        /** http://host:port/(live/)?user/pass/id(.ext) */
        internal val xtreamLink = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(\.[a-z0-9]+)?(\?.*)?$""", RegexOption.IGNORE_CASE)

        /** Plain-English reasons for the HTTP errors people actually hit. */
        fun httpError(code: Int): String = when (code) {
            401, 403 -> "HTTP $code: the provider refused the request (wrong login, or it blocks this app; try setting a user agent)"
            404 -> "HTTP 404: nothing at that address (check the server, port and link)"
            429 -> "HTTP 429: too many requests, try again in a few minutes"
            // Not standard codes: Xtream panels use these when an account is over its connection
            // limit, expired, or blocked for the moment.
            456, 458, 461 -> "HTTP $code: the provider refused this login for now (too many connections, expired, or blocked). Close other apps using it and try again later."
            502, 503, 504, 520, 521, 522, 523, 524 -> "HTTP $code: the provider's server didn't respond, try again later"
            884 -> "HTTP 884: the provider blocked this app (try setting a user agent)"
            else -> "HTTP $code"
        }
    }
}
