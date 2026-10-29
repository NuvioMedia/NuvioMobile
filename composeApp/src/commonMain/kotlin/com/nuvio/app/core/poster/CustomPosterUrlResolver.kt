package com.nuvio.app.core.poster

import com.nuvio.app.features.servers.ServerItemRef
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Resolves custom poster URL patterns by replacing placeholders with actual content IDs.
 *
 * Supported placeholders:
 *
 * **Nuvio native (recommended for new integrations):**
 * - `{id}`        - full Nuvio meta ID (e.g. "tt0137523", "tmdb:1396", "kitsu:7442")
 * - `{id_type}`   - ID namespace: "imdb", "tmdb", "kitsu", "anilist", "mal", "tvdb", "anidb"
 * - `{typed_id}`  - ID formatted for RPDB-style services without namespace prefix: "tt0137523" for IMDb,
 *                   "movie-1396" / "series-1396" for TMDB/TVDB/etc.
 * - `{type}`      - content type: "movie" or "series"
 * - `{shape}`     - poster shape: "poster", "landscape", or "square"
 *
 * **Per-service compatibility:**
 * - `{imdb_id}`    - IMDb ID (e.g. "tt0137523")
 * - `{tmdb_id}`    - TMDB numeric ID (e.g. "1396")
 * - `{tvdb_id}`    - TVDB numeric ID
 * - `{kitsu_id}`   - Kitsu numeric ID
 * - `{anilist_id}` - AniList numeric ID
 * - `{mal_id}`     - MyAnimeList numeric ID
 * - `{anidb_id}`   - AniDB numeric ID
 *
 * Every placeholder also has an **optional** form: `{imdb_id?}`.
 * Required placeholders with no value cause the resolver to return `null` (fallback to original art).
 * Optional placeholders with no value resolve to an empty string.
 *
 * **Pipe syntax (supported ID types):** `{imdb_id|kitsu_id|tvdb_id}` declares which ID types the
 * service supports. The resolver uses the available ID if it matches one of the declared types.
 * If the content's ID type is not in the list, the pattern returns `null` - no request is made,
 * avoiding unnecessary network traffic for unsupported ID types.
 *
 * **RPDB-family auto-detection:** URLs containing "ratingposterdb.com", "aioratings.com",
 * "top-posters.com", or "btttr.cc" automatically try alternative ID types when the primary ID
 * is unavailable (imdb -> tmdb -> tvdb), matching the behaviour of aiometadata.
 */
object CustomPosterUrlResolver {

    /**
     * All IDs extracted from a content item, used by the resolver.
     */
    data class ContentIds(
        val id: String,
        val imdbId: String? = null,
        val tmdbId: String? = null,
        val tvdbId: String? = null,
        val kitsuId: String? = null,
        val anilistId: String? = null,
        val malId: String? = null,
        val anidbId: String? = null
    )

    /**
     * Resolve a custom poster URL pattern.
     *
     * @param pattern URL pattern with `{placeholder}` tokens
     * @param ids content IDs extracted from the meta item
     * @param type content type ("movie" or "series")
     * @param shape poster shape ("poster", "landscape", or "square")
     * @return resolved URL, or `null` if a required placeholder has no value
     */
    fun resolve(
        pattern: String,
        ids: ContentIds,
        type: String,
        shape: String = "poster"
    ): String? {
        if (pattern.isBlank() || ServerItemRef.isServerId(ids.id)) return null
        val prepared = preparedFor(pattern)
        if (prepared.tokens.isEmpty()) return prepared.decoded

        return if (prepared.rpdb) {
            resolveRpdbWithFallback(prepared.decoded, prepared.tokens, ids, type, shape)
        } else {
            fill(prepared.decoded, prepared.tokens, ids, type, shape)
        }
    }

    /**
     * Extract [ContentIds] from a Nuvio meta ID and optional explicit imdbId field.
     *
     * Examples:
     * - `"tt0137523"` -> imdbId = "tt0137523"
     * - `"tmdb:1396"` -> tmdbId = "1396"
     * - `"kitsu:7442"` -> kitsuId = "7442"
     */
    fun extractIds(metaId: String, explicitImdbId: String? = null): ContentIds {
        var imdbId: String? = null
        var tmdbId: String? = null
        var tvdbId: String? = null
        var kitsuId: String? = null
        var anilistId: String? = null
        var malId: String? = null
        var anidbId: String? = null

        when {
            metaId.startsWith("tt") -> imdbId = metaId
            metaId.startsWith("tmdb:") -> tmdbId = metaId.removePrefix("tmdb:")
            metaId.startsWith("tvdb:") -> tvdbId = metaId.removePrefix("tvdb:")
            metaId.startsWith("kitsu:") -> kitsuId = metaId.removePrefix("kitsu:")
            metaId.startsWith("anilist:") -> anilistId = metaId.removePrefix("anilist:")
            metaId.startsWith("mal:") -> malId = metaId.removePrefix("mal:")
            metaId.startsWith("anidb:") -> anidbId = metaId.removePrefix("anidb:")
        }

        if (!explicitImdbId.isNullOrBlank()) {
            imdbId = explicitImdbId
        }

        return ContentIds(
            id = metaId,
            imdbId = imdbId,
            tmdbId = tmdbId,
            tvdbId = tvdbId,
            kitsuId = kitsuId,
            anilistId = anilistId,
            malId = malId,
            anidbId = anidbId
        )
    }

