package com.nuvio.app.features.servers.silo

import com.nuvio.app.core.network.createApiHttpClient
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
import com.nuvio.app.features.servers.ServerPlaybackTarget
import com.nuvio.app.features.servers.ServerProvider
import com.nuvio.app.features.servers.ServerSession
import com.nuvio.app.features.servers.ServerSignIn
import com.nuvio.app.features.servers.ServerTitle
import com.nuvio.app.features.servers.ServerTitleQuery
import com.nuvio.app.features.servers.ServerTrack
import com.nuvio.app.features.servers.mediabrowser.normalizeServerAddress
import com.nuvio.app.features.servers.mediabrowser.pathSegment
import com.nuvio.app.features.streams.StreamSubtitle
import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random
import kotlin.time.Clock

/** Why a Silo sign-in was refused, when the server said so. */
internal enum class SiloSignInProblem { NOT_SILO, TEMPORARY_PASSWORD, PASSWORD_SIGN_IN_OFF, TOO_MANY_TRIES }

/**
 * Silo through its own API (`/api/v2`). Sign-in is a code approved in a browser or the account's password, followed
 * by a Silo profile; the saved token holds the token pair and the profile.
 */
internal class SiloProvider(
    http: HttpClient = createApiHttpClient(),
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    deviceId: (() -> String)? = null,
) : ServerProvider {
    override val id: String = ID
    override val displayName: String = "Silo"
    override val minimumVersion: String = ""
    override val capabilities: Set<ServerCapability> = setOf(
        ServerCapability.SEARCH,
        ServerCapability.EXTERNAL_ID_LOOKUP,
        ServerCapability.USER_STATE_READ,
        ServerCapability.USER_STATE_WRITE,
        ServerCapability.TRANSCODING,
    )

    private val client = if (deviceId != null) SiloClient(http, deviceId) else SiloClient(http)
    private val json = client.json
    private val lock = SynchronizedObject()
    private val plays = mutableMapOf<String, SiloPlayState>()
    private val installations = mutableMapOf<String, String>()
    private val capabilityCache = mutableMapOf<String, Pair<Long, PlaybackCapabilitiesWire>>()
    private val legacyQuality = mutableSetOf<String>()
    private val traversals = LinkedHashMap<String, Traversal>()

    // Sign-in

    suspend fun findServer(typed: String): SiloServer {
        val address = siloAddress(typed)
        return coroutineScope {
            val serverId = async { serverId(address) }
            val providers = async { get(address, PROVIDERS_PATH, ProvidersWire.serializer()) }
            val branding = async { optional { get(address, BRANDING_PATH, BrandingWire.serializer()) } }
            val codes = async { optional { get(address, DEVICE_CAPABILITY_PATH, DeviceCapabilityWire.serializer()) } }
            val methods = providers.await()
            val capability = codes.await()
            SiloServer(
                address = address,
                serverId = serverId.await(),
                name = branding.await()?.serverName?.takeIf { it.isNotBlank() } ?: displayName,
                passwordSignIn = methods.passwordLogin || methods.items.any { it.mode == "credentials" },
                codeSignIn = capability == null || capability.state == "available",
                cancelsCodes = capability?.cancel ?: true,
            )
        }
    }

    suspend fun startCode(server: SiloServer): SiloCode {
        val startedAt = now()
        val reply = send(
            HttpMethod.Post,
            server.address,
            DEVICE_START_PATH,
            DeviceStartWire.serializer(),
            DeviceStartBody.serializer(),
            DeviceStartBody(client.deviceName.take(DEVICE_NAME_MAX)),
        )
        if (reply.deviceCode.isBlank() || reply.userCode.isBlank()) throw ServerException(ServerFailure.FAILED)
        return SiloCode(
            deviceCode = reply.deviceCode,
            userCode = reply.userCode,
            link = reply.verificationUri,
            linkWithCode = reply.verificationUriComplete.ifBlank { reply.verificationUri },
            expiresAtMs = startedAt + reply.expiresIn.coerceAtLeast(1) * 1_000L,
            serverExpiresAt = reply.expiresAt,
            pollEveryMs = reply.interval.coerceAtLeast(1) * 1_000L,
        )
    }

    suspend fun pollCode(server: SiloServer, code: SiloCode): SiloCodeStatus {
        val reply = try {
            send(
                HttpMethod.Post,
                server.address,
                DEVICE_POLL_PATH,
                DevicePollWire.serializer(),
                DeviceCodeBody.serializer(),
                DeviceCodeBody(code.deviceCode),
            )
        } catch (error: ServerException) {
            if (error.failure == ServerFailure.NOT_FOUND) return SiloCodeStatus.Ended(SiloCodeEnd.EXPIRED)
            throw error
        }
        val pollAfterMs = (reply.pollAfter ?: 0L).times(1_000L).coerceAtLeast(code.pollEveryMs)
        return when (reply.status) {
            "pending" -> SiloCodeStatus.Waiting(reply.opened, movedExpiry(code, reply.expiresAt), pollAfterMs)
            "approved" -> reply.tokens?.let { SiloCodeStatus.Approved(it.toTokens()) } ?: SiloCodeStatus.Ended(SiloCodeEnd.USED)
            "denied" -> SiloCodeStatus.Ended(SiloCodeEnd.DENIED)
            "canceled" -> SiloCodeStatus.Ended(SiloCodeEnd.CANCELED)
            "consumed" -> SiloCodeStatus.Ended(SiloCodeEnd.USED)
            "expired" -> SiloCodeStatus.Ended(SiloCodeEnd.EXPIRED)
            else -> SiloCodeStatus.Waiting(reply.opened, code.expiresAtMs, pollAfterMs)
        }
    }

    /** Silo pushes the end of a code back while someone has it open; the local deadline moves by the same amount. */
    private fun movedExpiry(code: SiloCode, serverExpiresAt: String?): Long {
        val moved = parseInstant(serverExpiresAt) ?: return code.expiresAtMs
        val original = parseInstant(code.serverExpiresAt) ?: return code.expiresAtMs
        return maxOf(code.expiresAtMs, code.expiresAtMs + (moved - original))
    }

    /** An abandoned code can't be approved later. */
    suspend fun cancelCode(server: SiloServer, code: SiloCode) {
        if (!server.cancelsCodes) return
        withContext(NonCancellable) {
            runCatching {
                send<DeviceCodeBody, Unit>(
                    HttpMethod.Post,
                    server.address,
                    DEVICE_CANCEL_PATH,
                    null,
                    DeviceCodeBody.serializer(),
                    DeviceCodeBody(code.deviceCode),
                )
            }
        }
    }

    suspend fun passwordSignIn(server: SiloServer, username: String, password: String): SiloTokens {
        val reply = send(
            HttpMethod.Post,
            server.address,
            LOGIN_PATH,
            TokenPairWire.serializer(),
            LoginBody.serializer(),
            LoginBody(username.trim(), password),
        )
        return reply.toTokens().also { checkUsable(server, it) }
    }

    /** A temporary password has to be changed in Silo's web app before anything else works. */
    suspend fun checkUsable(server: SiloServer, tokens: SiloTokens) {
        if (!tokens.passwordChangeNeeded) return
        abandon(server, tokens)
        throw SiloProblem(403, PASSWORD_CHANGE_REQUIRED).toServerException()
    }

    suspend fun profiles(server: SiloServer, tokens: SiloTokens): List<SiloProfile> =
        get(server.address, PROFILES_PATH, ProfilesWire.serializer(), bearer(tokens.access)).items
            .filter { it.id.isNotBlank() }
            .map { SiloProfile(it.id, it.name.ifBlank { it.id }, it.hasPin, it.isChild, it.isPrimary) }

    /** The profile's token, or null when the PIN is wrong. */
    suspend fun verifyPin(server: SiloServer, tokens: SiloTokens, profile: SiloProfile, pin: String): String? {
        if (pin.isBlank()) return null
        val reply = send(
            HttpMethod.Post,
            server.address,
            "$PROFILES_PATH/${pathSegment(profile.id)}/verify-pin",
            PinWire.serializer(),
            PinBody.serializer(),
            PinBody(pin),
            bearer(tokens.access),
        )
        return reply.profileToken?.takeIf { reply.valid && it.isNotBlank() }
    }

    fun signIn(
        server: SiloServer,
        tokens: SiloTokens,
        profile: SiloProfile,
        profileToken: String?,
        profileCount: Int,
    ): ServerSignIn = ServerSignIn(
        address = server.address,
        serverName = server.name,
        serverId = server.serverId,
        userId = "${tokens.userId}/${profile.id}",
        userName = if (profileCount > 1) "${tokens.userName} · ${profile.name}" else tokens.userName,
        token = json.encodeToString(
            SiloCredential.serializer(),
            SiloCredential(
                access = tokens.access,
                refresh = tokens.refresh,
                issuedAtMs = tokens.issuedAtMs,
                accessExpiresAtMs = tokens.accessExpiresAtMs,
                profileId = profile.id,
                profileToken = profileToken?.takeIf { profile.hasPin },
                signInId = randomId(),
                syncedAtMs = tokens.issuedAtMs,
            ),
        ),
    )

    /** Ends a sign-in that was never saved, so it doesn't stay in the server's list of devices. */
    suspend fun abandon(server: SiloServer, tokens: SiloTokens) = logout(server.address, tokens.access)

    /** Silo's own id for the server; a Silo too old for the identity document is known by its address. */
    private suspend fun serverId(address: String): String = try {
        get(address, IDENTITY_PATH, IdentityWire.serializer()).serverId.takeIf { it.isNotBlank() } ?: throw notSilo()
    } catch (error: ServerException) {
        if (error.failure != ServerFailure.NOT_FOUND) throw error
        address
    }

    /** The address once it answers as Silo's own API, following a move from http to https on the same host. */
    private suspend fun siloAddress(typed: String): String {
        var site = normalizeServerAddress(typed) ?: throw ServerException(ServerFailure.NOT_FOUND)
        repeat(2) {
            val reply = try {
                client.execute(HttpMethod.Get, site, SYSTEM_INFO_PATH, allowRedirect = true)
            } catch (error: ServerException) {
                if (error.failure == ServerFailure.UNREACHABLE || error.siloStatus == null) throw error
                throw notSilo()
            }
            if (reply.status !in 300..399) {
                val info = runCatching { client.decode(SystemInfoWire.serializer(), reply.body) }.getOrNull()
                if (info?.apiMajor == API_MAJOR) return site
                throw notSilo()
            }
            val current = runCatching { Url(site) }.getOrNull()
            site = reply.location
                ?.substringBefore(SYSTEM_INFO_PATH)
                ?.let { normalizeServerAddress(it) }
                ?.takeIf { moved ->
                    val next = runCatching { Url(moved) }.getOrNull()
                    moved != site && next?.host == current?.host &&
                        !(current?.protocol == URLProtocol.HTTPS && next?.protocol == URLProtocol.HTTP)
                }
                ?: throw notSilo()
        }
        throw notSilo()
    }

    private fun notSilo() = SiloProblem(0, NOT_SILO).toServerException()

    // Browsing

    override suspend fun libraries(session: ServerSession): List<ServerLibrary> =
        get(session.address, USER_LIBRARIES_PATH, UserLibrariesWire.serializer(), authHeaders(session)).items
            .sortedBy { it.sortOrder }
            .mapNotNull { library ->
                val kind = libraryKind(library.type) ?: return@mapNotNull null
                ServerLibrary(id = library.id, name = library.name.ifBlank { library.id }, kind = kind)
            }

    override suspend fun libraryPage(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int,
    ): ServerPage<ServerTitle> {
        val page = catalogPage(
            session,
            "library",
            libraryQuery(library, library.kind, sort = "-added_at", "medium"),
            start,
            limit,
        )
        val mapper = mapper(session)
        return ServerPage(page.items.mapNotNull(mapper::title), page.total.takeIf { page.totalExact })
    }

    override suspend fun search(
        session: ServerSession,
        kind: ServerMediaKind,
        libraries: List<ServerLibrary>,
        query: String,
        limit: Int,
    ): List<ServerTitle> {
        val found = coroutineScope {
            libraries.filter { it.holds(kind) }.map { library ->
                async {
                    get(
                        session.address,
                        CATALOG_PATH,
                        CatalogPageWire.serializer(),
                        authHeaders(session),
                        libraryQuery(library, kind, sort = null, "medium") + mapOf(
                            "q" to query.trim(),
                            "limit" to limit.coerceIn(1, SEARCH_MAX).toString(),
                        ),
                    ).items
                }
            }.awaitAll().flatten()
        }
        val mapper = mapper(session)
        return found.mapNotNull(mapper::title).distinctBy { it.preview.id }.take(limit)
    }

    override suspend fun details(session: ServerSession, itemId: String): ServerItemDetails {
        val item = item(session, itemId, "large")
        val episodes = if (siloKind(item.type) == ServerMediaKind.SERIES) episodes(session, itemId) else emptyList()
        val mapper = mapper(session)
        val states = if (episodes.isEmpty()) listOfNotNull(mapper.userState(item)) else episodes.mapNotNull(mapper::userState)
        return ServerItemDetails(meta = mapper.details(item, episodes), externalIds = item.externalIds(), userStates = states)
    }

    override suspend fun setPlayed(session: ServerSession, itemId: String, played: Boolean) {
        send<Unit, Unit>(
            if (played) HttpMethod.Post else HttpMethod.Delete,
            session.address,
            "$WATCHED_PATH/${pathSegment(itemId)}",
            null,
            null,
            null,
            authHeaders(session),
        )
    }

    /** The profile's Continue Watching row from Silo's Home, without the next-up episodes the app works out itself. */
    override suspend fun resumeItems(session: ServerSession, limit: Int): List<ServerTitle> {
        val layout = get(session.address, HOME_LAYOUT_PATH, HomeLayoutWire.serializer(), authHeaders(session))
        val section = layout.sections.firstOrNull { it.sectionType == "continue_watching" } ?: return emptyList()
        val items = get(
            session.address,
            "$HOME_SECTIONS_PATH/${pathSegment(section.id)}/items",
            HomeSectionWire.serializer(),
            authHeaders(session),
            mapOf("image_size" to "medium"),
        ).items
        val libraries = session.connection.selectedLibraries
        val mapper = mapper(session)
        return items
            .filter { it.itemSource != "next_up" && (it.positionSeconds ?: 0.0) > 0.0 }
            .mapNotNull(mapper::resumeTitle)
            .filter { title ->
                val kind = ServerMediaKind.fromContentType(title.preview.type)
                kind != null && libraries.any { it.holds(kind) }
            }
            .distinctBy { it.preview.id }
            .take(limit)
    }

    override suspend fun externalIdIndex(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int,
    ): ServerPage<ServerIndexEntry> {
        val mapper = mapper(session)
        val entries = mutableListOf<ServerIndexEntry>()
        var total: Int? = null
        while (entries.size < limit) {
            val size = minOf(CATALOG_MAX, limit - entries.size)
            val page = catalogPage(
                session,
                "index",
                libraryQuery(library, library.kind, sort = "title", "small"),
                start + entries.size,
                size,
            )
            entries += page.items.map(mapper::indexEntry)
            total = page.total.takeIf { page.totalExact }
            if (page.items.size < size || page.page?.hasMore != true) break
        }
        return ServerPage(entries, total)
    }

    /** Silo names most titles after an external id, so a title is usually one read away; otherwise a title search. */
    override suspend fun lookupTitle(
        session: ServerSession,
        library: ServerLibrary,
        query: ServerTitleQuery,
    ): List<ServerIndexEntry> {
        val kinds = if (library.kind == ServerMediaKind.MIXED) {
            listOf(ServerMediaKind.MOVIE, ServerMediaKind.SERIES)
        } else {
            listOf(library.kind)
        }
        for (contentId in kinds.flatMap { contentIdCandidates(it, query.ids) }) {
            val item = try {
                item(session, contentId, "small")
            } catch (error: ServerException) {
                if (error.failure == ServerFailure.NOT_FOUND) continue
                throw error
            }
            if (library.holds(siloKind(item.type) ?: continue)) {
                return listOf(ServerIndexEntry(item.contentId, item.externalIds(), item.year))
            }
        }
        val name = query.name?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val reply = get(
            session.address,
            CATALOG_PATH,
            CatalogPageWire.serializer(),
            authHeaders(session),
            libraryQuery(library, library.kind, sort = null, "small") + mapOf(
                "q" to (query.year?.let { "$name $it" } ?: name),
                "limit" to TITLE_LOOKUP_LIMIT.toString(),
            ),
        )
        val mapper = mapper(session)
        return reply.items.filter { item -> siloKind(item.type)?.let(library::holds) == true }.map(mapper::indexEntry)
    }

    override suspend fun findEpisode(
        session: ServerSession,
        seriesItemId: String,
        season: Int,
        episode: Int,
    ): ServerEpisode? {
        episodeContentId(seriesItemId, season, episode)?.let { contentId ->
            try {
                val item = item(session, contentId, "small")
                if (item.type.equals("episode", ignoreCase = true) && item.seasonNumber == season && item.episodeNumber == episode) {
                    return ServerEpisode(item.contentId, item.airDate)
                }
            } catch (error: ServerException) {
                if (error.failure != ServerFailure.NOT_FOUND) throw error
            }
        }
        val match = try {
            seasonEpisodes(session, seriesItemId, season).filter { it.episodeNumber == episode }.singleOrNull()
        } catch (error: ServerException) {
            if (error.failure == ServerFailure.NOT_FOUND) return null
            throw error
        }
        return match?.let { ServerEpisode(it.contentId, it.airDate) }
    }

    /** Fresh artwork links for [contentId]; Silo's signed links run out after a few hours. */
    internal suspend fun freshArtwork(session: ServerSession, contentId: String): Map<SiloArtworkKind, String> {
        val item = item(session, contentId, "medium")
        val mapper = mapper(session)
        return buildMap {
            mapper.artwork(item.posterUrl)?.let { put(SiloArtworkKind.POSTER, it) }
            mapper.artwork(item.backdropUrl)?.let { put(SiloArtworkKind.BACKGROUND, it) }
            mapper.artwork(item.logoUrl)?.let { put(SiloArtworkKind.LOGO, it) }
        }
    }

    private suspend fun item(session: ServerSession, contentId: String, imageSize: String): CatalogItemWire =
        get(
            session.address,
            "$CATALOG_ITEMS_PATH/${pathSegment(contentId)}",
            CatalogItemWire.serializer(),
            authHeaders(session),
            mapOf("image_size" to imageSize),
        )

    /** Every episode of a series, a few seasons at a time to stay inside Silo's request limit. */
    private suspend fun episodes(session: ServerSession, seriesId: String): List<EpisodeWire> {
        val seasons = get(
            session.address,
            "$SERIES_PATH/${pathSegment(seriesId)}/seasons",
            SeasonsWire.serializer(),
            authHeaders(session),
            mapOf("include_artwork" to "false"),
        ).items.map { it.seasonNumber }.distinct().sorted()
        return seasons.chunked(SEASONS_AT_ONCE).flatMap { chunk ->
            coroutineScope { chunk.map { number -> async { seasonEpisodes(session, seriesId, number) } }.awaitAll() }.flatten()
        }
    }

    private suspend fun seasonEpisodes(session: ServerSession, seriesId: String, season: Int): List<EpisodeWire> =
        get(
            session.address,
            "$SERIES_PATH/${pathSegment(seriesId)}/seasons/$season/episodes",
            EpisodesWire.serializer(),
            authHeaders(session),
            mapOf("image_size" to "small"),
        ).items.filter { it.contentId.isNotBlank() }

    private fun libraryQuery(
        library: ServerLibrary,
        kind: ServerMediaKind,
        sort: String?,
        imageSize: String,
    ): Map<String, String?> = mapOf(
        "source" to "query",
        "library_id" to library.id,
        "type" to kind.takeIf { it == ServerMediaKind.MOVIE || it == ServerMediaKind.SERIES }?.contentType,
        "sort" to sort,
        "image_size" to imageSize,
    )

    /**
     * One page of a catalog list at an offset. Silo pages with cursors, so the cursor for the next page is kept, and a
     * page reached any other way is found with Silo's seek from the list's window cursor.
     */
    private suspend fun catalogPage(
        session: ServerSession,
        purpose: String,
        query: Map<String, String?>,
        start: Int,
        limit: Int,
    ): CatalogPageWire {
        val key = listOf(session.connection.id, profileId(session), purpose, query.toString(), limit).joinToString("|")
        val pageQuery = query + ("limit" to limit.toString())
        repeat(2) { attempt ->
            val traversal = traversal(key)
            val position: Map<String, String?> = when {
                start == 0 -> emptyMap()
                traversal.cursor(start) != null -> mapOf("cursor" to traversal.cursor(start))
                else -> mapOf(
                    "cursor" to (traversal.window ?: catalogPage(session, purpose, query, 0, limit).let { traversal(key).window }),
                    "seek" to start.toString(),
                )
            }
            val reply = try {
                get(session.address, CATALOG_PATH, CatalogPageWire.serializer(), authHeaders(session), pageQuery + position)
            } catch (error: ServerException) {
                if (error.siloCode != "invalid_cursor" || attempt > 0) throw error
                synchronized(lock) { traversals.remove(key) }
                return@repeat
            }
            reply.windowCursor?.takeIf { it.isNotBlank() }?.let { traversal.window = it }
            val next = reply.page?.nextCursor?.takeIf { reply.page.hasMore && it.isNotBlank() }
            if (next != null) traversal.remember(start + limit, next)
            return reply
        }
        throw ServerException(ServerFailure.FAILED)
    }

    /** The cursors kept for one list, newest lists kept when there are too many. */
    private fun traversal(key: String): Traversal = synchronized(lock) {
        val kept = traversals.remove(key)?.takeIf { now() - it.createdAtMs < TRAVERSAL_TTL_MS } ?: Traversal(now())
        traversals[key] = kept
        while (traversals.size > MAX_TRAVERSALS) traversals.remove(traversals.keys.first())
        kept
    }

    private fun mapper(session: ServerSession) = SiloMapper(session.address, session.connection.id)

    private class Traversal(val createdAtMs: Long) {
        private val lock = SynchronizedObject()
        private val cursors = mutableMapOf<Int, String>()

        @kotlin.concurrent.Volatile
        var window: String? = null

        fun cursor(offset: Int): String? = synchronized(lock) { cursors[offset] }

        fun remember(offset: Int, cursor: String) = synchronized(lock) { cursors[offset] = cursor }
    }

    // Playback

    override suspend fun candidates(session: ServerSession, itemId: String): List<ServerCandidate> =
        mapper(session).candidates(itemId, watchState(session, itemId).versions)

    override suspend fun preparePlayback(session: ServerSession, request: ServerPlaybackRequest): ServerPlaybackSession {
        val credential = credential(session)
        val itemId = request.target.item.itemId
        val installationId = installationId(session)
        val state = watchState(session, itemId)
        val fileId = request.target.mediaSourceId
            ?: state.userData?.lastFileId?.takeIf { last -> state.versions.any { it.fileId == last } }
            ?: state.versions.firstOrNull()?.fileId
            ?: throw ServerException(ServerFailure.NOT_FOUND)
        val attemptId = randomId()
        val support = if (request.capabilities.directPlayAll) SiloDirectSupport.ANY else SiloDirectSupport.EXOPLAYER
        fun startBody(legacy: Boolean) = siloStartBody(
            installationId = installationId,
            fileId = fileId,
            profileId = credential.profileId,
            attemptId = attemptId,
            direct = request.capabilities.allowDirectPlay,
            audioIndex = request.audioStreamIndex,
            subtitleIndex = request.subtitleStreamIndex,
            platform = client.platform,
            appVersion = client.appVersion,
            support = support,
            legacyQuality = legacy,
        )
        val key = cacheKey(session)
        var body = startBody(synchronized(lock) { key in legacyQuality })
        val decision = try {
            sendPlayback(session, HttpMethod.Post, PLAYBACK_START_PATH, body, DecisionWire.serializer())
        } catch (error: ServerException) {
            if (error.siloStatus != 422 && error.siloCode != VALIDATION_FAILED) throw error
            if (synchronized(lock) { legacyQuality.remove(key) }) throw error
            body = startBody(legacy = true)
            sendPlayback(session, HttpMethod.Post, PLAYBACK_START_PATH, body, DecisionWire.serializer())
                .also { synchronized(lock) { legacyQuality += key } }
        }
        val started = decision.plan
        val startedSession = decision.sessionId
        if (decision.outcome != "playable" || started == null || startedSession.isNullOrBlank() || started.stream.url.isBlank()) {
            if (decision.terminal?.reason == INSTALLATION_CHANGED) synchronized(lock) { installations.remove(key) }
            throw ServerException(ServerFailure.UNSUPPORTED, decision.terminal?.reason)
        }
        val (sessionId, plan) = seekablePlan(session, body, startedSession, started)
        val effectiveFile = plan.effectiveMediaFileId?.takeIf { it.isNotBlank() } ?: fileId
        val version = state.versions.firstOrNull { it.fileId == effectiveFile }
        val method = when {
            plan.delivery == "original_http" -> ServerPlayMethod.DIRECT_PLAY
            plan.delivery.startsWith("server_remux") -> ServerPlayMethod.DIRECT_STREAM
            else -> ServerPlayMethod.TRANSCODE
        }
        val headers = authHeaders(session)
        val subtitles = plan.subtitle.inventory
            .filter { it.delivery == "sidecar" && !it.url.isNullOrBlank() }
            .filter { method != ServerPlayMethod.DIRECT_PLAY || it.source != "embedded" }
            .mapNotNull { entry ->
                StreamSubtitle(
                    url = playbackUrl(session, entry.url) ?: return@mapNotNull null,
                    language = entry.language?.takeIf { it.isNotBlank() } ?: "und",
                    name = entry.label?.takeIf { it.isNotBlank() } ?: displayName,
                    headers = headers,
                )
            }
        val selectedAudio = plan.selectedTracks.audio?.index
        val selectedSubtitle = plan.selectedTracks.subtitle?.index
        synchronized(lock) { plays[sessionId] = SiloPlayState(installationId, itemId, plan.timeline.timelineOffsetSeconds) }
        return ServerPlaybackSession(
            target = ServerPlaybackTarget(request.target.item, effectiveFile),
            mediaSourceId = effectiveFile,
            url = playbackUrl(session, plan.stream.url) ?: throw ServerException(ServerFailure.FAILED),
            headers = headers,
            subtitles = subtitles,
            playSessionId = sessionId,
            playMethod = method,
            audioTracks = version?.audioTracks.orEmpty().mapIndexed { index, track ->
                ServerTrack(index, track.label(), track.language, selected = index == selectedAudio)
            },
            burnInSubtitles = plan.subtitle.inventory.filter { it.delivery == "burn_in_only" && !it.unplayable() }.map { entry ->
                ServerTrack(
                    entry.combinedIndex,
                    entry.label ?: entry.language ?: entry.combinedIndex.toString(),
                    entry.language,
                    entry.combinedIndex == selectedSubtitle,
                )
            },
            transcodeReasons = if (method == ServerPlayMethod.DIRECT_PLAY) {
                emptyList()
            } else {
                listOfNotNull(plan.decisionReason) + plan.degradationWarnings.map { it.code }
            },
        )
    }

    /**
     * Swaps a copy remux (a repackaged file that only moves when Silo is asked again for every seek) for another route
     * when Silo has one, normally a conversion the player can seek in. Without one the copy is kept: it starts at 0, so
     * its positions still match the file.
     */
    private suspend fun seekablePlan(
        session: ServerSession,
        start: JsonObject,
        sessionId: String,
        started: PlanWire,
    ): Pair<String, PlanWire> {
        var current = sessionId to started
        val attempted = mutableListOf<String>()
        while (current.second.needsSeekableRoute() && attempted.size < MAX_REPLANS) {
            val plan = current.second
            attempted += plan.planAttemptKey
            val body = siloReplanBody(start, plan, attempted, randomId())
            val next = try {
                sendPlayback(
                    session,
                    HttpMethod.Post,
                    "$PLAYBACK_PATH/${pathSegment(current.first)}/replan",
                    body,
                    DecisionWire.serializer(),
                )
            } catch (_: ServerException) {
                break
            }
            val nextPlan = next.plan?.takeIf { next.outcome == "playable" && it.stream.url.isNotBlank() } ?: break
            current = (next.sessionId?.takeIf { it.isNotBlank() } ?: current.first) to nextPlan
        }
        return current
    }

    private fun PlanWire.needsSeekableRoute(): Boolean =
        !timeline.canSeekAnywhere && delivery.startsWith("server_remux") && (source.durationSeconds ?: 0.0) > 0.0 &&
            planId.isNotBlank() && planAttemptKey.isNotBlank()

    override suspend fun report(session: ServerSession, playback: ServerPlaybackSession, event: ServerPlaybackEvent) {
        val sessionId = playback.playSessionId ?: return
        val state = synchronized(lock) { plays[sessionId] } ?: return
        val position = event.positionMs.coerceAtLeast(0L) / 1_000.0 + state.timelineOffsetSeconds
        val path = "$PLAYBACK_PATH/${pathSegment(sessionId)}"
        try {
            when (event.type) {
                ServerPlaybackEventType.START -> Unit
                ServerPlaybackEventType.STOP -> {
                    try {
                        stopWithRetries(session, path, siloStopBody(state.installationId, randomId(), state.nextSequence(), position))
                    } catch (error: ServerException) {
                        if (error.failure != ServerFailure.AUTH_REQUIRED) synchronized(lock) { plays.remove(sessionId) }
                        throw error
                    }
                    synchronized(lock) { plays.remove(sessionId) }
                }
                else -> sendPlayback<Unit>(
                    session,
                    HttpMethod.Post,
                    "$path/progress",
                    siloProgressBody(state.installationId, state.nextSequence(), position, event.isPaused),
                    null,
                )
            }
        } catch (error: ServerException) {
            if (error.failure != ServerFailure.NOT_FOUND && error.siloStatus != SESSION_ENDED_STATUS) throw error
            synchronized(lock) { plays.remove(sessionId) }
        }
    }

    /** Silo keeps the first stop; the same stop is sent again after a lost connection or a busy server. */
    private suspend fun stopWithRetries(session: ServerSession, path: String, body: JsonObject) {
        repeat(STOP_TRIES) { attempt ->
            try {
                sendPlayback<Unit>(session, HttpMethod.Delete, path, body, null)
                return
            } catch (error: ServerException) {
                if (error.failure != ServerFailure.UNREACHABLE || attempt == STOP_TRIES - 1) throw error
                delay(STOP_RETRY_MS * (attempt + 1))
            }
        }
    }

    private suspend fun watchState(session: ServerSession, itemId: String): WatchStateWire =
        get(
            session.address,
            "$WATCH_PATH/${pathSegment(itemId)}",
            WatchStateWire.serializer(),
            authHeaders(session) + client.deviceHeaders,
        )

    /** The server's playback installation, which every playback call carries; Silo playback switched off or too old says so. */
    private suspend fun installationId(session: ServerSession): String {
        val key = cacheKey(session)
        synchronized(lock) { installations[key] }?.let { return it }
        val cached = synchronized(lock) { capabilityCache[key] }?.takeIf { now() - it.first < CAPABILITIES_TTL_MS }?.second
        val document = cached
            ?: get(session.address, PLAYBACK_CAPABILITIES_PATH, PlaybackCapabilitiesWire.serializer(), authHeaders(session))
                .also { synchronized(lock) { capabilityCache[key] = now() to it } }
        val usable = document.state == "available" && document.allowed &&
            PLAYBACK_PROTOCOL in document.protocolVersions && "sequenced_progress_v1" in document.features
        val id = document.installationId?.takeIf { usable && it.isNotBlank() } ?: throw ServerException(ServerFailure.UNSUPPORTED)
        synchronized(lock) { installations[key] = id }
        return id
    }

    private suspend fun <T> sendPlayback(
        session: ServerSession,
        method: HttpMethod,
        path: String,
        body: JsonObject,
        reply: DeserializationStrategy<T>?,
    ): T {
        val response = try {
            client.execute(method, session.address, path, authHeaders(session) + client.deviceHeaders, body = body.toString())
        } catch (error: ServerException) {
            if (error.siloCode == INSTALLATION_CHANGED) synchronized(lock) { installations.remove(cacheKey(session)) }
            throw error.profileLocked()
        }
        @Suppress("UNCHECKED_CAST")
        if (reply == null) return Unit as T
        return client.decode(reply, response.body)
    }

    /** A link from a playback plan: root-relative to the address Silo is used at, path prefix included. */
    private fun playbackUrl(session: ServerSession, url: String?): String? {
        val value = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true) -> value
            value.startsWith("/") -> session.address.trimEnd('/') + value
            else -> null
        }
    }

    private fun cacheKey(session: ServerSession): String = session.address + "|" + profileId(session)

    private val ServerSession.address: String
        get() = connection.address

    // Tokens

    override suspend fun signOut(session: ServerSession) {
        val credential = credentialOrNull(session.token) ?: return
        logout(session.address, credential.access)
    }

    /** Renewed once half the token's life has passed, so a play that starts now keeps a valid token to its end. */
    override fun renewalDue(session: ServerSession): Boolean {
        val credential = credentialOrNull(session.token) ?: return false
        val halfLife = if (credential.issuedAtMs > 0) {
            (credential.accessExpiresAtMs - credential.issuedAtMs) / 2
        } else {
            RENEW_BEFORE_MS
        }
        return now() >= credential.accessExpiresAtMs - halfLife.coerceAtLeast(RENEW_BEFORE_MS)
    }

    /** A new token pair, unless the server ended the sign-in or wants a PIN profile checked again: renewing can't fix those. */
    override suspend fun renewToken(session: ServerSession, failure: ServerException?): String? {
        if (failure?.siloCode == PROFILE_VERIFICATION) {
            return null
        }
        val credential = credentialOrNull(session.token) ?: return null
        val startedAt = now()
        val reply = try {
            send(
                HttpMethod.Post,
                session.address,
                REFRESH_PATH,
                TokenPairWire.serializer(),
                RefreshBody.serializer(),
                RefreshBody(credential.refresh),
            )
        } catch (error: ServerException) {
            if (error.siloStatus in SIGNED_OUT) throw ServerException(ServerFailure.AUTH_REQUIRED, error.message)
            throw error
        }
        if (reply.accessToken.isBlank()) throw ServerException(ServerFailure.FAILED)
        return json.encodeToString(
            SiloCredential.serializer(),
            credential.copy(
                access = reply.accessToken,
                refresh = reply.refreshToken.ifBlank { credential.refresh },
                issuedAtMs = startedAt,
                accessExpiresAtMs = startedAt + reply.expiresIn * 1_000L,
                syncedAtMs = if (startedAt - credential.syncedAtMs >= ACCOUNT_REFRESH_MS) startedAt else credential.syncedAtMs,
            ),
        )
    }

    /** The account gets the refresh token only; each device gets its own short-lived access token from it. */
    override fun tokenForAccount(token: String): String {
        val credential = credentialOrNull(token) ?: return token
        return json.encodeToString(
            SiloCredential.serializer(),
            credential.copy(access = "", issuedAtMs = 0L, accessExpiresAtMs = 0L),
        )
    }

    /** The same sign-in on this device is at least as fresh as the account's copy, and its token still works. */
    override fun keepsLocalToken(local: String, fromAccount: String): Boolean {
        val localSignIn = credentialOrNull(local)?.signInId.orEmpty()
        return localSignIn.isNotEmpty() && localSignIn == credentialOrNull(fromAccount)?.signInId
    }

    /** Refresh tokens last 30 days and older ones keep working, so the account only needs a fresh copy now and then. */
    override fun renewalChangesAccount(previous: String, renewed: String): Boolean =
        credentialOrNull(previous)?.syncedAtMs != credentialOrNull(renewed)?.syncedAtMs

    private fun authHeaders(session: ServerSession): Map<String, String> {
        val credential = credentialOrNull(session.token) ?: return emptyMap()
        return buildMap {
            put(AUTHORIZATION, "Bearer ${credential.access}")
            put(PROFILE_ID, credential.profileId)
            credential.profileToken?.let { put(PROFILE_TOKEN, it) }
        }
    }

    private fun credential(session: ServerSession): SiloCredential =
        credentialOrNull(session.token) ?: throw ServerException(ServerFailure.AUTH_REQUIRED)

    internal fun credentialOrNull(token: String): SiloCredential? = try {
        json.decodeFromString(SiloCredential.serializer(), token)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun profileId(session: ServerSession): String = credentialOrNull(session.token)?.profileId.orEmpty()

    private suspend fun logout(address: String, access: String) {
        withContext(NonCancellable) {
            runCatching { send<Unit, Unit>(HttpMethod.Post, address, LOGOUT_PATH, null, null, null, bearer(access)) }
        }
    }

    // Requests

    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (_: ServerException) {
        null
    }

    private suspend fun <T> get(
        address: String,
        path: String,
        reply: DeserializationStrategy<T>,
        headers: Map<String, String> = emptyMap(),
        query: Map<String, String?> = emptyMap(),
    ): T = send<Unit, T>(HttpMethod.Get, address, path, reply, null, null, headers, query)

    private suspend fun <B, T> send(
        method: HttpMethod,
        address: String,
        path: String,
        reply: DeserializationStrategy<T>?,
        bodySerializer: SerializationStrategy<B>?,
        body: B?,
        headers: Map<String, String> = emptyMap(),
        query: Map<String, String?> = emptyMap(),
    ): T {
        val response = try {
            client.execute(
                method,
                address,
                path,
                headers,
                query,
                body = if (bodySerializer != null && body != null) json.encodeToString(bodySerializer, body) else null,
            )
        } catch (error: ServerException) {
            throw error.profileLocked()
        }
        @Suppress("UNCHECKED_CAST")
        if (reply == null) return Unit as T
        return client.decode(reply, response.body)
    }

    private fun bearer(access: String): Map<String, String> = mapOf(AUTHORIZATION to "Bearer $access")

    private fun TokenPairWire.toTokens(): SiloTokens {
        if (accessToken.isBlank() || refreshToken.isBlank()) throw ServerException(ServerFailure.FAILED)
        val account = user ?: throw ServerException(ServerFailure.FAILED)
        val issuedAt = now()
        return SiloTokens(
            access = accessToken,
            refresh = refreshToken,
            issuedAtMs = issuedAt,
            accessExpiresAtMs = issuedAt + expiresIn * 1_000L,
            userId = account.id,
            userName = account.username,
            passwordChangeNeeded = account.passwordChangeRequired,
        )
    }

    /** A PIN profile Silo wants checked again (the account's access rules changed): the sign-in has to be renewed. */
    private fun ServerException.profileLocked(): ServerException =
        if (siloCode == PROFILE_VERIFICATION && failure != ServerFailure.AUTH_REQUIRED) {
            ServerException(ServerFailure.AUTH_REQUIRED, message)
        } else {
            this
        }

    private fun randomId(): String = (0 until 32).joinToString("") { Random.nextInt(16).toString(16) }

    internal companion object {
        const val ID = "silo"
        const val API_MAJOR = 2
        const val SYSTEM_INFO_PATH = "/api/v2/system/info"
        const val IDENTITY_PATH = "/api/v2/system/identity"
        const val PROVIDERS_PATH = "/api/v2/auth/providers"
        const val BRANDING_PATH = "/api/v2/theme/branding"
        const val DEVICE_CAPABILITY_PATH = "/api/v2/auth/device/capability"
        const val DEVICE_START_PATH = "/api/v2/auth/device/start"
        const val DEVICE_POLL_PATH = "/api/v2/auth/device/poll"
        const val DEVICE_CANCEL_PATH = "/api/v2/auth/device/cancel"
        const val LOGIN_PATH = "/api/v2/auth/login"
        const val REFRESH_PATH = "/api/v2/auth/refresh"
        const val LOGOUT_PATH = "/api/v2/auth/logout"
        const val PROFILES_PATH = "/api/v2/profiles"
        const val USER_LIBRARIES_PATH = "/api/v2/user/libraries"
        const val CATALOG_PATH = "/api/v2/catalog"
        const val CATALOG_ITEMS_PATH = "/api/v2/catalog/items"
        const val SERIES_PATH = "/api/v2/catalog/series"
        const val WATCHED_PATH = "/api/v2/watched"
        const val HOME_LAYOUT_PATH = "/api/v2/home/layout"
        const val HOME_SECTIONS_PATH = "/api/v2/home/sections"
        const val WATCH_PATH = "/api/v2/watch"
        const val PLAYBACK_PATH = "/api/v2/playback"
        const val PLAYBACK_START_PATH = "/api/v2/playback/start"
        const val PLAYBACK_CAPABILITIES_PATH = "/api/v2/playback/capabilities"
        const val PLAYBACK_PROTOCOL = 3
        const val CAPABILITIES_TTL_MS = 10 * 60_000L
        const val STOP_TRIES = 3
        const val STOP_RETRY_MS = 250L
        const val MAX_REPLANS = 3
        const val CATALOG_MAX = 200
        const val SEARCH_MAX = 100
        const val TITLE_LOOKUP_LIMIT = 10
        const val SEASONS_AT_ONCE = 4
        const val MAX_TRAVERSALS = 32
        const val TRAVERSAL_TTL_MS = 2 * 60 * 60_000L
        const val RENEW_BEFORE_MS = 60_000L
        const val ACCOUNT_REFRESH_MS = 7 * 24 * 60 * 60_000L
        const val DEVICE_NAME_MAX = 120
        const val SESSION_ENDED_STATUS = 410
        const val AUTHORIZATION = "Authorization"
        const val PROFILE_ID = "X-Profile-Id"
        const val PROFILE_TOKEN = "X-Profile-Token"
        const val PROFILE_VERIFICATION = "profile_verification_required"
        const val INSTALLATION_CHANGED = "installation_changed"
        const val VALIDATION_FAILED = "validation_failed"
        const val PASSWORD_CHANGE_REQUIRED = "password_change_required"
        const val NOT_SILO = "not_silo"
        val SIGNED_OUT = setOf(400, 401, 403)

        fun libraryKind(type: String): ServerMediaKind? = when (type.trim().lowercase()) {
            "movies", "movie" -> ServerMediaKind.MOVIE
            "series", "shows", "tv", "tvshows" -> ServerMediaKind.SERIES
            "mixed", "video" -> ServerMediaKind.MIXED
            else -> null
        }

        /** Why a sign-in was refused, for the message shown; null when Silo gave no reason the app knows. */
        fun signInProblem(error: Throwable): SiloSignInProblem? = when ((error as? ServerException)?.siloCode) {
            NOT_SILO -> SiloSignInProblem.NOT_SILO
            PASSWORD_CHANGE_REQUIRED -> SiloSignInProblem.TEMPORARY_PASSWORD
            "local_login_disabled" -> SiloSignInProblem.PASSWORD_SIGN_IN_OFF
            "rate_limited" -> SiloSignInProblem.TOO_MANY_TRIES
            else -> null
        }
    }
}
