package com.nuvio.app.features.servers.jellyfin

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.servers.ServerCandidate
import com.nuvio.app.features.servers.ServerCapability
import com.nuvio.app.features.servers.ServerEpisode
import com.nuvio.app.features.servers.ServerException
import com.nuvio.app.features.servers.ServerFailure
import com.nuvio.app.features.servers.ServerIndexEntry
import com.nuvio.app.features.servers.ServerItemDetails
import com.nuvio.app.features.servers.ServerLibrary
import com.nuvio.app.features.servers.ServerMediaKind
import com.nuvio.app.features.servers.ServerPage
import com.nuvio.app.features.servers.ServerPlayMethod
import com.nuvio.app.features.servers.ServerPlaybackEvent
import com.nuvio.app.features.servers.ServerPlaybackEventType
import com.nuvio.app.features.servers.ServerPlaybackRequest
import com.nuvio.app.features.servers.ServerPlaybackSession
import com.nuvio.app.features.servers.ServerPlayerCapabilities
import com.nuvio.app.features.servers.ServerProvider
import com.nuvio.app.features.servers.ServerSession
import com.nuvio.app.features.streams.StreamSubtitle
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import kotlinx.serialization.DeserializationStrategy

internal data class JellyfinSignIn(
    val address: String,
    val serverName: String,
    val serverId: String,
    val userId: String,
    val userName: String,
    val token: String,
)

internal object JellyfinProvider : ServerProvider {
    override val id: String = "jellyfin"
    override val displayName: String = "Jellyfin"
    override val capabilities: Set<ServerCapability> = ServerCapability.entries.toSet()

    private val client = JellyfinClient()

    suspend fun signIn(address: String, username: String, password: String): JellyfinSignIn {
        val normalized = normalizeServerAddress(address) ?: throw ServerException(ServerFailure.NOT_FOUND)
        val (baseUrl, info) = publicInfo(normalized)
        if (!isSupportedVersion(info.version)) throw ServerException(ServerFailure.UNSUPPORTED)
        val response = client.execute(
            method = HttpMethod.Post,
            baseUrl = baseUrl,
            path = "/Users/AuthenticateByName",
            token = null,
            body = client.json.encodeToString(JellyfinAuthRequest.serializer(), JellyfinAuthRequest(username, password)),
        )
        val result = client.json.decodeFromString(JellyfinAuthResult.serializer(), response.body)
        val user = result.user ?: throw ServerException(ServerFailure.AUTH_REQUIRED)
        val token = result.accessToken?.takeIf { it.isNotBlank() } ?: throw ServerException(ServerFailure.AUTH_REQUIRED)
        val serverId = result.serverId ?: info.id ?: throw ServerException(ServerFailure.FAILED)
        return JellyfinSignIn(
            address = baseUrl,
            serverName = info.serverName?.takeIf { it.isNotBlank() } ?: Url(baseUrl).host,
            serverId = serverId,
            userId = user.id,
            userName = user.name ?: username,
            token = token,
        )
    }

    private suspend fun publicInfo(baseUrl: String): Pair<String, JellyfinPublicInfo> {
        val response = client.execute(HttpMethod.Get, baseUrl, "/System/Info/Public", token = null, allowRedirect = true)
        if (response.status in 300..399) {
            val redirected = response.location
                ?.substringBefore("/System/Info/Public")
                ?.let(::normalizeServerAddress)
                ?.takeIf { it != baseUrl }
                ?: throw ServerException(ServerFailure.FAILED)
            val retried = client.execute(HttpMethod.Get, redirected, "/System/Info/Public", token = null)
            return redirected to client.json.decodeFromString(JellyfinPublicInfo.serializer(), retried.body)
        }
        return baseUrl to client.json.decodeFromString(JellyfinPublicInfo.serializer(), response.body)
    }

    override suspend fun signOut(session: ServerSession) {
        client.execute(HttpMethod.Post, session.connection.address, "/Sessions/Logout", session.token)
    }

    override suspend fun libraries(session: ServerSession): List<ServerLibrary> =
        get(session, "/UserViews", JellyfinItemsResult.serializer(), mapOf("userId" to session.userId))
            .items
            .mapNotNull { view ->
                val kind = libraryKind(view.collectionType) ?: return@mapNotNull null
                ServerLibrary(id = view.id, name = view.name.orEmpty(), kind = kind)
            }

