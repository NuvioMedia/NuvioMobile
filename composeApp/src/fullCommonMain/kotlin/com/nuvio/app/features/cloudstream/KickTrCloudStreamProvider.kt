package com.nuvio.app.features.cloudstream

import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.streams.runCatchingUnlessCancelled
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal object KickTrCloudStreamProvider : CloudStreamProvider {
    override val id: String =
        "https://raw.githubusercontent.com/Kraptor123/cs-kraptor/master/repo.json#KickTR"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val apiOrigins = listOf("https://kick.com", "https://web.kick.com")
    private val requestHeaders = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "Origin" to "https://kick.com",
        "Referer" to "https://kick.com/",
        "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148",
    )

    override suspend fun getMainPage(page: Int): List<Pair<String, List<CloudStreamSearchItem>>> {
        if (page > 1) return emptyList()
        val categories = listOf(
            "Öne çıkan Türkçe yayınlar" to "/api/v1/livestreams/featured?language=tr&limit=96",
            "En çok izlenen Türkçe yayınlar" to "/api/v1/livestreams?sort=viewer_count_desc&language=tr&limit=96",
            "Türkçe sohbet yayınları" to "/api/v1/livestreams?limit=96&sort=viewer_count_desc&language=tr&category_id=15",
            "Türkçe FPS yayınları" to "/api/v1/livestreams?sort=featured&language=tr&tag=fps&limit=96",
        )
        return categories.mapNotNull { (title, path) ->
            runCatchingUnlessCancelled { title to parseLivestreamItems(fetchApi(path)) }
                .getOrNull()
                ?.takeIf { it.second.isNotEmpty() }
        }
    }

    override suspend fun search(query: String): List<CloudStreamSearchItem> {
        if (query.isBlank()) return emptyList()
        val root = fetchApi("/api/search?searched_word=${query.percentEncode()}")
        val channels = root.findArray("channels") ?: root.asArrayOrNull().orEmpty()
        return channels.mapNotNull(::channelSearchItem).distinctBy(CloudStreamSearchItem::data)
    }

    override suspend fun load(data: String): CloudStreamLoadItem {
        val slug = data.toKickSlug()
        val root = fetchApi("/api/v2/channels/${slug.percentEncode()}").asObjectOrNull()
            ?: error("Kick channel response is invalid")
        val user = root.objectValue("user")
        val livestream = root.objectValue("livestream")
        val displayName = user?.string("username")
            ?: root.string("username")
            ?: root.string("slug")
            ?: slug
        val title = livestream?.string("session_title")?.takeIf { it.isNotBlank() }
        val poster = root.imageUrl("profile_picture", "avatar", "profile_pic")
            ?: user?.imageUrl("profile_picture", "avatar", "profile_pic")
        val background = root.imageUrl("banner_image", "offline_banner_image", "thumbnail")
            ?: livestream?.imageUrl("thumbnail")
        val description = listOfNotNull(
            title,
            user?.string("bio"),
        ).distinct().joinToString("\n").takeIf { it.isNotBlank() }
        return CloudStreamLoadItem(
            providerId = id,
            data = "kick:channel:$slug",
            name = displayName,
            type = CloudStreamTvType.Live,
            posterUrl = poster,
            backgroundUrl = background,
            description = description,
            tags = listOfNotNull(livestream?.objectValue("categories")?.string("name")),
        )
    }

    override suspend fun loadLinks(data: String): List<CloudStreamPlaybackSource> {
        val slug = data.toKickSlug()
        val channelPage = "https://kick.com/$slug"
        val channelResponse = fetchApi("/api/v2/channels/${slug.percentEncode()}")
        val playbackUrl = channelResponse.findFirstString(
            "playback_url",
            "playbackUrl",
            "source",
            "url",
        )?.takeIf(String::isPlayableKickUrl)
            ?: runCatchingUnlessCancelled {
                fetchApi("/api/v1/stream/${slug.percentEncode()}").findFirstString(
                    "playback_url",
                    "playbackUrl",
                    "source",
                    "url",
                )
            }.getOrNull()?.takeIf(String::isPlayableKickUrl)
            ?: error("Kick stream is offline or no playback URL was found")

        return listOf(
            CloudStreamPlaybackSource(
                name = "Kick HLS",
                url = playbackUrl,
                referer = channelPage,
                headers = mapOf(
                    "Referer" to channelPage,
                    "Origin" to "https://kick.com",
                    "User-Agent" to requestHeaders.getValue("User-Agent"),
                ),
                isHls = playbackUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true),
            ),
        )
    }

    private suspend fun fetchApi(path: String): JsonElement {
        var lastError: Throwable? = null
        for (origin in apiOrigins) {
            val url = if (path.startsWith("http")) path else origin + path
            val payload = runCatchingUnlessCancelled { httpGetTextWithHeaders(url, requestHeaders) }
                .onFailure { lastError = it }
                .getOrNull() ?: continue
            val parsed = runCatchingUnlessCancelled { json.parseToJsonElement(payload) }
                .onFailure { lastError = it }
                .getOrNull() ?: continue
            if (parsed.findFirstString("error")?.contains("blocked", ignoreCase = true) == true) {
                lastError = IllegalStateException("Kick request was blocked by the provider's security layer")
                continue
            }
            return parsed
        }
        throw lastError ?: IllegalStateException("Kick API request failed")
    }

    private fun parseLivestreamItems(root: JsonElement): List<CloudStreamSearchItem> {
        val streams = root.findArray("livestreams")
            ?: root.findArray("data")
            ?: root.asArrayOrNull()
            ?: emptyList()
        return streams.mapNotNull(::livestreamSearchItem).distinctBy(CloudStreamSearchItem::data)
    }

    private fun livestreamSearchItem(element: JsonElement): CloudStreamSearchItem? {
        val stream = element.asObjectOrNull() ?: return null
        val channel = stream.objectValue("channel")
        val user = channel?.objectValue("user") ?: stream.objectValue("user")
        val slug = channel?.string("slug")
            ?: stream.string("slug")
            ?: user?.string("username")?.lowercase()
            ?: return null
        val name = user?.string("username")
            ?: channel?.string("username")
            ?: slug
        val title = stream.string("session_title")
        val category = stream.objectValue("categories")?.string("name")
            ?: stream.objectValue("category")?.string("name")
        val viewers = stream.int("viewer_count")
        val description = listOfNotNull(
            title,
            category,
            viewers?.let { "$it izleyici" },
        ).joinToString(" · ").takeIf { it.isNotBlank() }
        return CloudStreamSearchItem(
            providerId = id,
            data = "kick:channel:$slug",
            name = name,
            type = CloudStreamTvType.Live,
            posterUrl = stream.imageUrl("thumbnail")
                ?: channel?.imageUrl("profile_picture", "avatar")
                ?: user?.imageUrl("profile_picture", "avatar"),
            backgroundUrl = stream.imageUrl("thumbnail"),
            description = description,
        )
    }

    private fun channelSearchItem(element: JsonElement): CloudStreamSearchItem? {
        val channel = element.asObjectOrNull() ?: return null
        val user = channel.objectValue("user")
        val slug = channel.string("slug")
            ?: user?.string("username")?.lowercase()
            ?: return null
        val name = user?.string("username") ?: channel.string("username") ?: slug
        val livestream = channel.objectValue("livestream")
        return CloudStreamSearchItem(
            providerId = id,
            data = "kick:channel:$slug",
            name = name,
            type = CloudStreamTvType.Live,
            posterUrl = channel.imageUrl("profile_picture", "avatar")
                ?: user?.imageUrl("profile_picture", "avatar")
                ?: livestream?.imageUrl("thumbnail"),
            backgroundUrl = livestream?.imageUrl("thumbnail"),
            description = livestream?.string("session_title") ?: user?.string("bio"),
        )
    }
}

