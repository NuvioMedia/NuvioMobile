package com.nuvio.app.features.player

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.encodeAddonPathSegment
import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.streams.StreamBehaviorHints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_addon_subtitle_display_format
import org.jetbrains.compose.resources.getString

@Serializable
data class SubtitleAddonRequest(
    val url: String,
    val addonId: String,
    val addonName: String,
)

data class SubtitleRequestExtras(
    val filename: String? = null,
    val videoSize: Long? = null,
    val videoHash: String? = null,
) {
    fun toPathSegment(): String? = buildList {
        videoHash?.trim()?.takeIf(String::isNotBlank)?.let { hash ->
            add("videoHash=${hash.encodeAddonPathSegment()}")
        }
        videoSize?.takeIf { it > 0L }?.let { size ->
            add("videoSize=$size")
        }
        filename?.trim()?.takeIf(String::isNotBlank)?.let { name ->
            add("filename=${name.encodeAddonPathSegment()}")
        }
    }.joinToString(separator = "&").ifBlank { null }

    companion object {
        fun from(hints: StreamBehaviorHints): SubtitleRequestExtras? =
            SubtitleRequestExtras(
                filename = hints.filename,
                videoSize = hints.videoSize,
                videoHash = hints.videoHash,
            ).takeIf { it.toPathSegment() != null }
    }
}

internal fun buildSubtitleRequestUrl(
    manifestUrl: String,
    type: String,
    videoId: String,
    extras: SubtitleRequestExtras? = null,
): String = buildAddonResourceUrl(
    manifestUrl = manifestUrl,
    resource = "subtitles",
    type = canonicalSubtitleType(type),
    id = videoId,
    extraPathSegment = extras?.toPathSegment(),
)

internal fun addonSubtitleRequests(
    type: String,
    videoId: String,
    extras: SubtitleRequestExtras? = null,
): List<SubtitleAddonRequest> {
    val requestType = canonicalSubtitleType(type)
    return AddonRepository.uiState.value.addons.enabledAddons().mapNotNull { addon ->
        val manifest = addon.manifest ?: return@mapNotNull null
        if (manifest.resources.none { resource ->
                (resource.name.equals("subtitles", true) || resource.name.equals("subtitle", true)) &&
                    resource.supportsSubtitleType(requestType, videoId)
            }) return@mapNotNull null
        SubtitleAddonRequest(
            url = buildSubtitleRequestUrl(manifest.transportUrl, requestType, videoId, extras),
            addonId = manifest.id,
            addonName = addon.displayTitle,
        )
    }
}

internal suspend fun loadAddonSubtitles(
    requests: List<SubtitleAddonRequest>,
    onLoaded: (SubtitleAddonRequest, List<AddonSubtitle>) -> Unit = { _, _ -> },
): List<AddonSubtitle> = supervisorScope {
    requests.map { request ->
        async {
            val subtitles = try {
                withTimeoutOrNull(10_000L) {
                    parseAddonSubtitles(fetchAddonResponseText(request.url), request)
                }.orEmpty()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                emptyList()
            }
            currentCoroutineContext().ensureActive()
            onLoaded(request, subtitles)
            subtitles
        }
    }.awaitAll().flatten()
}

private suspend fun parseAddonSubtitles(response: String, request: SubtitleAddonRequest): List<AddonSubtitle> {
    val subtitles = Json.parseToJsonElement(response).jsonObject["subtitles"]?.jsonArray.orEmpty()
    return subtitles.mapIndexedNotNull { index, element ->
        val obj = element as? JsonObject ?: return@mapIndexedNotNull null
        val url = obj.stringValue("url") ?: return@mapIndexedNotNull null
        val language = listOf("lang", "language", "languageCode", "locale", "label")
            .firstNotNullOfOrNull(obj::stringValue) ?: "unknown"
        AddonSubtitle(
            id = obj.stringValue("id") ?: "${request.addonId}_$index",
            url = url,
            language = normalizeLanguageCode(language) ?: language,
            display = getString(
                Res.string.player_addon_subtitle_display_format,
                getLanguageLabelForCode(language),
                request.addonName,
            ),
            addonName = request.addonName,
        )
    }
}

private fun canonicalSubtitleType(type: String): String =
    if (type.equals("tv", ignoreCase = true)) "series" else type.lowercase()

private fun AddonResource.supportsSubtitleType(type: String, videoId: String): Boolean {
    val canonical = canonicalSubtitleType(type)
    val typeMatches = types.isEmpty() || types.any { canonicalSubtitleType(it).equals(canonical, ignoreCase = true) }
    return typeMatches && (idPrefixes.isEmpty() || idPrefixes.any { videoId.startsWith(it) })
}

private fun JsonObject.stringValue(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
