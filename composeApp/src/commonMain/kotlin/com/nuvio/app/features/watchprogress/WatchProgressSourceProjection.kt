package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.tracking.WatchProgressSource

internal fun projectWatchProgressSourceEntries(
    source: WatchProgressSource,
    nuvioEntries: Collection<WatchProgressEntry>,
    providerEntries: Collection<WatchProgressEntry>,
): List<WatchProgressEntry> = if (source.providerId == null) {
    nuvioEntries.toList()
} else if (source != WatchProgressSource.TRAKT) {
    providerEntries.toList()
} else {
    providerEntries.map { providerEntry ->
        val localEntry = nuvioEntries
            .asSequence()
            .filter { candidate ->
                candidate.resolvedProgressKey() == providerEntry.resolvedProgressKey() ||
                    candidate.hasSameLogicalIdentity(providerEntry)
            }
            .maxWithOrNull(watchProgressEntryFreshnessComparator)
        if (localEntry != null && localEntry.lastUpdatedEpochMs > providerEntry.lastUpdatedEpochMs) {
            localEntry.copy(progressKey = providerEntry.resolvedProgressKey())
        } else {
            providerEntry
        }
    }
}

private fun WatchProgressEntry.hasSameLogicalIdentity(other: WatchProgressEntry): Boolean =
    parentMetaId == other.parentMetaId &&
        seasonNumber == other.seasonNumber &&
        episodeNumber == other.episodeNumber