    override suspend fun libraryPage(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int,
    ): ServerPage<MetaPreview> {
        val result = get(
            session,
            "/Items",
            JellyfinItemsResult.serializer(),
            itemQuery(session, library) + mapOf(
                "sortBy" to if (library.kind == ServerMediaKind.COLLECTION) "SortName" else "DateCreated,SortName",
                "sortOrder" to if (library.kind == ServerMediaKind.COLLECTION) "Ascending" else "Descending",
                "startIndex" to start.toString(),
                "limit" to limit.toString(),
                "enableTotalRecordCount" to "true",
            ),
        )
        val mapper = mapper(session)
        return ServerPage(result.items.mapNotNull(mapper::preview), result.totalRecordCount)
    }

    override suspend fun collectionPage(
        session: ServerSession,
        collectionId: String,
        start: Int,
        limit: Int,
    ): ServerPage<MetaPreview> {
        val result = get(
            session,
            "/Items",
            JellyfinItemsResult.serializer(),
            mapOf(
                "userId" to session.userId,
                "parentId" to collectionId,
                "fields" to LIST_FIELDS,
                "imageTypeLimit" to "1",
                "enableImageTypes" to "Primary,Backdrop,Logo",
                "startIndex" to start.toString(),
                "limit" to limit.toString(),
                "enableTotalRecordCount" to "true",
            ),
        )
        return ServerPage(result.items.mapNotNull(mapper(session)::preview), result.totalRecordCount)
    }

    override suspend fun search(
        session: ServerSession,
        library: ServerLibrary,
        query: String,
        limit: Int,
    ): List<MetaPreview> {
        val result = get(
            session,
            "/Items",
            JellyfinItemsResult.serializer(),
            itemQuery(session, library) + mapOf(
                "searchTerm" to query,
                "limit" to limit.toString(),
            ),
        )
        return result.items.mapNotNull(mapper(session)::preview)
    }

    override suspend fun resumeItems(session: ServerSession, limit: Int): List<MetaPreview> {
        val result = get(
            session,
            "/UserItems/Resume",
            JellyfinItemsResult.serializer(),
            mapOf(
                "userId" to session.userId,
                "limit" to limit.toString(),
                "mediaTypes" to "Video",
                "fields" to LIST_FIELDS,
                "imageTypeLimit" to "1",
                "enableImageTypes" to "Primary,Backdrop,Logo,Thumb",
            ),
        )
        val allowed = session.connection.selectedLibraries.map { it.kind }.toSet()
        return result.items
            .mapNotNull(mapper(session)::resumePreview)
            .filter { preview -> allowed.any { it.contentType == preview.type } }
            .distinctBy { it.id }
    }

    override suspend fun details(session: ServerSession, itemId: String): ServerItemDetails {
        val item = item(session, itemId, DETAIL_FIELDS)
        val episodes = if (item.mediaKind() == ServerMediaKind.SERIES) {
            get(
                session,
                "/Shows/${pathSegment(itemId)}/Episodes",
                JellyfinItemsResult.serializer(),
                mapOf(
                    "userId" to session.userId,
                    "fields" to "Overview,ProviderIds",
                    "enableUserData" to "true",
                ),
            ).items
        } else {
            emptyList()
        }
        val mapper = mapper(session)
        return ServerItemDetails(
            meta = mapper.details(item, episodes),
            externalIds = item.externalIds(),
            userStates = (if (episodes.isEmpty()) listOf(item) else episodes).mapNotNull(mapper::userState),
        )
    }

    override suspend fun candidates(session: ServerSession, itemId: String): List<ServerCandidate> =
        mapper(session).candidates(item(session, itemId, "MediaSources"))

    override suspend fun setPlayed(session: ServerSession, itemId: String, played: Boolean) {
        client.execute(
            method = if (played) HttpMethod.Post else HttpMethod.Delete,
            baseUrl = session.connection.address,
            path = "/UserPlayedItems/${pathSegment(itemId)}",
            token = session.token,
            query = mapOf("userId" to session.userId),
        )
    }

    override suspend fun externalIdIndex(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int,
    ): ServerPage<ServerIndexEntry> {
        val result = get(
            session,
            "/Items",
            JellyfinItemsResult.serializer(),
            mapOf(
                "userId" to session.userId,
                "parentId" to library.id,
                "recursive" to "true",
                "includeItemTypes" to library.itemType(),
                "fields" to "ProviderIds",
                "enableImages" to "false",
                "enableUserData" to "false",
                "startIndex" to start.toString(),
                "limit" to limit.toString(),
                "enableTotalRecordCount" to "true",
            ),
        )
        return ServerPage(
            items = result.items.map { ServerIndexEntry(itemId = it.id, ids = it.externalIds()) },
            totalCount = result.totalRecordCount,
        )
    }