private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject
private fun JsonElement.asArrayOrNull(): JsonArray? = this as? JsonArray

private fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.imageUrl(vararg keys: String): String? {
    for (key in keys) {
        val value = this[key]
        val direct = (value as? JsonPrimitive)?.contentOrNull
        if (!direct.isNullOrBlank()) return direct.normalizeKickImageUrl()
        val nested = value as? JsonObject
        val nestedUrl = nested?.string("url") ?: nested?.string("src")
        if (!nestedUrl.isNullOrBlank()) return nestedUrl.normalizeKickImageUrl()
    }
    return null
}

private fun JsonElement.findArray(key: String): JsonArray? {
    when (this) {
        is JsonObject -> {
            (this[key] as? JsonArray)?.let { return it }
            values.forEach { value -> value.findArray(key)?.let { return it } }
        }
        is JsonArray -> forEach { value -> value.findArray(key)?.let { return it } }
        else -> Unit
    }
    return null
}

private fun JsonElement.findFirstString(vararg keys: String): String? {
    when (this) {
        is JsonObject -> {
            keys.forEach { key ->
                (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
            }
            values.forEach { value -> value.findFirstString(*keys)?.let { return it } }
        }
        is JsonArray -> forEach { value -> value.findFirstString(*keys)?.let { return it } }
        else -> Unit
    }
    return null
}

private fun String.toKickSlug(): String =
    substringAfterLast(':')
        .substringAfter("kick.com/")
        .substringBefore('/')
        .substringBefore('?')
        .trim()
        .lowercase()
        .also { require(it.isNotBlank()) { "Kick channel slug is missing" } }

private fun String.normalizeKickImageUrl(): String =
    replace("{width}", "640").replace("{height}", "360")

private fun String.isPlayableKickUrl(): Boolean {
    val normalized = trim()
    return normalized.startsWith("https://") && (
        normalized.substringBefore('?').endsWith(".m3u8", ignoreCase = true) ||
            normalized.substringBefore('?').endsWith(".mp4", ignoreCase = true) ||
            normalized.contains("playlist", ignoreCase = true)
        )
}

private fun String.percentEncode(): String = buildString {
    this@percentEncode.encodeToByteArray().forEach { byte ->
        val value = byte.toInt() and 0xff
        val character = value.toChar()
        if (character.isLetterOrDigit() || character in "-._~") {
            append(character)
        } else {
            append('%')
            append(value.toString(16).uppercase().padStart(2, '0'))
        }
    }
}