    // -- Internal --

    private class Token(
        val start: Int,
        val end: Int,
        val optional: Boolean,
        val keys: List<String>,
    )

    private class Prepared(
        val decoded: String,
        val tokens: List<Token>,
        val rpdb: Boolean,
    )

    private val cacheLock = SynchronizedObject()
    private val preparedCache = HashMap<String, Prepared>()

    private fun preparedFor(pattern: String): Prepared {
        synchronized(cacheLock) {
            preparedCache[pattern]?.let { return it }
        }
        val decoded = decodePatternPlaceholders(pattern)
        val prepared = Prepared(decoded, scanTokens(decoded), isRpdbFamily(decoded))
        synchronized(cacheLock) {
            if (preparedCache.size > 32) preparedCache.clear()
            preparedCache[pattern] = prepared
        }
        return prepared
    }

    /**
     * One left-to-right pass. Tokens are parsed once per pattern, so list scrolling
     * and recomposition do not recompile regexes or copy the URL for every placeholder.
     */
    private fun fill(
        decoded: String,
        tokens: List<Token>,
        ids: ContentIds,
        type: String,
        shape: String,
    ): String? {
        if (tokens.isEmpty()) return decoded
        val (rawId, rawIdType) = extractRawIdAndType(ids)
        val typedId = formatTypedId(rawId, rawIdType, type)
        val url = StringBuilder(decoded.length + 8)
        var cursor = 0
        for (token in tokens) {
            url.append(decoded, cursor, token.start)
            if (!appendToken(url, decoded, token, ids, rawId, rawIdType, typedId, type, shape)) {
                return null
            }
            cursor = token.end
        }
        url.append(decoded, cursor, decoded.length)
        return url.toString()
    }

    private fun appendToken(
        url: StringBuilder,
        decoded: String,
        token: Token,
        ids: ContentIds,
        rawId: String,
        rawIdType: String,
        typedId: String,
        type: String,
        shape: String,
    ): Boolean {
        if (token.keys.size > 1) {
            for (key in token.keys) {
                val value = lookup(key, ids, rawId, rawIdType, typedId, type, shape) ?: continue
                if (value.isNotEmpty()) {
                    url.append(value)
                    return true
                }
            }
            return false
        }
        val value = lookup(token.keys[0], ids, rawId, rawIdType, typedId, type, shape)
        if (value == null) {
            url.append(decoded, token.start, token.end)
            return true
        }
        if (value.isEmpty() && !token.optional) return false
        url.append(value)
        return true
    }

    /** `null` means the key is not a known placeholder and must be left as written. */
    private fun lookup(
        key: String,
        ids: ContentIds,
        rawId: String,
        rawIdType: String,
        typedId: String,
        type: String,
        shape: String,
    ): String? = when (key) {
        "id" -> ids.id
        "id_type" -> rawIdType
        "typed_id" -> typedId
        "type" -> type
        "shape" -> shape
        "imdb_id" -> ids.imdbId.orEmpty()
        "tmdb_id" -> ids.tmdbId.orEmpty()
        "tvdb_id" -> ids.tvdbId.orEmpty()
        "kitsu_id" -> ids.kitsuId.orEmpty()
        "anilist_id" -> ids.anilistId.orEmpty()
        "mal_id" -> ids.malId.orEmpty()
        "anidb_id" -> ids.anidbId.orEmpty()
        else -> null
    }

    private fun scanTokens(decoded: String): List<Token> {
        if ('{' !in decoded) return emptyList()
        val tokens = ArrayList<Token>(4)
        var index = 0
        while (index < decoded.length) {
            val open = decoded.indexOf('{', index)
            if (open < 0) break
            val close = decoded.indexOf('}', open + 1)
            if (close < 0) break
            val token = parseToken(decoded, open, close)
            if (token != null) {
                tokens += token
                index = close + 1
            } else {
                index = open + 1
            }
        }
        return tokens
    }

    private fun parseToken(decoded: String, open: Int, close: Int): Token? {
        val bodyEnd = close
        if (bodyEnd <= open + 1) return null
        val optional = decoded[bodyEnd - 1] == '?'
        val nameEnd = if (optional) bodyEnd - 1 else bodyEnd
        if (nameEnd <= open + 1) return null
        val keys = ArrayList<String>(1)
        var partStart = open + 1
        for (cursor in partStart..nameEnd) {
            val atEnd = cursor == nameEnd
            val pipe = !atEnd && decoded[cursor] == '|'
            if (!atEnd && !pipe && !isPlaceholderChar(decoded[cursor])) return null
            if (atEnd || pipe) {
                if (cursor == partStart) return null
                keys += decoded.substring(partStart, cursor)
                partStart = cursor + 1
            }
        }
        if (keys.isEmpty()) return null
        return Token(open, close + 1, optional, keys)
    }

