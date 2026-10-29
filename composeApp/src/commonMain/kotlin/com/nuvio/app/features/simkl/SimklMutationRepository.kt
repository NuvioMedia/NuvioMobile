package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingEpisode
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingHistoryItem
import com.nuvio.app.features.tracking.TrackingHistoryWriter
import com.nuvio.app.features.tracking.TrackingListStatus
import com.nuvio.app.features.tracking.TrackingListWriter
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingMutationResult
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import com.nuvio.app.features.tracking.TrackingScrobbler
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.round

internal class SimklMutationService(
    private val client: SimklApiClient,
    private val onMutationCommitted: suspend (SimklMutationReceipt) -> Unit = {},
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    suspend fun moveToList(
        items: Collection<TrackingMediaReference>,
        destination: TrackingListStatus,
    ): TrackingMutationResult {
        val candidates = items.validated()
        if (candidates.isEmpty()) return TrackingMutationResult(attemptedCount = 0)
        val response = client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/sync/add-to-list",
                body = buildSimklListMutationBody(candidates, destination, json),
                retryPolicy = SimklRetryPolicy.SYNC_WRITE,
            ),
        )
        val receipt = response.toListMutationReceipt(candidates, json)
        onMutationCommitted(receipt)
        return receipt.result
    }

    suspend fun removeFromList(items: Collection<TrackingMediaReference>): TrackingMutationResult =
        removeFromHistory(items)

    suspend fun addToHistory(
        items: Collection<TrackingHistoryItem>,
        allowRewatch: Boolean = false,
    ): TrackingMutationResult {
        val candidates = items.toList().also { historyItems ->
            require(historyItems.all { item -> item.media.hasResolvableIdentity }) {
                "Simkl mutation requires a media ID or title for every item"
            }
        }
        if (candidates.isEmpty()) return TrackingMutationResult(attemptedCount = 0)
        // The request asks for rewatch recording when any item says it is a repeat viewing: that is the
        // query flag, and Simkl reads the whole request through its rewatch logic with it. Without it a
        // mark of something already watched is answered as a plain add and recorded as nothing at all.
        val recordRewatch = allowRewatch || candidates.any(TrackingHistoryItem::isRewatch)
        val body = buildSimklHistoryMutationBody(candidates, isRewatch = allowRewatch, json = json)
        val response = client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/sync/history",
                query = if (recordRewatch) SIMKL_ALLOW_REWATCH_QUERY else emptyMap(),
                body = body,
                retryPolicy = SimklRetryPolicy.SYNC_WRITE,
                // A repeat viewing of an episode the history already holds is the same shape of call as
                // a stop scrobble, and Simkl answers both with a conflict that means the session is
                // there. Reading it as a failure is what made a recorded rewatch report an error.
                scrobbleStopConflictIsSuccess = recordRewatch,
            ),
        )
        val receipt = response.toHistoryMutationReceipt(candidates, json)
        onMutationCommitted(receipt)
        return receipt.result
    }

    suspend fun removeFromHistory(items: Collection<TrackingMediaReference>): TrackingMutationResult {
        val candidates = items.validated()
        if (candidates.isEmpty()) return TrackingMutationResult(attemptedCount = 0)
        val response = client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/sync/history/remove",
                body = buildSimklHistoryRemovalBody(candidates, json),
                retryPolicy = SimklRetryPolicy.SYNC_WRITE,
            ),
        )
        val receipt = response.toHistoryRemovalReceipt(candidates, json)
        onMutationCommitted(receipt)
        return receipt.result
    }

    /**
     * Moves a running rewatch session to `closed`, which is how a run is dropped.
     *
     * The transition is documented on Simkl's rewatches guide: the session keeps its watched episodes
     * and can be resumed by another write, so dropping a run never loses history. Nothing is committed
     * locally from the answer: what the account keeps is read back from the sessions by the caller,
     * which is also what Continue Watching follows.
     */
    suspend fun closeRewatchSession(media: TrackingMediaReference, rewatchId: Long): Boolean {
        require(media.hasResolvableIdentity) {
            "Simkl mutation requires a media ID or title for the item"
        }
        val response = client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/sync/history",
                query = SIMKL_ALLOW_REWATCH_QUERY,
                body = buildSimklRewatchCloseBody(media, rewatchId, json),
                retryPolicy = SimklRetryPolicy.SYNC_WRITE,
            ),
        )
        return response.status in 200..299 || response.isSoftSuccess
    }

    suspend fun scrobble(
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent,
        recordRewatch: Boolean = false,
        completionThresholdPercent: Double = SIMKL_REWATCH_MIN_PROGRESS_PERCENT,
    ): SimklScrobbleResult {
        require(event.media.hasResolvableIdentity) { "Simkl scrobble requires a media ID or title" }
        require(event.media.kind == TrackingMediaKind.MOVIE || event.media.episode != null) {
            "Simkl series scrobble requires an episode"
        }
        val response = client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/scrobble/${action.wireValue}",
                query = simklScrobbleQuery(
                    completionThresholdPercent = completionThresholdPercent,
                    recordRewatch = recordRewatch,
                ),
                body = buildSimklScrobbleBody(event, json),
                retryPolicy = SimklRetryPolicy.NEVER,
                scrobbleStopConflictIsSuccess = action == TrackingScrobbleAction.STOP,
            ),
        )
        return response.toSimklScrobbleResult(
            requestedAction = action,
            event = event,
            json = json,
            completionThresholdPercent = completionThresholdPercent,
        )
    }

    private fun Collection<TrackingMediaReference>.validated(): List<TrackingMediaReference> =
        toList().also { candidates ->
            require(candidates.all(TrackingMediaReference::hasResolvableIdentity)) {
                "Simkl mutation requires a media ID or title for every item"
            }
        }
}

