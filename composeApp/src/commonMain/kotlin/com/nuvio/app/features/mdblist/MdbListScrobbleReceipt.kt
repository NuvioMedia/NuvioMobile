package com.nuvio.app.features.mdblist

import com.nuvio.app.features.tracking.TrackingScrobbleAction
import kotlin.time.Instant
import io.ktor.http.fromHttpToGmtDate

internal data class MdbListScrobbleReceipt(
    val target: MdbListMutationTarget,
    val action: TrackingScrobbleAction,
    val progress: Float,
    val timestamp: String,
    val playbackId: Long?
) {
    val isWatched: Boolean
        get() = action != TrackingScrobbleAction.START && progress >= MdbListProgressProjection.COMPLETION_PERCENT
}

internal fun decodeMdbListScrobbleReceipt(
    response: MdbListHttpResponse,
    target: MdbListMutationTarget,
    action: TrackingScrobbleAction,
    nowEpochMs: Long
): MdbListScrobbleReceipt {
    val body = mdbListResponseElement(response.body).objectValue()
    val acceptedActions = if (action == TrackingScrobbleAction.START) setOf("start") else setOf("pause", "stop", "scrobble")
    if (body.text("action") !in acceptedActions) throw MdbListDecodingException()
    val progress = body.text("progress")?.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..100f }
        ?: throw MdbListDecodingException()
    val episode = body.objectValue("episode")
    val parent = body.objectValue(if (target.type == MdbListItemType.MOVIE) "movie" else "show")
        ?: episode?.objectValue("show")
    val media = parent?.let(::decodeMdbListMedia)?.also {
        if (!it.ids.matches(target.media.ids)) throw MdbListDecodingException()
    }?.merged(target.media) ?: target.media
    val resolved = target.copy(
        media = media,
        season = episode?.integer("season") ?: target.season,
        episode = episode?.integer("number", "episode") ?: target.episode,
        episodeTitle = episode?.text("title", "name") ?: target.episodeTitle,
        episodeTmdbId = episode?.objectValue("ids")?.number("tmdb", "tmdbid") ?: target.episodeTmdbId,
        episodeTvdbId = episode?.objectValue("ids")?.number("tvdb", "tvdbid") ?: target.episodeTvdbId
    )
    if (resolved.type == MdbListItemType.EPISODE && (resolved.season == null || resolved.season < 0 ||
        resolved.episode == null || resolved.episode < 1)) throw MdbListDecodingException()
    val timestamp = body.timestamp("watched_at", "paused_at")
        ?: body.timestamp("started_at").takeIf { action == TrackingScrobbleAction.START }
        ?: response.header("Date")?.let {
        runCatching { Instant.fromEpochMilliseconds(it.fromHttpToGmtDate().timestamp).toString() }.getOrNull()
    } ?: Instant.fromEpochMilliseconds(nowEpochMs).toString()
    return MdbListScrobbleReceipt(resolved, action, progress, timestamp, body.number("id")?.takeIf { it > 0 })
}

internal fun MdbListSyncSnapshot.applyScrobble(receipt: MdbListScrobbleReceipt): MdbListSyncSnapshot {
    val target = receipt.target
    val remainingPlayback = playback.filterNot(target::matches)
    if (receipt.action == TrackingScrobbleAction.START) return copy(playback = remainingPlayback)
    val watched = if (receipt.isWatched) this.watched.filterNot(target::matches) + target.watched(receipt.timestamp) else this.watched
    val playback = if (receipt.isWatched) remainingPlayback else remainingPlayback + MdbListPlayback(
        id = receipt.playbackId ?: this.playback.firstOrNull(target::matches)?.id,
        type = target.type,
        media = target.media,
        progress = receipt.progress,
        updatedAt = receipt.timestamp,
        season = target.season,
        episode = target.episode,
        episodeTitle = target.episodeTitle,
        episodeTmdbId = target.episodeTmdbId,
        episodeTvdbId = target.episodeTvdbId
    )
    return copy(watched = watched, playback = playback).normalizeMedia()
}