    private fun isPlaceholderChar(char: Char): Boolean =
        char in 'a'..'z' || char == '_'

    /**
     * Extracts the raw ID (without namespace prefix) and the ID type string.
     * For `{id}` placeholder we strip the prefix so that Nuvio-native URLs get a clean numeric ID,
     * while `{id}` preserves the full string for backward compatibility.
     */
    private fun extractRawIdAndType(ids: ContentIds): Pair<String, String> {
        val metaId = ids.id
        return when {
            metaId.startsWith("tt") -> metaId to "imdb"
            metaId.contains(":") -> {
                val colonIndex = metaId.indexOf(':')
                val prefix = metaId.substring(0, colonIndex)
                val rawId = metaId.substring(colonIndex + 1)
                rawId to prefix
            }
            else -> metaId to "unknown"
        }
    }

    /**
     * Formats the ID for RPDB-style `{typed_id}` placeholder.
     * IMDb IDs stay as-is ("tt0137523"). TMDB and TVDB get a type prefix ("movie-1396",
     * "series-1396"). Other types pass through the raw ID.
     */
    private fun formatTypedId(rawId: String, idType: String, contentType: String): String {
        if (idType == "imdb") return rawId
        if (idType == "tmdb" || idType == "tvdb") {
            val prefix = if (contentType == "movie") "movie" else "series"
            return "$prefix-$rawId"
        }
        return rawId
    }

    // -- RPDB-family fallback --

    private val RPDB_DOMAINS = listOf(
        "ratingposterdb.com",
        "aioratings.com",
        "top-posters.com",
        "btttr.cc"
    )

    private fun isRpdbFamily(pattern: String): Boolean =
        RPDB_DOMAINS.any { domain -> domain in pattern }

    private fun decodePatternPlaceholders(pattern: String): String {
        if ('%' !in pattern) return pattern
        return pattern
            .replace("%7B", "{", ignoreCase = true)
            .replace("%7D", "}", ignoreCase = true)
            .replace("%7C", "|", ignoreCase = true)
    }

    /**
     * For RPDB-compatible services, try the pattern as-is, then swap the ID type segment
     * if the requested ID is missing (e.g. imdb -> tmdb -> tvdb fallback).
     */
    private fun resolveRpdbWithFallback(
        pattern: String,
        tokens: List<Token>,
        ids: ContentIds,
        type: String,
        shape: String
    ): String? {
        val direct = fill(pattern, tokens, ids, type, shape)
        if (direct != null) return direct

        val typePrefix = if (type == "movie") "movie" else "series"
        val fallbacks = mutableListOf<String>()

        if ("{imdb_id}" in pattern) {
            if (!ids.tmdbId.isNullOrBlank()) {
                fallbacks += pattern
                    .replace("/imdb/", "/tmdb/")
                    .replace("{imdb_id}", "$typePrefix-${ids.tmdbId}")
            }
            if (!ids.tvdbId.isNullOrBlank()) {
                fallbacks += pattern
                    .replace("/imdb/", "/tvdb/")
                    .replace("{imdb_id}", "$typePrefix-${ids.tvdbId}")
            }
        } else if ("{tmdb_id}" in pattern || "{type}-{tmdb_id}" in pattern) {
            if (!ids.imdbId.isNullOrBlank()) {
                fallbacks += pattern
                    .replace("/tmdb/", "/imdb/")
                    .replace("$typePrefix-{tmdb_id}", ids.imdbId)
                    .replace("{tmdb_id}", ids.imdbId)
            }
            if (!ids.tvdbId.isNullOrBlank()) {
                fallbacks += pattern
                    .replace("/tmdb/", "/tvdb/")
                    .replace("{tmdb_id}", ids.tvdbId)
            }
        } else if ("{tvdb_id}" in pattern || "{type}-{tvdb_id}" in pattern) {
            if (!ids.imdbId.isNullOrBlank()) {
                fallbacks += pattern
                    .replace("/tvdb/", "/imdb/")
                    .replace("$typePrefix-{tvdb_id}", ids.imdbId)
                    .replace("{tvdb_id}", ids.imdbId)
            }
            if (!ids.tmdbId.isNullOrBlank()) {
                fallbacks += pattern
                    .replace("/tvdb/", "/tmdb/")
                    .replace("{tvdb_id}", ids.tmdbId)
            }
        }

        for (fb in fallbacks) {
            val resolved = fill(fb, scanTokens(fb), ids, type, shape)
            if (resolved != null) return resolved
        }

        return null
    }
}
