package com.nuvio.app.features.simkl

import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.tracking.parseTrackingExternalIds
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlinx.atomicfu.atomic

internal fun SimklSyncSnapshot.reconcileWatchedPlayback(): SimklSyncSnapshot {
    if (playback.isEmpty()) return this
    val watchedItems = toSimklWatchedProjection().items
    val retainedPlayback = playback.filterNot { session ->
        session.toWatchProgressEntry(entries)?.let { progress ->
            entries.any { entry -> entry.hidesPlayback(progress) } ||
                watchedItems.any { watched -> watched.supersedes(progress) }
        } == true
    }
    return if (retainedPlayback.size == playback.size) this else copy(playback = retainedPlayback)
}

internal fun SimklSyncSnapshot.isHiddenFromContinueWatching(contentId: String): Boolean =
    hiddenContentIndex().contains(contentId)

internal fun SimklSyncSnapshot.hiddenFromContinueWatchingContentIds(): Set<String> =
    hiddenContentIndex().canonicalContentIds

private val hiddenContentIndexCache = atomic<SimklHiddenContentIndex?>(null)

private fun SimklSyncSnapshot.hiddenContentIndex(): SimklHiddenContentIndex {
    val animeIdPreference = TrackingSettingsRepository.uiState.value.simklAnimeIdPreference
    hiddenContentIndexCache.value?.let { cached ->
        if (cached.snapshot === this && cached.animeIdPreference == animeIdPreference) return cached
    }
    val canonicalContentIds = linkedSetOf<String>()
    val externalIdentityKeys = mutableSetOf<String>()
    entries.forEach { entry ->
        if (!entry.status.hidesContinueWatching()) return@forEach
        val media = entry.media ?: return@forEach
        media.canonicalContentId(animeIdPreference)?.let(canonicalContentIds::add)
        val ids = media.toTrackingExternalIds()
        ids.simkl?.let { externalIdentityKeys.add("simkl:$it") }
        ids.imdb?.takeIf(String::isNotBlank)?.let { externalIdentityKeys.add("imdb:${it.caseInsensitiveIdentity()}") }
        ids.tmdb?.let { externalIdentityKeys.add("tmdb:$it") }
        ids.tvdb?.takeIf(String::isNotBlank)?.let { externalIdentityKeys.add("tvdb:${it.caseInsensitiveIdentity()}") }
        ids.mal?.let { externalIdentityKeys.add("mal:$it") }
        ids.anidb?.let { externalIdentityKeys.add("anidb:$it") }
        ids.anilist?.let { externalIdentityKeys.add("anilist:$it") }
        ids.kitsu?.let { externalIdentityKeys.add("kitsu:$it") }
    }
    return SimklHiddenContentIndex(
        snapshot = this,
        animeIdPreference = animeIdPreference,
        canonicalContentIds = canonicalContentIds,
        canonicalIdentityKeys = canonicalContentIds.mapTo(mutableSetOf(), String::caseInsensitiveIdentity),
        externalIdentityKeys = externalIdentityKeys,
    ).also { hiddenContentIndexCache.value = it }
}

private data class SimklHiddenContentIndex(
    val snapshot: SimklSyncSnapshot,
    val animeIdPreference: SimklAnimeIdPreference,
    val canonicalContentIds: Set<String>,
    val canonicalIdentityKeys: Set<String>,
    val externalIdentityKeys: Set<String>,
) {
    fun contains(contentId: String): Boolean {
        if (contentId.caseInsensitiveIdentity() in canonicalIdentityKeys) return true
        val ids = parseTrackingExternalIds(contentId)
        val key = when {
            ids.simkl != null -> "simkl:${ids.simkl}"
            !ids.imdb.isNullOrBlank() -> "imdb:${ids.imdb.caseInsensitiveIdentity()}"
            ids.tmdb != null -> "tmdb:${ids.tmdb}"
            !ids.tvdb.isNullOrBlank() -> "tvdb:${ids.tvdb.caseInsensitiveIdentity()}"
            ids.mal != null -> "mal:${ids.mal}"
            ids.anidb != null -> "anidb:${ids.anidb}"
            ids.anilist != null -> "anilist:${ids.anilist}"
            ids.kitsu != null -> "kitsu:${ids.kitsu}"
            else -> return false
        }
        return key in externalIdentityKeys
    }
}

private fun String.caseInsensitiveIdentity(): String = buildString(length) {
    this@caseInsensitiveIdentity.forEach { character ->
        append(character.uppercaseChar().lowercaseChar())
    }
}

internal fun SimklListStatus?.hidesContinueWatching(): Boolean =
    this == SimklListStatus.ON_HOLD || this == SimklListStatus.DROPPED

private fun WatchedItem.supersedes(progress: WatchProgressEntry): Boolean {
    if (!type.equals(progress.contentType, ignoreCase = true)) return false
    if (season != progress.seasonNumber || episode != progress.episodeNumber) return false
    val sameProviderItem = trackingProviderItemId
        ?.takeIf(String::isNotBlank)
        ?.equals(progress.trackingProviderItemId, ignoreCase = true) == true
    val sameContent = id.equals(progress.parentMetaId, ignoreCase = true)
    return (sameProviderItem || sameContent) && markedAtEpochMs >= progress.lastUpdatedEpochMs
}

private fun SimklLibraryEntry.hidesPlayback(progress: WatchProgressEntry): Boolean {
    if (
        !matchesContent(
            contentId = progress.parentMetaId,
            trackingProviderItemId = progress.trackingProviderItemId,
        )
    ) {
        return false
    }
    return when {
        status.hidesContinueWatching() -> true
        status == SimklListStatus.COMPLETED ->
            parseSimklUtcEpochMs(lastWatchedAt)?.let { completedAt ->
                completedAt >= progress.lastUpdatedEpochMs
            } == true
        else -> false
    }
}

private fun SimklLibraryEntry.matchesContent(
    contentId: String,
    trackingProviderItemId: String?,
): Boolean {
    val providerItemId = media?.simklTrackingProviderItemId()
    val sameProviderItem = providerItemId != null &&
        providerItemId.equals(trackingProviderItemId, ignoreCase = true)
    return sameProviderItem || matchesContentId(contentId)
}
