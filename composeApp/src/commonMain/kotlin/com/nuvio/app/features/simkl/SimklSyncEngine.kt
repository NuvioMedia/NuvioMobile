package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.features.tracking.TrackingSettingsRepository

internal class SimklSyncEngine(
    private val remote: SimklSyncRemote,
    private val nowEpochMs: () -> Long,
) {
    private val log = Logger.withTag("SimklSync")

    suspend fun synchronize(current: SimklSyncSnapshot): SimklSyncSnapshot {
        if (!current.isInitialized) return initialSync()

        val activities = remote.fetchActivities()
        if (activities.all == current.watermark) {
            return current.copy(
                activities = activities,
                lastCheckedAtEpochMs = nowEpochMs(),
            )
        }
        if (current.watermark == null) return initialSync()

        var entries = current.entries
        if (hasAllItemsActivityChanged(current.activities, activities)) {
            val delta = remote.fetchAllItems(SimklAllItemsRequest.Changes(current.watermark))
            entries = mergeDelta(entries, delta)
        }

        if (hasRemovalActivityChanged(current.activities, activities)) {
            val authoritativeIds = remote.fetchAllItems(SimklAllItemsRequest.CurrentIds)
            entries = reconcileRemovedEntries(entries, authoritativeIds)
        }

        val playback = if (hasPlaybackActivityChanged(current.activities, activities)) {
            mergeFetchedPlayback(fetched = remote.fetchPlayback(), held = current.playback)
        } else {
            current.playback
        }

        val now = nowEpochMs()
        val rewatch = readRewatchRuns(current)
        return current.copy(
            watermark = activities.all,
            activities = activities,
            entries = entries,
            playback = playback,
            rewatchRuns = rewatch.runs,
            rewatchSessions = rewatch.sessions,
            lastSyncedAtEpochMs = now,
            lastCheckedAtEpochMs = now,
        ).reconcileWatchedPlayback()
    }

    private suspend fun initialSync(): SimklSyncSnapshot {
        val entries = buildList {
            SimklMediaType.entries.forEach { type ->
                addAll(
                    remote.fetchAllItems(SimklAllItemsRequest.Bootstrap(type))
                        .entriesFor(type),
                )
            }
        }
        val playback = remote.fetchPlayback()
        val activities = remote.fetchActivities()
        val now = nowEpochMs()
        val rewatch = readRewatchRuns(current = null)
        return SimklSyncSnapshot(
            isInitialized = true,
            watermark = activities.all,
            activities = activities,
            entries = entries.distinctBy(SimklLibraryEntry::stableKey),
            playback = playback,
            rewatchRuns = rewatch.runs,
            rewatchSessions = rewatch.sessions,
            lastSyncedAtEpochMs = now,
            lastCheckedAtEpochMs = now,
        ).reconcileWatchedPlayback()
    }

    /**
     * Reads the rewatch sessions of the account, keeps them, and turns them into runs.
     *
     * A failed read keeps what the previous sync found: losing the network must not empty the
     * Continue Watching cards the user is looking at.
     */
    private suspend fun readRewatchRuns(current: SimklSyncSnapshot?): SimklRewatchRead =
        runCatching {
            val sessions = remote.fetchRewatchSessions()
            SimklRewatchRead(
                runs = deriveSimklRewatchRuns(
                    entries = sessions,
                    animeIdPreference = TrackingSettingsRepository.uiState.value.simklAnimeIdPreference,
                    minimumRunEpisodes = TrackingSettingsRepository.uiState.value
                        .simklRewatchNextUpMode.minimumRunEpisodes,
                ),
                sessions = sessions,
            )
        }.getOrElse { error ->
            log.w { "Simkl rewatch sessions could not be read: ${error.message}" }
            SimklRewatchRead(
                runs = current?.rewatchRuns.orEmpty(),
                sessions = current?.rewatchSessions.orEmpty(),
            )
        }
}

internal fun mergeDelta(
    current: List<SimklLibraryEntry>,
    delta: SimklAllItemsResponse,
): List<SimklLibraryEntry> {
    val merged = current.mapNotNull { entry -> entry.stableKey()?.let { key -> key to entry } }.toMap().toMutableMap()
    delta.presentTypes().forEach { type ->
        delta.entriesFor(type).forEach { entry ->
            entry.stableKey()?.let { key -> merged[key] = entry }
        }
    }
    return merged.values.sortedWith(simklEntryComparator)
}

internal fun reconcileRemovedEntries(
    current: List<SimklLibraryEntry>,
    authoritative: SimklAllItemsResponse,
): List<SimklLibraryEntry> {
    val allowedKeys = SimklMediaType.entries.flatMapTo(mutableSetOf()) { type ->
        authoritative.entriesFor(type).mapNotNull(SimklLibraryEntry::stableKey)
    }
    return current.filter { entry -> entry.stableKey() in allowedKeys }
        .sortedWith(simklEntryComparator)
}

private fun hasAllItemsActivityChanged(
    previous: SimklActivities?,
    current: SimklActivities,
): Boolean {
    if (previous == null) return true
    return SimklMediaType.entries.any { type ->
        current.domain(type).hasAllItemsActivityChangedFrom(previous.domain(type))
    }
}

private fun SimklActivityDomain.hasAllItemsActivityChangedFrom(
    previous: SimklActivityDomain,
): Boolean =
    ratedAt != previous.ratedAt ||
        plantowatch != previous.plantowatch ||
        watching != previous.watching ||
        completed != previous.completed ||
        hold != previous.hold ||
        dropped != previous.dropped

private fun hasRemovalActivityChanged(
    previous: SimklActivities?,
    current: SimklActivities,
): Boolean =
    previous == null ||
        SimklMediaType.entries.any { type ->
            previous.domain(type).removedFromList != current.domain(type).removedFromList
        }

private fun hasPlaybackActivityChanged(
    previous: SimklActivities?,
    current: SimklActivities,
): Boolean =
    previous == null ||
        SimklMediaType.entries.any { type ->
            previous.domain(type).playback != current.domain(type).playback
        }

private val simklEntryComparator = compareBy<SimklLibraryEntry>(
    { entry -> entry.mediaType.ordinal },
    { entry -> entry.media?.title.orEmpty().lowercase() },
    { entry -> entry.stableKey().orEmpty() },
)