object SimklMutationRepository : TrackingListWriter, TrackingHistoryWriter, TrackingScrobbler {
    override val providerId: TrackingProviderId = TrackingProviderId.SIMKL

    /*
     * Simkl keeps a "watching now" state of its own and its pause carries the completion threshold,
     * so the account itself answers whether the playback was paused or finished. A playback that no
     * session owns is welcome here: the pause tells Simkl where the user stopped and the start after
     * the resume tells it the playback continued.
     */
    override val acceptsSessionlessScrobbles = true

    private val remote = SimklApiSyncRemote()

    private val service by lazy {
        SimklMutationService(
            client = SimklApi.client,
            onMutationCommitted = { receipt ->
                SimklSyncRepository.commitMutation(receipt)
                if (receipt.requiresReconciliation) {
                    SimklSyncRepository.refreshAsync(
                        intent = TrackingRefreshIntent.INVALIDATED,
                        origin = SimklRefreshOrigin.MUTATION,
                    )
                }
            },
        )
    }

    init {
        TrackingProviderRegistry.registerListWriter(this)
        TrackingProviderRegistry.registerHistoryWriter(this)
        TrackingProviderRegistry.registerScrobbler(this)
    }

    fun ensureRegistered() = Unit

    override suspend fun moveToList(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
        destination: TrackingListStatus,
    ): TrackingMutationResult {
        if (!isActiveProfile(profileId)) return TrackingMutationResult(attemptedCount = 0)
        return service.moveToList(items, destination)
    }

    override suspend fun removeFromList(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
    ): TrackingMutationResult {
        if (!isActiveProfile(profileId)) return TrackingMutationResult(attemptedCount = 0)
        return service.removeFromList(items)
    }