    override suspend fun findEpisode(
        session: ServerSession,
        seriesItemId: String,
        season: Int,
        episode: Int,
    ): ServerEpisode? {
        val matches = get(
            session,
            "/Shows/${pathSegment(seriesItemId)}/Episodes",
            JellyfinItemsResult.serializer(),
            mapOf(
                "userId" to session.userId,
                "season" to season.toString(),
                "fields" to "PremiereDate",
                "enableImages" to "false",
                "enableUserData" to "false",
            ),
        ).items.filter { item ->
            item.parentIndexNumber == season &&
                item.indexNumber == episode &&
                (item.indexNumberEnd == null || item.indexNumberEnd == episode) &&
                !item.isMissing
        }
        return matches.singleOrNull()?.let { ServerEpisode(itemId = it.id, premiereDate = it.premiereDate) }
    }

    override suspend fun preparePlayback(
        session: ServerSession,
        request: ServerPlaybackRequest,
    ): ServerPlaybackSession {
        val itemId = request.target.item.itemId
        val body = JellyfinPlaybackInfoRequest(
            userId = session.userId,
            mediaSourceId = request.target.mediaSourceId,
            maxStreamingBitrate = MAX_STREAMING_BITRATE,
            enableDirectPlay = request.capabilities.allowDirectPlay,
            deviceProfile = deviceProfile(request.capabilities),
        )
        val response = client.execute(
            method = HttpMethod.Post,
            baseUrl = session.connection.address,
            path = "/Items/${pathSegment(itemId)}/PlaybackInfo",
            token = session.token,
            query = mapOf("userId" to session.userId),
            body = client.json.encodeToString(JellyfinPlaybackInfoRequest.serializer(), body),
        )
        val info = client.json.decodeFromString(JellyfinPlaybackInfoResult.serializer(), response.body)
        return playbackSession(session, request, info, client.deviceId)
    }

    internal fun playbackSession(
        session: ServerSession,
        request: ServerPlaybackRequest,
        info: JellyfinPlaybackInfoResult,
        deviceId: String,
    ): ServerPlaybackSession {
        val itemId = request.target.item.itemId
        when (info.errorCode) {
            null -> Unit
            "NotAllowed" -> throw ServerException(ServerFailure.FORBIDDEN)
            else -> throw ServerException(ServerFailure.UNSUPPORTED, info.errorCode)
        }
        val source = info.mediaSources.firstOrNull { it.id == request.target.mediaSourceId }
            ?: info.mediaSources.firstOrNull()
            ?: throw ServerException(ServerFailure.NOT_FOUND)
        val base = session.connection.address
        val (url, method) = when {
            source.supportsDirectPlay && request.capabilities.allowDirectPlay -> buildUrl(
                base,
                "/Videos/${pathSegment(itemId)}/stream",
                mapOf(
                    "static" to "true",
                    "mediaSourceId" to source.id,
                    "playSessionId" to info.playSessionId,
                    "deviceId" to deviceId,
                    "tag" to source.eTag,
                    API_KEY to session.token,
                ),
            ) to ServerPlayMethod.DIRECT_PLAY

            source.transcodingUrl != null -> withApiKey(base + source.transcodingUrl, session.token) to
                if (source.supportsDirectStream) ServerPlayMethod.DIRECT_STREAM else ServerPlayMethod.TRANSCODE

            else -> throw ServerException(ServerFailure.UNSUPPORTED)
        }
        val subtitles = source.mediaStreams
            .filter { it.type.equals("Subtitle", ignoreCase = true) && it.deliveryMethod.equals("External", ignoreCase = true) }
            .mapNotNull { stream ->
                val deliveryUrl = stream.deliveryUrl ?: return@mapNotNull null
                StreamSubtitle(
                    url = withApiKey(base + deliveryUrl, session.token),
                    language = stream.language ?: "und",
                    name = stream.displayTitle,
                )
            }
        return ServerPlaybackSession(
            target = request.target,
            mediaSourceId = source.id,
            url = url,
            headers = emptyMap(),
            subtitles = subtitles,
            playSessionId = info.playSessionId,
            playMethod = method,
        )
    }

