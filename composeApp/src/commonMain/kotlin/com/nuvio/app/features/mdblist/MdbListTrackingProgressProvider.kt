package com.nuvio.app.features.mdblist

import com.nuvio.app.features.tracking.TrackingProgressProvider
import com.nuvio.app.features.tracking.TrackingProgressSnapshot
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

class MdbListTrackingProgressProvider(
    private val sync: MdbListSyncRepository,
    private val scrobble: MdbListScrobbleService,
    private val episodeMappings: MdbListEpisodeMappingService,
    auth: MdbListAuthStore,
    activeProfile: StateFlow<Int>,
    private val ensureAccountLoaded: () -> Unit,
) : TrackingProgressProvider {
    override val providerId = TrackingProviderId.MDBLIST
    override val changes = combine(sync.state, auth.state, activeProfile, episodeMappings.remapped) { _, _, _, _ -> Unit }
    override val ownsCompletedHistoryProjection = true
    private val snapshotEntries = MdbListRemapCache<List<WatchProgressEntry>>()
    override fun showIdSiblings() = sync.currentProjection().showIdSiblings

    override fun ensureLoaded() = ensureAccountLoaded()

    override suspend fun refresh(force: Boolean, sourceChanged: Boolean) {
        ensureAccountLoaded()
        sync.refresh(if (force) TrackingRefreshIntent.USER_INITIATED else TrackingRefreshIntent.AUTOMATIC)
    }

    /**
     * Episodes of shows with a known mapping come in the addon's numbering. In-progress episodes of
     * other shows have their mapping loaded in the background, and [changes] emits once it is known.
     */
    override fun snapshot(): TrackingProgressSnapshot {
        val projection = sync.currentProjection()
        val state = sync.state.value.takeIf { it.scope == runCatching { sync.currentScope() }.getOrNull() }
        episodeMappings.prefetch(
            projection.progress.filter { it.seasonNumber != null }.map(WatchProgressEntry::parentMetaId),
            projection,
        )
        val mappings = episodeMappings.remapped.value
        return TrackingProgressSnapshot(
            entries = snapshotEntries.get(projection, mappings) {
                (projection.progress + projection.nextUpSeeds).map { entry ->
                    mappings[entry.parentMetaId]?.let(entry::inAddonNumbering) ?: entry
                }
            },
            hiddenContentIds = projection.hiddenContentIds,
            hasLoadedRemoteProgress = state?.hasLoaded == true,
            errorMessage = state?.error?.localizedMdbListMessage(),
        )
    }

    override suspend fun prepareNextUpProgressEntries(
        entries: List<WatchProgressEntry>,
        contentId: String,
    ): List<WatchProgressEntry> {
        // snapshot() already moved the entries of shows whose mapping was known.
        if (episodeMappings.cached(contentId) != null) return entries
        val mapping = episodeMappings.resolve(contentId, sync.currentProjection()) ?: return entries
        return entries.map { entry ->
            if (entry.parentMetaId == contentId && entry.trackingProviderId == "mdblist") entry.inAddonNumbering(mapping) else entry
        }
    }

    override suspend fun removeProgress(entries: Collection<WatchProgressEntry>) {
        val scope = sync.currentScope()
        entries.distinctBy { Triple(it.parentMetaId, it.seasonNumber, it.episodeNumber) }.forEach { entry ->
            val season = entry.seasonNumber
            val episode = entry.episodeNumber
            // Only entries of shows with a known mapping were moved to the addon's numbering.
            val tmdb = if (season != null && episode != null) {
                episodeMappings.cached(entry.parentMetaId)?.takeIf { it.remaps }?.toTmdb(season, episode, entry.videoId)
            } else null
            scrobble.clear(scope, entry.parentMetaId, tmdb?.season ?: season, tmdb?.episode ?: episode)
        }
    }

    override fun normalizeParentContentId(parentContentId: String, videoId: String?): String =
        sync.currentProjection().canonicalContentId(parentContentId) ?: parentContentId

    override fun isHiddenFromProgress(contentId: String): Boolean = sync.currentProjection().isHidden(contentId)

    override suspend fun refreshEpisodeProgress(contentId: String, forceRefresh: Boolean) {
        sync.refresh(if (forceRefresh) TrackingRefreshIntent.USER_INITIATED else TrackingRefreshIntent.AUTOMATIC)
        // The details screen lists the show's episodes, so its watched marks and progress need the
        // mapping whenever MDBList has any of them.
        val projection = sync.currentProjection()
        if (contentId in projection.showIdSiblings) episodeMappings.resolve(contentId, projection)
    }
}

private fun WatchProgressEntry.inAddonNumbering(mapping: MdbListEpisodeMapping): WatchProgressEntry {
    if (!mapping.remaps) return this
    val addon = mapping.toAddon(seasonNumber ?: return this, episodeNumber ?: return this) ?: return this
    return copy(
        seasonNumber = addon.season,
        episodeNumber = addon.episode,
        videoId = buildPlaybackVideoId(parentMetaId, addon.season, addon.episode, fallbackVideoId = videoId),
        episodeTitle = addon.title ?: episodeTitle,
    )
}