    /**
     * Writes the manual marks (the episode menu, a season action, a library action) to the account.
     *
     * A mark of something the account has already watched is a repeat viewing, and Simkl records a
     * plain add of it as nothing at all, so such a mark travels as a rewatch: the item carries the
     * flag, the request carries `allow_rewatch=yes`, and a run the account already holds is pinned by
     * its session id, so the mark joins that run instead of opening another. Whether a mark is a
     * repeat is asked of the account's own history, and the flag only travels while the rewatch mode
     * and the plan allow it, the same gates the scrobble path uses: Simkl drops a write carrying the
     * flag for a free account silently, and the watch would be dropped with it.
     */
    override suspend fun addToHistory(
        profileId: Int,
        items: Collection<TrackingHistoryItem>,
    ): TrackingMutationResult {
        if (!isActiveProfile(profileId)) return TrackingMutationResult(attemptedCount = 0)
        SimklSyncRepository.ensureLoaded()
        TrackingSettingsRepository.ensureLoaded()
        val snapshot = SimklSyncRepository.state.value.snapshot
        val mode = TrackingSettingsRepository.uiState.value.simklRewatchMode
        val accountType = SimklAuthRepository.uiState.value.accountType
        val resolved = items.map { item ->
            val enriched = snapshot.enrichMediaReference(item.media).resolveAnimeEpisodeForSimkl()
            val runningSessionId = snapshot.rewatchSessions.activeRewatchSessionId(enriched)
            if (
                shouldRecordSimklRewatchOnMark(
                    mode = mode,
                    accountType = accountType,
                    isRepeatViewing = snapshot.entries.hasWatchedTitle(enriched),
                    hasRunningSession = runningSessionId != null,
                )
            ) {
                item.copy(media = enriched, isRewatch = true, rewatchId = runningSessionId)
            } else {
                item.copy(media = enriched)
            }
        }
        return service.addToHistory(resolved)
    }

    override suspend fun removeFromHistory(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
    ): TrackingMutationResult {
        if (!isActiveProfile(profileId)) return TrackingMutationResult(attemptedCount = 0)
        return service.removeFromHistory(items)
    }

    /**
     * Closes the running rewatch session of an item, which is how a card of that run is dropped.
     *
     * Answers false when the account holds no session for the item or the write did not land, so a
     * caller can leave the card alone instead of hiding something that is still on the account.
     */
    suspend fun closeRunningRewatchSession(media: TrackingMediaReference): Boolean {
        val snapshot = SimklSyncRepository.state.value.snapshot
        val sessionId = snapshot.rewatchSessions.activeRewatchSessionId(media) ?: return false
        val closed = runCatching {
            service.closeRewatchSession(media = media, rewatchId = sessionId)
        }.onFailure { error ->
            log.w { "Failed to close the Simkl rewatch session: ${error.message}" }
        }.getOrDefault(false)
        if (closed) {
            // The account took the close, so the run leaves the row at once: Continue Watching
            // follows the session list and only this object's background read publishes a change,
            // so waiting for that read would keep the card up for the whole settle window (the
            // reads sit 3, 10 and 25 seconds apart). The read below still runs and replaces this
            // with whatever the account reports.
            SimklSyncRepository.dropRewatchSession(sessionId)
        }
        // The sessions are read back in the background: Continue Watching follows the session list,
        // so the run leaves the row once the read sees the new state.
        refreshRewatchSessions { sessions ->
            sessions.none { session ->
                session.rewatchId == sessionId && session.isRunningRewatchSession()
            }
        }
        return closed
    }

