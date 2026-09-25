package com.nuvio.app.features.servers

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.streams.StreamSubtitle
import com.nuvio.app.features.tracking.TrackingExternalIds
import kotlinx.serialization.Serializable

@Serializable
enum class ServerMediaKind(val contentType: String) {
    MOVIE("movie"),
    SERIES("series"),
    COLLECTION("collection");

    companion object {
        fun fromContentType(type: String?): ServerMediaKind? = when (type?.trim()?.lowercase()) {
            "movie", "film" -> MOVIE
            "series", "show", "tv" -> SERIES
            else -> null
        }
    }
}

@Serializable
data class ServerLibrary(
    val id: String,
    val name: String,
    val kind: ServerMediaKind,
    val selected: Boolean = true,
)

@Serializable
data class ServerConnection(
    val id: String,
    val providerId: String,
    val name: String,
    val address: String,
    val remoteServerId: String,
    val remoteUserId: String,
    val userName: String,
    val credentialRef: String,
    val libraries: List<ServerLibrary> = emptyList(),
    val enabled: Boolean = true,
    val useCatalogMetadata: Boolean = false,
) {
    val selectedLibraries: List<ServerLibrary>
        get() = libraries.filter { it.selected }

    fun selectedLibraries(kind: ServerMediaKind): List<ServerLibrary> =
        selectedLibraries.filter { it.kind == kind }
}

class ServerSession(
    val connection: ServerConnection,
    val token: String,
) {
    override fun toString(): String = "ServerSession(connection=${connection.id})"
}

data class ServerItemRef(
    val connectionId: String,
    val itemId: String,
) {
    fun encode(): String = "$PREFIX$connectionId:${itemId.escapeSegment()}"

    companion object {
        private const val PREFIX = "srv1:"

        fun isServerId(id: String?): Boolean = id?.startsWith(PREFIX) == true

        fun parse(id: String?): ServerItemRef? {
            if (id == null || !id.startsWith(PREFIX)) return null
            val body = id.removePrefix(PREFIX)
            val separator = body.indexOf(':')
            if (separator <= 0 || separator == body.lastIndex) return null
            val itemId = body.substring(separator + 1).unescapeSegment() ?: return null
            return ServerItemRef(connectionId = body.substring(0, separator), itemId = itemId)
        }
    }
}

private fun String.escapeSegment(): String = replace("%", "%25").replace(":", "%3A")

private fun String.unescapeSegment(): String? {
    if (':' in this) return null
    return replace("%3A", ":", ignoreCase = true).replace("%25", "%")
}

data class ServerPlaybackTarget(
    val item: ServerItemRef,
    val mediaSourceId: String?,
)

data class ServerPage<T>(
    val items: List<T>,
    val totalCount: Int?,
)

data class ServerTitle(
    val preview: MetaPreview,
    val externalIds: TrackingExternalIds = TrackingExternalIds(),
) {
    fun catalogPreview(): MetaPreview {
        if (preview.type == ServerMediaKind.COLLECTION.contentType) return preview
        val id = externalIds.imdb ?: externalIds.tmdb?.let { "tmdb:$it" } ?: return preview
        return preview.copy(id = id)
    }
}

data class ServerItemDetails(
    val meta: MetaDetails,
    val externalIds: TrackingExternalIds,
    val userStates: List<ServerUserState> = emptyList(),
)

data class ServerUserState(
    val videoId: String,
    val positionMs: Long,
    val durationMs: Long,
    val played: Boolean,
    val lastPlayedEpochMs: Long?,
    val season: Int? = null,
    val episode: Int? = null,
    val title: String? = null,
)

data class ServerCandidate(
    val target: ServerPlaybackTarget,
    val title: String,
    val description: String?,
    val filename: String?,
    val sizeBytes: Long?,
)

data class ServerPlaybackRequest(
    val target: ServerPlaybackTarget,
    val capabilities: ServerPlayerCapabilities,
)

data class ServerPlayerCapabilities(
    val directPlayAll: Boolean,
    val allowDirectPlay: Boolean = true,
)

enum class ServerPlayMethod(val wireName: String) {
    DIRECT_PLAY("DirectPlay"),
    DIRECT_STREAM("DirectStream"),
    TRANSCODE("Transcode"),
}

class ServerPlaybackSession(
    val target: ServerPlaybackTarget,
    val mediaSourceId: String,
    val url: String,
    val headers: Map<String, String>,
    val subtitles: List<StreamSubtitle>,
    val playSessionId: String?,
    val playMethod: ServerPlayMethod,
) {
    override fun toString(): String = "ServerPlaybackSession(item=${target.item.itemId}, method=$playMethod)"
}

enum class ServerPlaybackEventType {
    START,
    PROGRESS,
    PAUSE,
    RESUME,
    STOP,
}

data class ServerPlaybackEvent(
    val type: ServerPlaybackEventType,
    val positionMs: Long,
    val isPaused: Boolean,
)

enum class ServerFailure {
    AUTH_REQUIRED,
    FORBIDDEN,
    UNREACHABLE,
    UNSUPPORTED,
    NOT_FOUND,
    INCOMPLETE,
    FAILED,
}

class ServerException(
    val failure: ServerFailure,
    message: String? = null,
) : Exception(message ?: failure.name)
