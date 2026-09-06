package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingEpisode
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingHistoryItem
import com.nuvio.app.features.tracking.TrackingListStatus
import com.nuvio.app.features.tracking.TrackingHistoryWriter
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
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
        val body = buildSimklHistoryMutationBody(candidates, json)
        val response = client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/sync/history",
                query = if (allowRewatch) mapOf("allow_rewatch" to "yes") else emptyMap(),
                body = body,
                retryPolicy = SimklRetryPolicy.SYNC_WRITE,
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

    suspend fun scrobble(
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent,
    ): SimklScrobbleResult {
        require(event.media.hasResolvableIdentity) { "Simkl scrobble requires a media ID or title" }
        require(event.media.kind == TrackingMediaKind.MOVIE || event.media.episode != null) {
            "Simkl series scrobble requires an episode"
        }
        val response = client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/scrobble/${action.wireValue}",
                body = buildSimklScrobbleBody(event, json),
                retryPolicy = SimklRetryPolicy.NEVER,
                scrobbleStopConflictIsSuccess = action == TrackingScrobbleAction.STOP,
            ),
        )
        return response.toSimklScrobbleResult(action, event, json)
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

    private val log = Logger.withTag("SimklMutation")

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

    override suspend fun addToHistory(
        profileId: Int,
        items: Collection<TrackingHistoryItem>,
    ): TrackingMutationResult {
        if (!isActiveProfile(profileId)) return TrackingMutationResult(attemptedCount = 0)
        SimklSyncRepository.ensureLoaded()
        val snapshot = SimklSyncRepository.state.value.snapshot
        val resolved = items.map { item ->
            val enriched = snapshot.enrichMediaReference(item.media)
            item.copy(media = enriched.resolveAnimeEpisodeForSimkl())
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

    override suspend fun scrobble(
        profileId: Int,
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent,
    ) {
        if (!isActiveProfile(profileId)) return
        SimklSyncRepository.ensureLoaded()
        val enriched = SimklSyncRepository.state.value.snapshot.enrichMediaReference(event.media)
        val resolvedMedia = enriched.resolveAnimeEpisodeForSimkl()
        val result = service.scrobble(
            action = action,
            event = event.copy(media = resolvedMedia),
        )
        // Evaluate before committing this scrobble locally: a first watch is not a rewatch.
        val shouldRecordRewatch = result.outcome == SimklScrobbleOutcome.SCROBBLE &&
            isRewatchRecordingEnabled() &&
            SimklSyncRepository.state.value.snapshot.hasPriorWatch(result)
        if (action != TrackingScrobbleAction.START) {
            SimklSyncRepository.commitScrobble(result)
        }
        if (shouldRecordRewatch) {
            recordRewatchWrite(media = resolvedMedia, result = result)
        }
    }

    private suspend fun isRewatchRecordingEnabled(): Boolean {
        TrackingSettingsRepository.ensureLoaded()
        if (!TrackingSettingsRepository.uiState.value.simklRewatchesEnabled) return false
        var accountType = SimklAuthRepository.snapshot().accountType
        if (accountType == null) {
            // Plan is unknown until user settings have been fetched at least once.
            SimklAuthRepository.refreshUserSettings()
            accountType = SimklAuthRepository.snapshot().accountType
        }
        return accountType == "pro" || accountType == "vip"
    }

    private suspend fun recordRewatchWrite(
        media: TrackingMediaReference,
        result: SimklScrobbleResult,
    ) {
        val watchedAtEpochMs = result.watchedAt?.let(::parseSimklUtcEpochMs)
            ?: SimklPlatformClock.nowEpochMs()
        try {
            service.addToHistory(
                items = listOf(TrackingHistoryItem(media = media, watchedAtEpochMs = watchedAtEpochMs)),
                allowRewatch = true,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w(error) { "Simkl rewatch write failed" }
        }
    }

    private fun isActiveProfile(profileId: Int): Boolean = ProfileRepository.activeProfileId == profileId
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

internal fun buildSimklHistoryMutationBody(
    items: Collection<TrackingHistoryItem>,
    json: Json = SimklMutationJson,
): String = json.encodeToString(buildHistoryRequest(items, includeWatchedAt = true))

internal fun buildSimklHistoryRemovalBody(
    items: Collection<TrackingMediaReference>,
    json: Json = SimklMutationJson,
): String = json.encodeToString(
    buildHistoryRequest(
        items = items.map { media -> TrackingHistoryItem(media = media) },
        includeWatchedAt = false,
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
): SimklHistoryMutationRequestDto {
    val movies = items
        .filter { item -> item.media.kind == TrackingMediaKind.MOVIE }
        .map { item ->
            item.media.toHistoryItemDto(
                watchedAtEpochMs = item.watchedAtEpochMs.takeIf { includeWatchedAt },
                includeWatchedAt = includeWatchedAt,
            )
        }
    val shows = items
        .filter { item -> item.media.kind != TrackingMediaKind.MOVIE }
        .groupBy { item -> item.media.stableKey }
        .values
        .map { matchingItems -> buildShowHistoryItem(matchingItems, includeWatchedAt) }
    return SimklHistoryMutationRequestDto(movies = movies, shows = shows)
}

private fun buildShowHistoryItem(
    items: List<TrackingHistoryItem>,
    includeWatchedAt: Boolean,
): SimklHistoryItemDto {
    val first = items.first()
    val parentMutation = items.lastOrNull { item -> item.media.episode == null }
    if (parentMutation != null) {
        return parentMutation.media.toHistoryItemDto(
            watchedAtEpochMs = parentMutation.watchedAtEpochMs.takeIf { includeWatchedAt },
            includeWatchedAt = includeWatchedAt,
            status = if (includeWatchedAt) TrackingListStatus.COMPLETED.wireValue else null,
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
    )
}

private fun TrackingMediaReference.toHistoryItemDto(
    watchedAtEpochMs: Long?,
    includeWatchedAt: Boolean,
    status: String? = null,
    episodes: List<SimklEpisodeMutationDto> = emptyList(),
    seasons: List<SimklSeasonMutationDto> = emptyList(),
    useTvdbAnimeSeasons: Boolean = false,
): SimklHistoryItemDto = SimklHistoryItemDto(
    title = title.nonBlankOrNull(),
    year = year,
    ids = ids.toSimklJsonObjectOrNull(),
    watchedAt = watchedAtEpochMs.takeIf { includeWatchedAt }?.epochMsToUtcIso(),
    status = status,
    episodes = episodes,
    seasons = seasons,
    useTvdbAnimeSeasons = useTvdbAnimeSeasons,
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
