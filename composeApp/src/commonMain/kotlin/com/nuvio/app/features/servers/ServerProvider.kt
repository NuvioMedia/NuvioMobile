package com.nuvio.app.features.servers

import com.nuvio.app.features.servers.jellyfin.JellyfinProvider
import com.nuvio.app.features.tracking.TrackingExternalIds

enum class ServerCapability {
    SEARCH,
    EXTERNAL_ID_LOOKUP,
    USER_STATE_READ,
    USER_STATE_WRITE,
    TRANSCODING,
}

interface ServerProvider {
    val id: String
    val displayName: String
    val capabilities: Set<ServerCapability>
    val minimumVersion: String

    suspend fun signIn(address: String, username: String, password: String): ServerSignIn = unsupported()

    suspend fun libraries(session: ServerSession): List<ServerLibrary>

    suspend fun libraryPage(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int,
    ): ServerPage<ServerTitle>

    suspend fun details(session: ServerSession, itemId: String): ServerItemDetails

    suspend fun candidates(session: ServerSession, itemId: String): List<ServerCandidate>

    suspend fun preparePlayback(session: ServerSession, request: ServerPlaybackRequest): ServerPlaybackSession

    suspend fun report(session: ServerSession, playback: ServerPlaybackSession, event: ServerPlaybackEvent) = Unit

    suspend fun signOut(session: ServerSession) = Unit

    suspend fun collectionPage(
        session: ServerSession,
        collectionId: String,
        start: Int,
        limit: Int,
    ): ServerPage<ServerTitle> = unsupported()

    suspend fun search(
        session: ServerSession,
        library: ServerLibrary,
        query: String,
        limit: Int,
    ): List<ServerTitle> = unsupported()

    suspend fun resumeItems(session: ServerSession, limit: Int): List<ServerTitle> = unsupported()

    suspend fun setPlayed(session: ServerSession, itemId: String, played: Boolean): Unit = unsupported()

    suspend fun externalIdIndex(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int,
    ): ServerPage<ServerIndexEntry> = unsupported()

    suspend fun findEpisode(
        session: ServerSession,
        seriesItemId: String,
        season: Int,
        episode: Int,
    ): ServerEpisode? = unsupported()
}

data class ServerIndexEntry(
    val itemId: String,
    val ids: TrackingExternalIds,
)

data class ServerEpisode(
    val itemId: String,
    val premiereDate: String?,
)

fun ServerProvider.supports(capability: ServerCapability): Boolean = capability in capabilities

private fun unsupported(): Nothing = throw ServerException(ServerFailure.UNSUPPORTED)

internal object ServerProviders {
    internal val registered: MutableList<ServerProvider> = mutableListOf(JellyfinProvider())

    fun forId(id: String?): ServerProvider? = registered.firstOrNull { it.id == id }
}
