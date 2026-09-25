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

        val watched = details.userStates
            .filter { it.played }
            .filterNot { WatchedRepository.isWatched(meta.id, meta.type, it.season.takeIf { isSeries }, it.episode.takeIf { isSeries }) }
        watched.forEach { state ->
            WatchedRepository.markWatchedFromPlaybackCompletion(
                item = WatchedItem(
                    id = meta.id,
                    type = meta.type,
                    name = meta.name,
                    poster = meta.poster,
                    season = state.season.takeIf { isSeries },
                    episode = state.episode.takeIf { isSeries },
                    videoId = state.videoId.takeIf { isSeries },
                    markedAtEpochMs = state.lastPlayedEpochMs ?: 0L,
                ),
                syncRemote = false,
            )
        }
    }

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