    override suspend fun scrobble(
        profileId: Int,
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent,
    ) {
        if (!isActiveProfile(profileId)) return
        SimklSyncRepository.ensureLoaded()
        TrackingSettingsRepository.ensureLoaded()
        val snapshot = SimklSyncRepository.state.value.snapshot
        val media = snapshot.enrichMediaReference(event.media).resolveAnimeEpisodeForSimkl()
        val settings = TrackingSettingsRepository.uiState.value
        val mode = settings.simklRewatchMode
        val accountType = SimklAuthRepository.uiState.value.accountType
        // Where a playback counts as finished. IntroDB's credits marker decides it when there is one,
        // because that is where the content really ends, and the user's own percentage is the fallback.
        // Floored to the whole number the call carries as min_progress, so the stop and the rewatch
        // gates cannot drift apart from what the account judges the playback by.
        val completionThresholdPercent = simklMinProgressPercent(
            resolvedSimklCompletionPercent(
                userThresholdPercent = settings.simklWatchedThresholdPercent.toDouble(),
                contentEndPercent = event.contentEndPercent,
            ),
        ).toDouble()
        // The stop goes out as it is: the call carries the completion threshold as min_progress, so
        // the account keeps a playback below it as a resumable pause instead of this client
        // rewriting its own stop into one.
        // The run of the item, when the account already has one, and the answer the user gave to the
        // question before the playback: together they decide whether the scrobble asks Simkl for a
        // rewatch. A session the account runs releases the answer, which was for the run it opened.
        val runningSessionId = snapshot.rewatchSessions.activeRewatchSessionId(media)
        if (runningSessionId != null) {
            SimklRewatchConsentRepository.releaseGrant(media)
        }

        val recordRewatch = shouldRecordSimklRewatchOnStop(
            mode = mode,
            accountType = accountType,
            action = action,
            progressPercent = event.progressPercent,
            completionThresholdPercent = completionThresholdPercent,
            hasRunningSession = runningSessionId != null,
            consented = SimklRewatchConsentRepository.grantedFor(media),
            hasPlaybackSession = event.hasPlaybackSession,
        )
        val result = service.scrobble(
            action = action,
            event = event.copy(media = media),
            recordRewatch = recordRewatch,
            completionThresholdPercent = completionThresholdPercent,
        )
        // The prior watch has to be read before the commit, otherwise the playback looks like a
        // repeat viewing of itself. A stop asks whether the viewing that ended is a rewatch, a start
        // whether the viewing that is beginning opens a run.
        val priorWatch = if (
            action == TrackingScrobbleAction.STOP ||
            action == TrackingScrobbleAction.START
        ) {
            snapshot.priorWatchForScrobble(result)
        } else {
            SimklPriorWatch.None
        }
        if (action != TrackingScrobbleAction.START) {
            SimklSyncRepository.commitScrobble(result)
        }
        if (recordRewatch || result.rewatchStatus != null) {
            log.i {
                "Simkl rewatch action=${action.wireValue} status=" +
                    "${result.rewatchStatus?.name?.lowercase() ?: "none"} rewatching=${result.rewatchId != null}"
            }
        }

        // Simkl named the plan the account is on, and it is not the one this client had cached: the
        // rewatch decision below is gated on that plan, so the cache is corrected where the answer is
        // instead of at the next read of the user settings. Only the plan is written.
        val planAccountType = if (result.rewatchStatus == SimklRewatchStatus.PRO_REQUIRED) {
            log.i { "simkl rewatch plan no longer covers sessions; correcting the cached plan" }
            SimklAuthRepository.markPlanAsFree()
            SIMKL_FREE_PLAN
        } else {
            accountType
        }
        if (action == TrackingScrobbleAction.START) {
            val askToStartRun = shouldAskToStartSimklRewatch(
                mode = mode,
                accountType = planAccountType,
                action = action,
                priorWatch = priorWatch,
                nowEpochMs = SimklPlatformClock.nowEpochMs(),
                hasRunningSession = runningSessionId != null,
                progressPercent = event.progressPercent,
            )
            if (askToStartRun) {
                SimklRewatchConsentRepository.ask(media)
            }
        }
        if (action == TrackingScrobbleAction.STOP) {
            // A dismissal is for the playback it happened during: the playback has ended, so the
            // series may be asked about again the next time it plays.
            SimklRewatchConsentRepository.clearDismissal(media)
            if (result.rewatchId != null) {
                // A rewatch was actually recorded, so the answer before the playback has done its
                // job. For a movie this is the only release it gets, since movie sessions are never
                // read. A show keeps its answer until a running session is seen, because its session
                // only reaches the snapshot a few seconds later.
                if (media.kind == TrackingMediaKind.MOVIE) {
                    SimklRewatchConsentRepository.releaseGrant(media)
                }
                // A session the account just took is not on the snapshot until the sessions are read
                // again, and Continue Watching follows that read: refreshing keeps the row current
                // instead of waiting for the next sync.
                SimklSyncRepository.refreshAsync(TrackingRefreshIntent.INVALIDATED)
            }
        }
    }

