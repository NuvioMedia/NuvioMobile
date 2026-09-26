package com.nuvio.app.features.servers

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository

internal object ServerUserStateProjection {
    fun apply(details: ServerItemDetails) {
        val meta = details.meta
        val ref = ServerItemRef.parse(meta.id) ?: return
        val connection = ServerRepository.connection(ref.connectionId) ?: return
        val label = ServerRepository.sourceLabel(connection)
        val isSeries = meta.type == ServerMediaKind.SERIES.contentType

        WatchProgressRepository.applyExternalProgress(
            details.userStates
                .filter { !it.played && it.positionMs > 0L && it.durationMs > 0L && it.lastPlayedEpochMs != null }
                .map { state -> state.toEntry(meta, isSeries, label, connection.id) },
        )

        val (played, unplayed) = details.userStates
            .filter { !isSeries || (it.season != null && it.episode != null) }
            .partition { it.played }
        WatchedRepository.markWatchedLocally(
            played.filterNot { it.isWatched(meta, isSeries) }.map { it.toWatchedItem(meta, isSeries) },
        )
        WatchedRepository.unmarkWatchedLocally(
            unplayed.filter { it.isWatched(meta, isSeries) }.map { it.toWatchedItem(meta, isSeries) },
        )
    }

    private fun ServerUserState.isWatched(meta: MetaDetails, isSeries: Boolean): Boolean =
        WatchedRepository.isWatched(meta.id, meta.type, season.takeIf { isSeries }, episode.takeIf { isSeries })

    private fun ServerUserState.toWatchedItem(meta: MetaDetails, isSeries: Boolean): WatchedItem = WatchedItem(
        id = meta.id,
        type = meta.type,
        name = meta.name,
        poster = meta.poster,
        season = season.takeIf { isSeries },
        episode = episode.takeIf { isSeries },
        videoId = videoId.takeIf { isSeries },
        markedAtEpochMs = lastPlayedEpochMs ?: 0L,
    )

    private fun ServerUserState.toEntry(
        meta: MetaDetails,
        isSeries: Boolean,
        label: String,
        connectionId: String,
    ): WatchProgressEntry = WatchProgressEntry(
        contentType = meta.type,
        parentMetaId = meta.id,
        parentMetaType = meta.type,
        videoId = videoId,
        title = meta.name,
        logo = meta.logo,
        poster = meta.poster,
        background = meta.background,
        seasonNumber = season.takeIf { isSeries },
        episodeNumber = episode.takeIf { isSeries },
        episodeTitle = title.takeIf { isSeries },
        lastPositionMs = positionMs,
        durationMs = durationMs,
        lastUpdatedEpochMs = lastPlayedEpochMs ?: 0L,
        providerName = label,
        providerAddonId = "server:$connectionId",
    )
}