    override suspend fun report(
        session: ServerSession,
        playback: ServerPlaybackSession,
        event: ServerPlaybackEvent,
    ) {
        val path = when (event.type) {
            ServerPlaybackEventType.START -> "/Sessions/Playing"
            ServerPlaybackEventType.STOP -> "/Sessions/Playing/Stopped"
            else -> "/Sessions/Playing/Progress"
        }
        val report = JellyfinPlaybackReport(
            itemId = playback.target.item.itemId,
            mediaSourceId = playback.mediaSourceId,
            playSessionId = playback.playSessionId,
            positionTicks = event.positionMs.coerceAtLeast(0L) * TICKS_PER_MS,
            isPaused = event.isPaused,
            playMethod = playback.playMethod.wireName,
            eventName = when (event.type) {
                ServerPlaybackEventType.PAUSE -> "Pause"
                ServerPlaybackEventType.RESUME -> "Unpause"
                ServerPlaybackEventType.PROGRESS -> "TimeUpdate"
                else -> null
            },
        )
        client.execute(
            method = HttpMethod.Post,
            baseUrl = session.connection.address,
            path = path,
            token = session.token,
            body = client.json.encodeToString(JellyfinPlaybackReport.serializer(), report),
        )
    }

    private suspend fun item(session: ServerSession, itemId: String, fields: String): JellyfinItem =
        get(
            session,
            "/Items/${pathSegment(itemId)}",
            JellyfinItem.serializer(),
            mapOf("userId" to session.userId, "fields" to fields),
        )

    private suspend fun <T> get(
        session: ServerSession,
        path: String,
        deserializer: DeserializationStrategy<T>,
        query: Map<String, String?>,
    ): T = client.get(session.connection.address, path, deserializer, session.token, query)

    private fun mapper(session: ServerSession) = JellyfinMapper(session.connection.address, session.connection.id)

    private fun itemQuery(session: ServerSession, library: ServerLibrary): Map<String, String?> = mapOf(
        "userId" to session.userId,
        "parentId" to library.id,
        "recursive" to (library.kind != ServerMediaKind.COLLECTION).toString(),
        "includeItemTypes" to library.itemType().takeUnless { library.kind == ServerMediaKind.COLLECTION },
        "fields" to LIST_FIELDS,
        "imageTypeLimit" to "1",
        "enableImageTypes" to "Primary,Backdrop,Logo",
    )

    private val ServerSession.userId: String
        get() = connection.remoteUserId

    private fun ServerLibrary.itemType(): String = when (kind) {
        ServerMediaKind.MOVIE -> "Movie"
        ServerMediaKind.SERIES -> "Series"
        ServerMediaKind.COLLECTION -> "BoxSet"
    }

    private fun deviceProfile(capabilities: ServerPlayerCapabilities) = JellyfinDeviceProfile(
        name = "Nuvio",
        maxStreamingBitrate = MAX_STREAMING_BITRATE,
        directPlayProfiles = if (capabilities.directPlayAll) {
            listOf(JellyfinDirectPlayProfile())
        } else {
            listOf(
                JellyfinDirectPlayProfile(
                    container = "mp4,m4v,mkv,webm,mov",
                    videoCodec = "h264,hevc,vp8,vp9,av1",
                    audioCodec = "aac,mp3,ac3,eac3,flac,opus,vorbis",
                ),
            )
        },
        transcodingProfiles = listOf(
            JellyfinTranscodingProfile(
                container = "ts",
                videoCodec = "h264",
                audioCodec = "aac,mp3,ac3",
                protocol = "hls",
            ),
        ),
        subtitleProfiles = listOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt").map {
            JellyfinSubtitleProfile(format = it, method = "External")
        } + listOf("pgssub", "dvdsub", "dvbsub").map {
            JellyfinSubtitleProfile(format = it, method = "Embed")
        },
    )

    private fun withApiKey(url: String, token: String): String {
        val query = url.substringAfter('?', "")
        val hasKey = query.split('&').any { it.substringBefore('=').equals(API_KEY, true) || it.substringBefore('=').equals("ApiKey", true) }
        if (hasKey) return url
        return url + (if ('?' in url) "&" else "?") + "$API_KEY=$token"
    }

    internal fun isSupportedVersion(version: String?): Boolean {
        val parts = version?.split('.')?.mapNotNull { it.toIntOrNull() } ?: return false
        val major = parts.getOrNull(0) ?: return false
        val minor = parts.getOrNull(1) ?: 0
        return major > 10 || (major == 10 && minor >= 9)
    }

    private const val API_KEY = "api_key"
    private const val MAX_STREAMING_BITRATE = 120_000_000L
    private const val LIST_FIELDS = "Overview,Genres,ProviderIds,PremiereDate"
    private const val DETAIL_FIELDS = "Overview,Genres,ProviderIds,People,Studios,PremiereDate,EndDate"
}