    /**
     * Reads the rewatch sessions back until they say what the write should have done, then stores them.
     *
     * Runs in the background, because Simkl can take a while to publish a session change and nobody
     * is waiting for this. The last read is stored even when the condition never became true, so the
     * snapshot carries the freshest state the account offered.
     */
    private fun refreshRewatchSessions(settled: (List<SimklLibraryEntry>) -> Boolean) {
        scope.launch {
            // The read is paired with the sync watermark, so it carries the items changed since
            // the last sync, the write this waits for among them once Simkl publishes it. Every
            // pass of the loop reads the same window on purpose: a narrower one would skip a
            // change that was published between two reads.
            val dateFrom = SimklSyncRepository.state.value.snapshot.watermark
            var lastRead: List<SimklLibraryEntry>? = null
            var settledSeen = false
            for (waitMs in SIMKL_REWATCH_SESSION_READ_DELAYS_MS) {
                delay(waitMs)
                val sessions = runCatching { remote.fetchRewatchSessions(dateFrom) }
                    .onFailure { error ->
                        log.w { "Could not read the rewatch sessions back: ${error.message}" }
                    }
                    .getOrNull() ?: continue
                lastRead = sessions
                if (settled(sessions)) {
                    settledSeen = true
                    break
                }
            }
            // A write the account took but did not act on looks exactly like one it never saw, and
            // only the reads tell the two apart. The line is what a report of a card that came back
            // is diagnosed from.
            if (!settledSeen && lastRead != null) {
                log.w { "The account did not report the session change the write asked for" }
            }
            lastRead?.let { sessions -> SimklSyncRepository.adoptRewatchSessions(sessions) }
        }
    }

    private fun isActiveProfile(profileId: Int): Boolean = ProfileRepository.activeProfileId == profileId

