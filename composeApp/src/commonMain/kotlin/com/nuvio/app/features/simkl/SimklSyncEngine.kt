package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.features.tracking.TrackingSettingsRepository

internal class SimklSyncEngine(
    private val remote: SimklSyncRemote,
    private val nowEpochMs: () -> Long,
) {
    private val log = Logger.withTag("SimklSync")

    suspend fun synchronize(
        current: SimklSyncSnapshot,
        forceRewatchRead: Boolean = false,
    ): SimklSyncSnapshot {
        if (!current.isInitialized) return initialSync()

        val activities = remote.fetchActivities()
        if (
            activities.all == current.watermark &&
            !forceRewatchRead &&
            current.rewatchSessionsComplete
        ) {
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
        val rewatch = readRewatchRuns(current, forceRewatchRead = forceRewatchRead)
        return current.copy(
            watermark = activities.all,
            activities = activities,
            entries = entries,
            playback = playback,
            rewatchRuns = rewatch.runs,
            rewatchSessions = rewatch.sessions,
            rewatchSessionsComplete = rewatch.complete,
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
        val rewatch = readRewatchRuns(current = null, forceRewatchRead = false)
        return SimklSyncSnapshot(
            isInitialized = true,
            watermark = activities.all,
            activities = activities,
            entries = entries.distinctBy(SimklLibraryEntry::stableKey),
            playback = playback,
            rewatchRuns = rewatch.runs,
            rewatchSessions = rewatch.sessions,
            rewatchSessionsComplete = rewatch.complete,
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
    private suspend fun readRewatchRuns(
        current: SimklSyncSnapshot?,
        forceRewatchRead: Boolean,
    ): SimklRewatchRead {
        // Load the settings before the mode is read: the state flow starts at the default (Off), and
        // a read taken mid-startup would report rewatches as off and drop the runs with them. The TV
        // reads the mode from a DataStore flow, so it never sees the default.
        TrackingSettingsRepository.ensureLoaded()
        val mode = TrackingSettingsRepository.uiState.value.simklRewatchMode
        val accountType = SimklAuthRepository.uiState.value.accountType
        if (mode == SimklRewatchMode.OFF || !isSimklRewatchPlanEligible(accountType)) {
            // The runs are dropped rather than kept alive forever: while rewatches are off, or a
            // plan that cannot hold sessions is known, nothing may offer them. The sessions of the
            // last read stay on the snapshot either way: they are the raw input the mode re-derives
            // from when the user turns rewatches back on, and dropping them is what made that switch
            // wait for a manual sync. An account type that has not been read yet keeps the runs too,
            // because there the plan is unknown, not known to be ineligible.
            val knownToHoldNoSessions = mode == SimklRewatchMode.OFF || accountType != null
            return if (knownToHoldNoSessions) {
                SimklRewatchRead(
                    runs = emptyList(),
                    sessions = current?.rewatchSessions.orEmpty(),
                    complete = current?.rewatchSessionsComplete ?: false,
                )
            } else {
                SimklRewatchRead(
                    runs = current?.rewatchRuns.orEmpty(),
                    sessions = current?.rewatchSessions.orEmpty(),
                    complete = current?.rewatchSessionsComplete ?: false,
                )
            }
        }
        // The guide's rule: every read outside the initial sync pairs date_from, so the server
        // returns the changed slice instead of the whole library. That slice mentions only the
        // items that changed, and the runs need every session the account holds, so it is merged
        // into the sessions already on the snapshot. The unpaired full read stays for the initial
        // sync, and for the recovery a Sync now can ask for while there is no session to merge
        // into (a snapshot that never read, or one a wipe left empty).
        // Only a completed read may be continued as a delta. A snapshot from before this feature,
        // or one whose read failed, carries an empty list that a delta would never fill.
        val dateFrom = current?.takeIf { it.rewatchSessionsComplete }?.watermark
        return runCatching {
            val read = remote.fetchRewatchSessions(dateFrom)
            val sessions = mergeRewatchSessions(read, current?.rewatchSessions.orEmpty())
            SimklRewatchRead(
                runs = deriveSimklRewatchRuns(
                    entries = sessions,
                    animeIdPreference = TrackingSettingsRepository.uiState.value.simklAnimeIdPreference,
                    offerRuns = true,
                ),
                sessions = sessions,
                complete = true,
            )
        }.getOrElse { error ->
            log.w { "Simkl rewatch sessions could not be read: ${error.message}" }
            SimklRewatchRead(
                runs = current?.rewatchRuns.orEmpty(),
                sessions = current?.rewatchSessions.orEmpty(),
                complete = current?.rewatchSessionsComplete ?: false,
            )
        }
    }
}

internal fun mergeDelta(
    current: List<SimklLibraryEntry>,
    delta: SimklAllItemsResponse,
): List<SimklLibraryEntry> {
    val merged = current.mapNotNull { entry -> entry.stableKey()?.let { key -> key to entry } }.toMap().toMutableMap()
    val deltaEntries = mutableListOf<SimklLibraryEntry>()
    delta.presentTypes().forEach { type ->
        delta.entriesFor(type).forEach { entry ->
            entry.stableKey()?.let { key -> merged[key] = entry }
            deltaEntries += entry
        }
    }
    // A delta entry can arrive under a new canonical key while the same media is already held
    // under the old one. Without this pass the library carries the item twice (two Continue
    // Watching cards, two rows) until a removal reconcile happens to drop the stale key. Mirrors
    // the TV's second pass.
    deltaEntries.forEach { deltaEntry ->
        val deltaMedia = deltaEntry.media ?: return@forEach
        val deltaKey = deltaEntry.stableKey()
        merged.entries.removeAll { entry ->
            entry.key != deltaKey &&
                entry.value.mediaType == deltaEntry.mediaType &&
                entry.value.media?.matchesTarget(deltaMedia) == true
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
