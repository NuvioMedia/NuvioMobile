package com.nuvio.app.features.details

import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamProxyHeaders
import com.nuvio.app.features.streams.normalizeStreamType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

internal object MetaDetailsParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(payload: String): MetaDetails {
        val root = json.parseToJsonElement(payload).asJsonObjectOrNull()
            ?: error("Expected top-level JSON object in response")
        val meta = root.extractMetaObject()
            ?: error("Response did not contain a valid meta object")
        val links = meta.links()
        val videos = meta.videos()

        return MetaDetails(
            id = meta.requiredString("id"),
            type = meta.requiredString("type"),
            name = meta.requiredString("name"),
            imdbId = meta.string("imdb_id"),
            poster = meta.string("poster"),
            background = meta.string("background"),
            logo = meta.string("logo"),
            description = meta.string("description"),
            releaseInfo = meta.string("releaseInfo"),
            lastAirDate = meta.string("lastAirDate"),
            status = meta.string("status"),
            imdbRating = meta.string("imdbRating"),
            ageRating = meta.ageRating(),
            runtime = meta.string("runtime"),
            genres = meta.stringList("genres"),
            director = meta.directors(links),
            writer = meta.writers(links),
            cast = meta.cast(links),
            country = meta.string("country"),
            awards = meta.string("awards"),
            language = meta.string("language"),
            website = meta.string("website"),
            hasScheduledVideos = meta.behaviorHints().boolean("hasScheduledVideos") == true,
            defaultVideoId = meta.behaviorHints().string("defaultVideoId"),
            trailers = meta.trailers(),
            links = links,
            seasonPosters = meta.seasonPosters(videos),
            videos = videos,
        )
    }

    private fun JsonObject.requiredString(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull ?: error("Missing required field '$name'")

    private fun JsonObject.string(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.array(name: String): JsonArray =
        this[name] as? JsonArray ?: JsonArray(emptyList())

    private fun JsonObject.stringList(name: String): List<String> =
        array(name).mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }

    private fun JsonObject.stringListOrCsv(name: String): List<String> {
        val value = this[name] ?: return emptyList()
        return when (value) {
            is JsonArray -> value.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
            is JsonPrimitive -> value.contentOrNull
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotBlank)
                .orEmpty()
            else -> emptyList()
        }
    }

    private fun JsonObject.links(): List<MetaLink> =
        array("links").mapNotNull { element ->
            val link = element as? JsonObject ?: return@mapNotNull null
            val linkName = link.string("name") ?: return@mapNotNull null
            val category = link.string("category") ?: return@mapNotNull null
            val url = link.string("url") ?: return@mapNotNull null
            MetaLink(name = linkName, category = category, url = url)
        }

    private fun JsonObject.int(name: String): Int? =
        this[name]?.jsonPrimitive?.intOrNull

    private fun JsonObject.boolean(name: String): Boolean? =
        this[name]?.jsonPrimitive?.booleanOrNull

    private fun JsonObject.behaviorHints(): JsonObject =
        this["behaviorHints"] as? JsonObject ?: JsonObject(emptyMap())

    private fun JsonObject.extractMetaObject(): JsonObject? {
        val data = this["data"].asJsonObjectOrNull()
        val candidates = listOfNotNull(
            this["meta"].asJsonObjectOrNull(),
            data?.get("meta").asJsonObjectOrNull(),
            data?.takeIf { it.looksLikeMetaObject() },
            this.takeIf { it.looksLikeMetaObject() },
        )
        return candidates.firstOrNull()
    }

    private fun JsonObject.looksLikeMetaObject(): Boolean =
        string("id") != null && string("type") != null && string("name") != null

    private fun JsonObject.ageRating(): String? {
        val appExtras = this["app_extras"] as? JsonObject
        return listOf(
            string("ageRating"),
            appExtras?.string("certificationLocal"),
            appExtras?.string("certification"),
        ).firstNotNullOfOrNull { value ->
            value?.trim()?.takeIf(String::isNotBlank)
        }
    }

    private fun JsonObject.directors(links: List<MetaLink>): List<String> {
        val appExtras = this["app_extras"] as? JsonObject
        val topLevel = stringListOrCsv("director")
        val extraDirectors = appExtras.personNameList("directors")
        val linkDirectors = links.filter { link ->
            link.category.equals("director", ignoreCase = true) ||
                link.category.equals("directors", ignoreCase = true)
        }.map(MetaLink::name)

        return (topLevel + extraDirectors + linkDirectors)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
    }

    private fun JsonObject.cast(links: List<MetaLink>): List<MetaPerson> {
        val appExtras = this["app_extras"] as? JsonObject
        val appExtraCast = appExtras.people("cast")
        val topLevelCast = stringListOrCsv("cast").map { name ->
            MetaPerson(name = name)
        }
        val linkedCast = links.filter { link ->
            link.category.equals("cast", ignoreCase = true) ||
                link.category.equals("actor", ignoreCase = true) ||
                link.category.equals("actors", ignoreCase = true)
        }.map { link ->
            MetaPerson(name = link.name)
        }

        return mergePeople(appExtraCast, topLevelCast, linkedCast)
    }

    private fun JsonObject.writers(links: List<MetaLink>): List<String> {
        val appExtras = this["app_extras"] as? JsonObject
        val topLevel = stringListOrCsv("writer")
        val extraWriters = appExtras.personNameList("writers")
        val linkWriters = links.filter { link ->
            link.category.equals("writer", ignoreCase = true) ||
                link.category.equals("writers", ignoreCase = true) ||
                link.category.equals("screenplay", ignoreCase = true)
        }.map(MetaLink::name)

        return (topLevel + extraWriters + linkWriters)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
    }

    private fun JsonObject?.personNameList(name: String): List<String> =
        people(name).map(MetaPerson::name)

    private fun JsonObject?.people(name: String): List<MetaPerson> {
        if (this == null) return emptyList()
        return when (val value = this[name]) {
            is JsonArray -> value.mapNotNull { element ->
                when (element) {
                    is JsonObject -> {
                        val personName = element.string("name")?.trim()
                            ?.takeIf(String::isNotBlank)
                            ?: return@mapNotNull null
                        MetaPerson(
                            name = personName,
                            role = element.string("character")?.trim()?.takeIf(String::isNotBlank),
                            photo = element.string("photo")?.trim()?.takeIf(String::isNotBlank),
                        )
                    }
                    is JsonPrimitive -> element.contentOrNull
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?.let(::MetaPerson)
                    else -> null
                }
            }
            is JsonPrimitive -> value.contentOrNull
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotBlank)
                ?.map(::MetaPerson)
                .orEmpty()
            else -> emptyList()
        }
    }

    private fun mergePeople(vararg groups: List<MetaPerson>): List<MetaPerson> {
        val merged = linkedMapOf<String, MetaPerson>()
        groups.forEach { group ->
            group.forEach { person ->
                val normalizedName = person.name.trim()
                if (normalizedName.isBlank()) return@forEach
                val key = normalizedName.lowercase()
                val existing = merged[key]
                merged[key] = if (existing == null) {
                    person.copy(name = normalizedName)
                } else {
                    existing.copy(
                        role = existing.role ?: person.role,
                        photo = existing.photo ?: person.photo,
                    )
                }
            }
        }
        return merged.values.toList()
    }

    private fun JsonObject.videos(): List<MetaVideo> =
        array("videos").mapNotNull { element ->
            val video = element as? JsonObject ?: return@mapNotNull null
            val id = video.string("id") ?: return@mapNotNull null
            val title = video.string("title") ?: video.string("name") ?: return@mapNotNull null
            MetaVideo(
                id = id,
                title = title,
                released = video.string("released"),
                available = video.boolean("available") ?: true,
                thumbnail = video.string("thumbnail"),
                seasonPoster = video.string("seasonPoster") ?: video.string("season_poster_path"),
                season = video.int("season"),
                episode = video.int("episode"),
                overview = video.string("overview") ?: video.string("description"),
                runtime = parseRuntimeMinutes((video["runtime"] as? JsonPrimitive)?.contentOrNull),
                rating = video.string("rating")?.trim()?.toDoubleOrNull()?.takeIf { it > 0.0 },
                streams = video.embeddedStreams(),
            )
        }

    private fun JsonObject.seasonPosters(videos: List<MetaVideo>): Map<Int, String> {
        val appExtras = this["app_extras"] as? JsonObject ?: return emptyMap()
        val byNumber = appExtras["seasonPosterByNumber"] as? JsonObject
        if (byNumber != null) {
            return byNumber.entries.mapNotNull { (key, element) ->
                val season = key.trim().toIntOrNull() ?: return@mapNotNull null
                val url = (element as? JsonPrimitive)?.contentOrNull
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                season to url
            }.toMap()
        }
        val posters = appExtras["seasonPosters"] as? JsonArray ?: return emptyMap()
        val seasons = videos
            .mapNotNull(MetaVideo::season)
            .filter { it >= SPECIALS_SEASON_NUMBER }
            .distinct()
            .sorted()
        val positiveSeasons = seasons.filter { it > SPECIALS_SEASON_NUMBER }
        val posterSeasons = when {
            seasons.size == posters.size -> seasons
            positiveSeasons.size == posters.size -> positiveSeasons
            positiveSeasons.isNotEmpty() &&
                posters.size == positiveSeasons.size + 1 &&
                posters.firstOrNull() == JsonNull -> listOf(SPECIALS_SEASON_NUMBER) + positiveSeasons
            else -> List(posters.size) { index -> index + 1 }
        }
        return posters.mapIndexedNotNull { index, element ->
            (element as? JsonPrimitive)?.contentOrNull
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let { posterSeasons[index] to it }
        }.toMap()
    }

    private fun JsonObject.trailers(): List<MetaTrailer> =
        array("trailers").mapNotNull { element ->
            val trailer = element as? JsonObject ?: return@mapNotNull null
            val key = trailer.string("key")
                ?: trailer.string("source")
                ?: trailer.string("ytId")
                ?: trailer.string("ytid")
                ?: return@mapNotNull null

            val normalizedKey = key.trim()
            if (normalizedKey.isEmpty()) return@mapNotNull null

            MetaTrailer(
                id = trailer.string("id")?.takeIf(String::isNotBlank) ?: normalizedKey,
                key = normalizedKey,
                name = trailer.string("name")?.takeIf(String::isNotBlank) ?: runBlocking { getString(Res.string.generic_trailer) },
                site = trailer.string("site")?.takeIf(String::isNotBlank) ?: "YouTube",
                size = trailer.int("size"),
                type = trailer.string("type")?.takeIf(String::isNotBlank) ?: runBlocking { getString(Res.string.generic_trailer) },
                official = trailer.boolean("official") == true,
                publishedAt = trailer.string("published_at") ?: trailer.string("publishedAt"),
                seasonNumber = trailer.int("seasonNumber") ?: trailer.int("season_number"),
                displayName = trailer.string("displayName")?.takeIf(String::isNotBlank),
            )
        }

    private fun JsonObject.embeddedStreams(): List<StreamItem> {
        val arr = this["streams"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val url = obj.string("url")
            val infoHash = obj.string("infoHash")
            val externalUrl = obj.string("externalUrl")
            if (url == null && infoHash == null && externalUrl == null) return@mapNotNull null

            val hintsObj = obj["behaviorHints"] as? JsonObject
            val proxyHeaders = hintsObj
                ?.objectValue("proxyHeaders")
                ?.toProxyHeaders()
            val streamData = obj["streamData"] as? JsonObject
            val addonName = streamData?.string("addon")
                ?: obj.string("name")
                ?: runBlocking { getString(Res.string.source_embedded) }
            StreamItem(
                name = obj.string("name"),
                description = obj.string("description") ?: obj.string("title"),
                url = url,
                infoHash = infoHash,
                fileIdx = obj.int("fileIdx"),
                externalUrl = externalUrl,
                addonName = addonName,
                addonId = "embedded",
                streamType = normalizeStreamType(obj.string("type")),
                behaviorHints = StreamBehaviorHints(
                    bingeGroup = hintsObj?.string("bingeGroup"),
                    notWebReady = (hintsObj?.boolean("notWebReady") ?: false) || proxyHeaders != null,
                    videoSize = hintsObj?.long("videoSize"),
                    filename = hintsObj?.string("filename"),
                    proxyHeaders = proxyHeaders,
                ),
            )
        }
    }

    private fun JsonObject.objectValue(name: String): JsonObject? =
        this[name] as? JsonObject

    private fun JsonObject.stringMap(): Map<String, String> =
        entries.mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { key to it }
        }.toMap()

    private fun JsonObject.toProxyHeaders(): StreamProxyHeaders? {
        val requestHeaders = objectValue("request")?.stringMap().orEmpty().takeIf { it.isNotEmpty() }
        val responseHeaders = objectValue("response")?.stringMap().orEmpty().takeIf { it.isNotEmpty() }
        if (requestHeaders == null && responseHeaders == null) {
            return null
        }
        return StreamProxyHeaders(
            request = requestHeaders,
            response = responseHeaders,
        )
    }

    private fun JsonObject.long(name: String): Long? =
        this[name]?.jsonPrimitive?.longOrNull
}

private fun JsonElement?.asJsonObjectOrNull(): JsonObject? = this as? JsonObject