    /** Keeps the session refresh alive after the prompt is answered and its caller is gone. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val log = Logger.withTag("SimklMutation")
}

internal fun buildSimklListMutationBody(
    items: Collection<TrackingMediaReference>,
    destination: TrackingListStatus,
    json: Json = SimklMutationJson,
): String {
    val requestItems = items.map { item ->
        item to SimklListItemDto(
            to = destination.wireValue,
            title = item.title.nonBlankOrNull(),
            year = item.year,
            ids = item.ids.toSimklJsonObjectOrNull(),
        )
    }
    return json.encodeToString(
        SimklListMutationRequestDto(
            movies = requestItems.filter { (item, _) -> item.kind == TrackingMediaKind.MOVIE }.map { it.second },
            shows = requestItems.filter { (item, _) -> item.kind != TrackingMediaKind.MOVIE }.map { it.second },
        ),
    )
}

/**
 * Closes a running rewatch session.
 *
 * The write names the session and the state it moves to, and carries no watch: closing changes what
 * the account keeps about the session, it is not a viewing. It has to say `is_rewatch: true` as well,
 * because that flag is what makes Simkl read the write through its rewatch logic at all: without it
 * the very same body is answered as a plain history write, the session is left alone, and nothing in
 * the answer says so.
 */
internal fun buildSimklRewatchCloseBody(
    media: TrackingMediaReference,
    rewatchId: Long,
    json: Json = SimklMutationJson,
): String = json.encodeToString(
    SimklHistoryMutationRequestDto(
        shows = listOf(
            media.toHistoryItemDto(
                watchedAtEpochMs = null,
                includeWatchedAt = false,
                isRewatch = true,
                rewatchId = rewatchId,
                rewatchStatus = SimklRewatchStatus.CLOSED.name.lowercase(),
            ),
        ),
    ),
)

internal fun buildSimklHistoryMutationBody(
    items: Collection<TrackingHistoryItem>,
    isRewatch: Boolean = false,
    json: Json = SimklMutationJson,
): String = json.encodeToString(buildHistoryRequest(items, includeWatchedAt = true, isRewatch = isRewatch))

internal fun buildSimklHistoryRemovalBody(
    items: Collection<TrackingMediaReference>,
    json: Json = SimklMutationJson,
): String = json.encodeToString(
    buildHistoryRequest(
        items = items.map { media -> TrackingHistoryItem(media = media) },
        includeWatchedAt = false,
        isRewatch = false,
    ),
)

internal fun buildSimklScrobbleBody(
    event: TrackingScrobbleEvent,
    json: Json = SimklMutationJson,
): String {
    val media = event.media.toScrobbleMediaDto()
    val usesTvStyleAnimeCoordinates = event.media.kind == TrackingMediaKind.ANIME &&
        event.media.episode?.season != null
    val request = SimklScrobbleRequestDto(
        progress = event.progressPercent.clampAndRoundProgress(),
        movie = media.takeIf { event.media.kind == TrackingMediaKind.MOVIE },
        show = media.takeIf {
            event.media.kind == TrackingMediaKind.SHOW || usesTvStyleAnimeCoordinates
        },
        anime = media.takeIf {
            event.media.kind == TrackingMediaKind.ANIME && !usesTvStyleAnimeCoordinates
        },
        episode = event.media.episode?.toEpisodeDto(
            includeSeason = true,
            includeWatchedAt = false,
            watchedAtEpochMs = null,
        ),
    )
    return json.encodeToString(request)
}

private fun buildHistoryRequest(
    items: Collection<TrackingHistoryItem>,
    includeWatchedAt: Boolean,
    isRewatch: Boolean,
): SimklHistoryMutationRequestDto {
    val movies = items
        .filter { item -> item.media.kind == TrackingMediaKind.MOVIE }
        .map { item ->
            item.media.toHistoryItemDto(
                watchedAtEpochMs = item.watchedAtEpochMs.takeIf { includeWatchedAt },
                includeWatchedAt = includeWatchedAt,
                isRewatch = item.isRewatch || isRewatch,
                rewatchId = item.rewatchId,
            )
        }
    val shows = items
        .filter { item -> item.media.kind != TrackingMediaKind.MOVIE }
        .groupBy { item -> item.media.stableKey }
        .values
        .map { matchingItems -> buildShowHistoryItem(matchingItems, includeWatchedAt, isRewatch) }
    return SimklHistoryMutationRequestDto(movies = movies, shows = shows)
}

private fun buildShowHistoryItem(
    items: List<TrackingHistoryItem>,
    includeWatchedAt: Boolean,
    requestRewatch: Boolean,
): SimklHistoryItemDto {
    val first = items.first()
    val parentMutation = items.lastOrNull { item -> item.media.episode == null }
    // The session and the flag travel on the item itself: a mark that is a repeat viewing names the
    // run it joins, and a caller that marks the whole request marks every item of it.
    val mutation = parentMutation ?: first
    val rewatchId = mutation.rewatchId
    val isRewatch = mutation.isRewatch || requestRewatch
    if (parentMutation != null) {
        return parentMutation.media.toHistoryItemDto(
            watchedAtEpochMs = parentMutation.watchedAtEpochMs.takeIf { includeWatchedAt },
            includeWatchedAt = includeWatchedAt,
            status = if (includeWatchedAt) TrackingListStatus.COMPLETED.wireValue else null,
            isRewatch = isRewatch,
            rewatchId = rewatchId,
        )
    }

    val episodeMutations = items.mapNotNull { item ->
        item.media.episode?.let { episode -> item to episode }
    }
    val flatEpisodes = episodeMutations
        .filter { (_, episode) -> episode.season == null }
        .map { (item, episode) ->
            episode.toEpisodeDto(
                includeSeason = false,
                includeWatchedAt = includeWatchedAt,
                watchedAtEpochMs = item.watchedAtEpochMs,
            )
        }
        .distinctBy(SimklEpisodeMutationDto::number)
    val seasons = episodeMutations
        .filter { (_, episode) -> episode.season != null }
        .groupBy { (_, episode) -> requireNotNull(episode.season) }
        .map { (season, seasonItems) ->
            SimklSeasonMutationDto(
                number = season,
                episodes = seasonItems
                    .map { (item, episode) ->
                        episode.toEpisodeDto(
                            includeSeason = false,
                            includeWatchedAt = includeWatchedAt,
                            watchedAtEpochMs = item.watchedAtEpochMs,
                        )
                    }
                    .distinctBy(SimklEpisodeMutationDto::number),
            )
        }
        .sortedBy(SimklSeasonMutationDto::number)

    return first.media.toHistoryItemDto(
        watchedAtEpochMs = null,
        includeWatchedAt = includeWatchedAt,
        episodes = flatEpisodes,
        seasons = seasons,
        useTvdbAnimeSeasons = first.media.kind == TrackingMediaKind.ANIME && seasons.isNotEmpty(),
        isRewatch = isRewatch,
        rewatchId = rewatchId,
    )
}

private fun TrackingMediaReference.toHistoryItemDto(
    watchedAtEpochMs: Long?,
    includeWatchedAt: Boolean,
    status: String? = null,
    episodes: List<SimklEpisodeMutationDto> = emptyList(),
    seasons: List<SimklSeasonMutationDto> = emptyList(),
    useTvdbAnimeSeasons: Boolean = false,
    isRewatch: Boolean = false,
    rewatchId: Long? = null,
    rewatchStatus: String? = null,
): SimklHistoryItemDto = SimklHistoryItemDto(
    title = title.nonBlankOrNull(),
    year = year,
    ids = ids.toSimklJsonObjectOrNull(),
    watchedAt = watchedAtEpochMs.takeIf { includeWatchedAt }?.epochMsToUtcIso(),
    status = status,
    episodes = episodes,
    seasons = seasons,
    useTvdbAnimeSeasons = useTvdbAnimeSeasons,
    isRewatch = isRewatch.takeIf { it },
    rewatchId = rewatchId,
    rewatchStatus = rewatchStatus,
)

private fun TrackingMediaReference.toScrobbleMediaDto(): SimklScrobbleMediaDto =
    SimklScrobbleMediaDto(
        title = title.nonBlankOrNull(),
        year = year,
        ids = ids.toSimklJsonObjectOrNull(),
    )

private fun TrackingEpisode.toEpisodeDto(
    includeSeason: Boolean,
    includeWatchedAt: Boolean,
    watchedAtEpochMs: Long?,
): SimklEpisodeMutationDto = SimklEpisodeMutationDto(
    season = season.takeIf { includeSeason },
    number = number,
    watchedAt = watchedAtEpochMs.takeIf { includeWatchedAt }?.epochMsToUtcIso(),
)

internal fun TrackingExternalIds.toSimklJsonObjectOrNull(): JsonObject? {
    val value = buildJsonObject {
        simkl?.let { put("simkl", it) }
        imdb.nonBlankOrNull()?.let { put("imdb", it) }
        tmdb?.let { put("tmdb", it) }
        tvdb.nonBlankOrNull()?.let { tvdbValue ->
            tvdbValue.toLongOrNull()?.let { put("tvdb", it) } ?: put("tvdb", tvdbValue)
        }
        mal?.let { put("mal", it) }
        anidb?.let { put("anidb", it) }
        anilist?.let { put("anilist", it) }
        kitsu?.let { put("kitsu", it) }
    }
    return value.takeIf { it.isNotEmpty() }
}

private fun Double.clampAndRoundProgress(): Double = round(coerceIn(0.0, 100.0) * 100.0) / 100.0

private fun String?.nonBlankOrNull(): String? = this?.trim()?.takeIf(String::isNotEmpty)

internal fun Long.epochMsToUtcIso(): String? {
    if (this < 10_000_000_000L) return null
    val totalSeconds = this / 1_000L
    val second = (totalSeconds % 60L).toInt()
    val minute = ((totalSeconds / 60L) % 60L).toInt()
    val hour = ((totalSeconds / 3_600L) % 24L).toInt()
    var days = totalSeconds / 86_400L
    var year = 1970
    while (true) {
        val daysInYear = if (year.isLeapYear()) 366 else 365
        if (days < daysInYear) break
        days -= daysInYear
        year += 1
    }
    val monthDays = if (year.isLeapYear()) {
        intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    } else {
        intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    }
    var monthIndex = 0
    while (monthIndex < monthDays.size && days >= monthDays[monthIndex]) {
        days -= monthDays[monthIndex]
        monthIndex += 1
    }
    val month = monthIndex + 1
    val day = days.toInt() + 1
    return "${year.pad(4)}-${month.pad(2)}-${day.pad(2)}T${hour.pad(2)}:${minute.pad(2)}:${second.pad(2)}Z"
}

private fun Int.isLeapYear(): Boolean = (this % 4 == 0 && this % 100 != 0) || this % 400 == 0
private fun Int.pad(length: Int): String = toString().padStart(length, '0')

private val SimklMutationJson = Json {
    encodeDefaults = false
    explicitNulls = false
}

@Serializable
private data class SimklListMutationRequestDto(
    val movies: List<SimklListItemDto> = emptyList(),
    val shows: List<SimklListItemDto> = emptyList(),
)

@Serializable
private data class SimklListItemDto(
    val to: String,
    val title: String? = null,
    val year: Int? = null,
    val ids: JsonObject? = null,
)

@Serializable
private data class SimklHistoryMutationRequestDto(
    val movies: List<SimklHistoryItemDto> = emptyList(),
    val shows: List<SimklHistoryItemDto> = emptyList(),
)

@Serializable
private data class SimklHistoryItemDto(
    val title: String? = null,
    val year: Int? = null,
    val ids: JsonObject? = null,
    @SerialName("watched_at") val watchedAt: String? = null,
    val status: String? = null,
    val episodes: List<SimklEpisodeMutationDto> = emptyList(),
    val seasons: List<SimklSeasonMutationDto> = emptyList(),
    @SerialName("use_tvdb_anime_seasons") val useTvdbAnimeSeasons: Boolean = false,
    @SerialName("is_rewatch") val isRewatch: Boolean? = null,
    @SerialName("rewatch_id") val rewatchId: Long? = null,
    @SerialName("rewatch_status") val rewatchStatus: String? = null,
)

@Serializable
private data class SimklSeasonMutationDto(
    val number: Int,
    val episodes: List<SimklEpisodeMutationDto> = emptyList(),
)

@Serializable
private data class SimklEpisodeMutationDto(
    val season: Int? = null,
    val number: Int,
    @SerialName("watched_at") val watchedAt: String? = null,
)

@Serializable
private data class SimklScrobbleRequestDto(
    val progress: Double,
    val movie: SimklScrobbleMediaDto? = null,
    val show: SimklScrobbleMediaDto? = null,
    val anime: SimklScrobbleMediaDto? = null,
    val episode: SimklEpisodeMutationDto? = null,
)

@Serializable
private data class SimklScrobbleMediaDto(
    val title: String? = null,
    val year: Int? = null,
    val ids: JsonObject? = null,
)
