package com.nuvio.app.features.mdblist

class MdbListSyncEngine(
    private val remote: MdbListSyncRemote,
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() }
) {
    suspend fun synchronize(previous: MdbListSyncSnapshot): MdbListSyncSnapshot {
        val activities = remote.activities()
        val previousActivities = previous.activities
        val full = !previous.isInitialized || previousActivities == null
        val watermark = previous.watermark
        val expired = watermark == null || mdbListTimestamp(activities.serverTime) -
            mdbListTimestamp(watermark) !in 0 until JOURNAL_RETENTION_MS
        val watched = when {
            full || expired || MdbListSyncBucket.WATCHED in previous.invalidatedBuckets -> remote.watched()
            activities.watchedChanged(previousActivities) -> {
                val journal = remote.journal(watermark)
                if (journal.requiresFullSync) remote.watched()
                else applyMdbListJournal(previous, journal.items)
            }
            else -> previous.watched
        }
        val playback = if (full || expired || MdbListSyncBucket.PLAYBACK in previous.invalidatedBuckets ||
            activities.playbackChanged(previousActivities)) {
            remote.playback()
        } else previous.playback
        val dropped = if (full || expired || MdbListSyncBucket.DROPPED in previous.invalidatedBuckets ||
            activities.droppedChanged(previousActivities)) {
            remote.dropped()
        } else previous.dropped
        val next = previous.copy(
            watched = watched,
            playback = playback,
            dropped = dropped,
            activities = activities,
            watermark = activities.serverTime,
            checkedAtEpochMs = now(),
            isInitialized = true,
            invalidatedBuckets = emptySet()
        )
        return if (watched === previous.watched && playback === previous.playback) next else next.normalizeMedia()
    }

    private companion object {
        const val JOURNAL_RETENTION_MS = 30L * 24 * 60 * 60 * 1_000
    }
}

internal fun applyMdbListJournal(
    snapshot: MdbListSyncSnapshot,
    journal: List<MdbListJournalRecord>
): List<MdbListWatchedRecord> {
    val index = MdbListMediaIndex(snapshot)
    journal.forEach { index.add(it.type, MdbListMedia(it.ids)) }
    val records = snapshot.watched.associateByTo(linkedMapOf()) { record ->
        record.copy(media = index.resolve(record.type, record.media.ids)).key
    }
    journal.sortedBy { mdbListTimestamp(it.actionAt) }.forEach { change ->
        val media = index.resolve(change.type, change.ids)
        val record = MdbListWatchedRecord(
            type = change.type,
            media = media,
            watchedAt = change.watchedAt ?: change.actionAt,
            season = change.season,
            episode = change.episode,
            episodeTmdbId = change.episodeTmdbId,
            episodeTvdbId = change.episodeTvdbId
        )
        if (change.removed) {
            records.remove(record.key)
        } else {
            val previous = records[record.key]
            records[record.key] = record.copy(
                episodeTitle = previous?.episodeTitle,
                episodeTmdbId = record.episodeTmdbId ?: previous?.episodeTmdbId,
                episodeTvdbId = record.episodeTvdbId ?: previous?.episodeTvdbId
            )
        }
    }
    return records.values.toList()
}
