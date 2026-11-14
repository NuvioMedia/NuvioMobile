package com.nuvio.app.features.addons

import co.touchlab.kermit.Logger
import com.nuvio.app.features.servers.ServerItemRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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

    init {
        scope.launch {
            for (event in events) {
                deliver(event)
            }
        }
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
        videoIds
            .filter(String::isNotBlank)
            .distinct()
            .chunked(LIBRARY_EVENT_VIDEOS_COUNT)
            .forEach { chunk ->
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

    private suspend fun deliver(event: AddonEvent) {
        val manifests = AddonRepository.uiState.value.addons
            .asSequence()
            .filter(ManagedAddon::isActive)
            .mapNotNull(ManagedAddon::manifest)
            .filter { manifest -> manifest.supportsResource(event.resource, event.type, event.id) }
            .distinctBy(AddonManifest::transportUrl)
            .toList()
        if (manifests.isEmpty()) return

        val extraSegment = event.extra.joinToString("&") { (name, value) ->
            "${name.encodeAddonPathSegment()}=${value.encodeAddonPathSegment()}"
        }
        coroutineScope {
            manifests.forEach { manifest ->
                val url = buildAddonResourceUrl(
                    manifestUrl = manifest.transportUrl,
                    resource = event.resource,
                    type = event.type,
                    id = event.id,
                    extraPathSegment = extraSegment,
                )
                launch {
                    try {
                        withTimeout(EVENT_TIMEOUT_MS) {
                            httpGetTextWithHeaders(
                                url = url,
                                headers = mapOf("Cache-Control" to "no-cache"),
                            )
                        }
                    } catch (error: TimeoutCancellationException) {
                        log.w(error) { "${manifest.id} ${event.resource} event timed out" }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        log.w(error) { "${manifest.id} ${event.resource} event failed" }
                    }
                }
            }
        }
    }
}

private data class AddonEvent(
    val resource: String,
    val type: String,
    val id: String,
    val extra: List<Pair<String, String>>,
)
