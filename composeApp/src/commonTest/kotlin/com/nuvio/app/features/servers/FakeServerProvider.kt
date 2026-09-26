package com.nuvio.app.features.servers

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.tracking.TrackingExternalIds

internal class FakeServerProvider(
    private val movieCount: Int = 120,
) : ServerProvider {
    override val id: String = "fake"
    override val displayName: String = "Fake"
    override val minimumVersion: String = "1.0"
    override val capabilities: Set<ServerCapability> = setOf(ServerCapability.SEARCH, ServerCapability.EXTERNAL_ID_LOOKUP)

    val movies = MOVIE_LIBRARY
    val shows = SERIES_LIBRARY
    val indexedIds = mutableMapOf<String, TrackingExternalIds>()
    val episodes = mutableMapOf<Pair<Int, Int>, ServerEpisode>()
    val reported = mutableListOf<ServerPlaybackEventType>()

    override suspend fun libraries(session: ServerSession): List<ServerLibrary> = listOf(movies, shows)

    override suspend fun libraryPage(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int,
    ): ServerPage<ServerTitle> {
        val ids = (start until minOf(start + limit, movieCount)).map { it.toString() }
        return ServerPage(ids.map { title(session, it) }, movieCount)
    }

    override suspend fun collectionPage(
        session: ServerSession,
        collectionId: String,
        start: Int,
        limit: Int,
    ): ServerPage<ServerTitle> = ServerPage(
        listOf(title(session, "7"), title(session, SHOW_ID).let { it.copy(preview = it.preview.copy(type = "series")) }),
        2,
    )

    override suspend fun search(
        session: ServerSession,
        library: ServerLibrary,
        query: String,
        limit: Int,
    ): List<ServerTitle> = listOf(title(session, "7"))

    override suspend fun details(session: ServerSession, itemId: String): ServerItemDetails = ServerItemDetails(
        meta = MetaDetails(
            id = ServerItemRef(session.connection.id, itemId).encode(),
            type = if (itemId == SHOW_ID) "series" else "movie",
            name = "Item $itemId",
            videos = if (itemId == SHOW_ID) {
                listOf(MetaVideo(id = ServerItemRef(session.connection.id, "901").encode(), title = "Pilot", season = 1, episode = 1))
            } else {
                emptyList()
            },
        ),
        externalIds = indexedIds[itemId] ?: TrackingExternalIds(),
    )

    override suspend fun candidates(session: ServerSession, itemId: String): List<ServerCandidate> = listOf(
        ServerCandidate(
            target = ServerPlaybackTarget(ServerItemRef(session.connection.id, itemId), mediaSourceId = "src-$itemId"),
            title = "Original",
            description = null,
            filename = "item-$itemId.mkv",
            sizeBytes = null,
        ),
    )

    override suspend fun preparePlayback(session: ServerSession, request: ServerPlaybackRequest): ServerPlaybackSession {
        if (!request.capabilities.allowDirectPlay) throw ServerException(ServerFailure.UNSUPPORTED)
        return ServerPlaybackSession(
            target = request.target,
            mediaSourceId = request.target.mediaSourceId ?: "default",
            url = "https://fake.example/${request.target.item.itemId}",
            headers = emptyMap(),
            subtitles = emptyList(),
            playSessionId = null,
            playMethod = ServerPlayMethod.DIRECT_PLAY,
        )
    }

    override suspend fun report(session: ServerSession, playback: ServerPlaybackSession, event: ServerPlaybackEvent) {
        reported += event.type
    }

    override suspend fun externalIdIndex(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int,
    ): ServerPage<ServerIndexEntry> {
        val entries = indexedIds.entries
            .filter { (itemId, _) -> (itemId == SHOW_ID) == (library.kind == ServerMediaKind.SERIES) }
            .map { ServerIndexEntry(it.key, it.value) }
        return ServerPage(entries.drop(start).take(limit), entries.size)
    }

    override suspend fun findEpisode(session: ServerSession, seriesItemId: String, season: Int, episode: Int): ServerEpisode? =
        episodes[season to episode]

    private fun title(session: ServerSession, itemId: String) = ServerTitle(
        preview = MetaPreview(
            id = ServerItemRef(session.connection.id, itemId).encode(),
            type = "movie",
            name = "Item $itemId",
        ),
        externalIds = indexedIds[itemId] ?: TrackingExternalIds(),
    )

    companion object {
        const val SHOW_ID = "500"
        val MOVIE_LIBRARY = ServerLibrary(id = "10", name = "Movies", kind = ServerMediaKind.MOVIE)
        val SERIES_LIBRARY = ServerLibrary(id = "20", name = "Shows", kind = ServerMediaKind.SERIES)
        val COLLECTION_LIBRARY = ServerLibrary(id = "30", name = "Featured", kind = ServerMediaKind.COLLECTION)
    }
}

internal fun installFakeServer(
    provider: FakeServerProvider = FakeServerProvider(),
    connectionId: String = "cfake",
    libraries: List<ServerLibrary> = listOf(provider.movies, provider.shows),
): ServerConnection {
    ServerProviders.registered.removeAll { it.id == provider.id }
    ServerProviders.registered += provider
    val connection = ServerConnection(
        id = connectionId,
        providerId = provider.id,
        name = "Box",
        address = "https://fake.example",
        remoteServerId = "server-1",
        remoteUserId = "user-1",
        userName = "viewer",
        credentialRef = "k$connectionId",
        libraries = libraries,
    )
    ServerRepository.store(connection, token = "token")
    return connection
}

internal fun removeFakeServer(connectionId: String = "cfake") {
    ServerRepository.remove(connectionId)
    ServerProviders.registered.removeAll { it.id == "fake" }
}
