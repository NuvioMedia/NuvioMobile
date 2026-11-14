package com.nuvio.app.features.addons

import co.touchlab.kermit.Logger
import com.nuvio.app.features.servers.ServerItemRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class AddonPlayerAction(val wireValue: String) {
    START("start"),
    PAUSE("pause"),
    STOP("stop"),
}

enum class AddonLibraryAction(val wireValue: String) {
    LIBRARY_ADD("libraryAdd"),
    LIBRARY_REMOVE("libraryRemove"),
    WATCHED("watched"),
    UNWATCHED("unwatched"),
}

data class AddonPlayerEventTarget(
    val type: String,
    val videoId: String,
)

private const val LIBRARY_EVENT_VIDEOS_COUNT = 100
private const val EVENT_TIMEOUT_MS = 10_000L

object AddonEventDispatcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("AddonEvents")
    private val events = Channel<AddonEvent>(Channel.UNLIMITED)
    private val addonQueues = mutableMapOf<String, Channel<AddonDelivery>>()

    init {
        scope.launch {
            for (event in events) {
                route(event)
            }
        }
    }

    fun hasEnabledAddonFor(resource: String): Boolean =
        AddonRepository.uiState.value.addons.any { addon ->
            addon.isActive && addon.manifest?.resources?.any { it.name == resource } == true
        }

    fun player(
        target: AddonPlayerEventTarget,
        action: AddonPlayerAction,
        currentTimeMs: Long,
        durationMs: Long,
    ) {
        dispatch(
            resource = ADDON_PLAYER_RESOURCE,
            type = target.type,
            id = target.videoId,
            extra = listOf(
                "action" to action.wireValue,
                "currentTime" to currentTimeMs.coerceAtLeast(0L).toString(),
                "duration" to durationMs.coerceAtLeast(0L).toString(),
            ),
        )
    }

    fun library(
        type: String,
        id: String,
        action: AddonLibraryAction,
    ) {
        dispatch(
            resource = ADDON_LIBRARY_RESOURCE,
            type = type,
            id = id,
            extra = listOf("action" to action.wireValue),
        )
    }

    fun libraryVideos(
        type: String,
        id: String,
        action: AddonLibraryAction,
        videoIds: Collection<String>,
    ) {
        libraryVideoIdChunks(videoIds).forEach { chunk ->
            dispatch(
                resource = ADDON_LIBRARY_RESOURCE,
                type = type,
                id = id,
                extra = listOf(
                    "action" to action.wireValue,
                    "videoId" to chunk.joinToString(","),
                ),
            )
        }
    }

    private fun dispatch(
        resource: String,
        type: String,
        id: String,
        extra: List<Pair<String, String>>,
    ) {
        if (type.isBlank() || id.isBlank() || ServerItemRef.isServerId(id)) return
        events.trySend(AddonEvent(resource, type, id, extra))
    }

    private fun route(event: AddonEvent) {
        AddonRepository.uiState.value.addons
            .asSequence()
            .filter(ManagedAddon::isActive)
            .mapNotNull(ManagedAddon::manifest)
            .filter { manifest -> manifest.supportsResource(event.resource, event.type, event.id) }
            .distinctBy(AddonManifest::transportUrl)
            .forEach { manifest ->
                val url = buildAddonEventUrl(manifest.transportUrl, event.resource, event.type, event.id, event.extra)
                queueFor(manifest.transportUrl).trySend(AddonDelivery(manifest.id, event.resource, url))
            }
    }

    private fun queueFor(transportUrl: String): Channel<AddonDelivery> =
        addonQueues.getOrPut(transportUrl) {
            Channel<AddonDelivery>(Channel.UNLIMITED).also { queue ->
                scope.launch {
                    for (delivery in queue) {
                        send(delivery)
                    }
                }
            }
        }

    private suspend fun send(delivery: AddonDelivery) {
        try {
            withTimeout(EVENT_TIMEOUT_MS) {
                httpGetTextWithHeaders(
                    url = delivery.url,
                    headers = mapOf("Cache-Control" to "no-cache"),
                )
            }
        } catch (error: TimeoutCancellationException) {
            log.w(error) { "${delivery.addonId} ${delivery.resource} event timed out" }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w(error) { "${delivery.addonId} ${delivery.resource} event failed" }
        }
    }
}

internal fun libraryVideoIdChunks(videoIds: Collection<String>): List<List<String>> =
    videoIds
        .filter(String::isNotBlank)
        .distinct()
        .chunked(LIBRARY_EVENT_VIDEOS_COUNT)

internal fun buildAddonEventUrl(
    manifestUrl: String,
    resource: String,
    type: String,
    id: String,
    extra: List<Pair<String, String>>,
): String =
    buildAddonResourceUrl(
        manifestUrl = manifestUrl,
        resource = resource,
        type = type,
        id = id,
        extraPathSegment = extra.joinToString("&") { (name, value) ->
            "${name.encodeAddonPathSegment()}=${value.encodeAddonPathSegment()}"
        },
    )

private data class AddonEvent(
    val resource: String,
    val type: String,
    val id: String,
    val extra: List<Pair<String, String>>,
)

private data class AddonDelivery(
    val addonId: String,
    val resource: String,
    val url: String,
)
