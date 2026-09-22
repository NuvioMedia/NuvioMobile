package com.nuvio.app.features.player

import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
internal data class InfusePlaybackSession(
    val id: String,
    val sourceUrl: String,
    val playbackSession: WatchProgressPlaybackSession?,
    val durationMs: Long?,
    val returnedPositionMs: Long? = null,
    val failed: Boolean = false,
) {
    fun playbackResult(): ExternalPlaybackResult? = returnedPositionMs?.let {
        ExternalPlaybackResult(it, durationMs, endedByUser = true, playbackSession = playbackSession, callbackId = id)
    }
}

internal class InfusePlaybackCallbacks(
    private val load: () -> String?,
    private val save: (String?) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val pending = MutableStateFlow<InfusePlaybackSession?>(null)
    val results = pending.asStateFlow()

    private fun storedSession(): InfusePlaybackSession? = load()?.let {
        runCatching { json.decodeFromString<InfusePlaybackSession>(it) }.getOrNull()
    }

    @OptIn(ExperimentalUuidApi::class)
    fun prepare(request: ExternalPlayerPlaybackRequest): String {
        val session = InfusePlaybackSession(
            id = Uuid.random().toString(),
            sourceUrl = request.sourceUrl,
            playbackSession = request.playbackSession,
            durationMs = request.durationMs?.takeIf { it > 0L },
        )
        save(json.encodeToString(session))
        pending.value = null
        return buildInfusePlaybackUrl(request, session.id)
    }

    fun restoreResult() {
        pending.value = storedSession()?.takeIf { it.returnedPositionMs != null || it.failed }
    }

    fun handleUrl(value: String): Boolean {
        val url = runCatching { Url(value) }.getOrNull() ?: return false
        if (url.protocol.name != "nuvio" || url.host != "external-player") return false
        val path = url.pathSegments.filter(String::isNotEmpty)
        if (path.size != 3 || path[0] != "infuse") return false
        val session = storedSession() ?: return true
        if (path[1] != session.id || session.returnedPositionMs != null || session.failed) return true
        val result = when (path[2]) {
            "success" -> {
                if (url.parameters["lastPlayedUrl"] != session.sourceUrl) return true
                val seconds = url.parameters["position"]?.toLongOrNull()
                    ?.takeIf { it in 0..Long.MAX_VALUE / 1000L } ?: return true
                session.copy(returnedPositionMs = seconds * 1000L)
            }
            "error" -> session.copy(failed = true)
            else -> return true
        }
        save(json.encodeToString(result))
        pending.value = result
        return true
    }

    fun consume(id: String) {
        if (storedSession()?.id == id) save(null)
        if (pending.value?.id == id) pending.value = null
    }

    fun cancelLaunch(value: String) {
        val url = runCatching { Url(value) }.getOrNull() ?: return
        val callback = url.parameters["x-success"]?.let { runCatching { Url(it) }.getOrNull() } ?: return
        callback.pathSegments.filter(String::isNotEmpty).getOrNull(1)?.let(::consume)
    }
}

internal val infusePlaybackCallbacks = InfusePlaybackCallbacks(
    load = PlayerSettingsStorage::loadPendingExternalPlayback,
    save = PlayerSettingsStorage::savePendingExternalPlayback,
)

internal fun buildInfusePlaybackUrl(request: ExternalPlayerPlaybackRequest, sessionId: String): String = buildString {
    append("infuse://x-callback-url/play?url=")
    append(request.sourceUrl.encodeURLParameter())
    append("&position=${request.resumePositionMs.coerceAtLeast(0L) / 1000L}")
    append("&filename=")
    append(request.buildPlayerTitle(includeEpisodeTitle = true).encodeURLParameter())
    request.subtitles?.forEach { subtitle ->
        append("&sub=")
        append(subtitle.url.encodeURLParameter())
    }
    append("&x-success=")
    append("nuvio://external-player/infuse/$sessionId/success".encodeURLParameter())
    append("&x-error=")
    append("nuvio://external-player/infuse/$sessionId/error".encodeURLParameter())
}
