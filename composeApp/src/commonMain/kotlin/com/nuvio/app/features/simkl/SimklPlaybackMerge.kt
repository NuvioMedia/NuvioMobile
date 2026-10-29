package com.nuvio.app.features.simkl

/**
 * A fetched playback list with the rows the app recorded itself kept when they are newer.
 *
 * Simkl answers a stop or a pause right away, but it publishes the new position on `/sync/playback`
 * a moment later. The read that follows the user's own write can therefore still describe the
 * previous viewing of the same episode, and taking it sends the next resume back to a position the
 * user has already passed: this is how an episode stopped at 83 % resumed at 88 %.
 *
 * Only rows both sides know about are compared, and the account stays authoritative for everything
 * else: a playback it dropped is a playback that is over.
 */
internal fun mergeFetchedPlayback(
    fetched: List<SimklPlaybackSession>,
    held: List<SimklPlaybackSession>,
): List<SimklPlaybackSession> {
    if (held.isEmpty()) return fetched
    val fetchedByKey = fetched.mapNotNull { session ->
        session.playbackKey()?.let { key -> key to session }
    }.toMap()
    val kept = held.filter { local ->
        val key = local.playbackKey() ?: return@filter false
        val incoming = fetchedByKey[key] ?: return@filter false
        (local.playedAtEpochMs() ?: 0L) > (incoming.playedAtEpochMs() ?: 0L)
    }
    if (kept.isEmpty()) return fetched
    val keptKeys = kept.mapNotNullTo(mutableSetOf()) { session -> session.playbackKey() }
    return fetched.filterNot { session -> session.playbackKey() in keptKeys } + kept
}

/**
 * What a playback row describes: the show or movie, plus the episode when there is one. Two rows of
 * the same show are two playbacks, so the episode has to be part of the identity.
 */
private fun SimklPlaybackSession.playbackKey(): String? {
    val media = media ?: return null
    val contentId = media.ids.simklIdValue()
        ?: media.ids.idValue("imdb")
        ?: media.ids.idValue("tvdb")
        ?: media.title
        ?: return null
    val season = episode?.tvdbSeason ?: episode?.season ?: -1
    val number = episode?.tvdbNumber ?: episode?.number ?: -1
    return "$contentId|$season|$number"
}

/** When the account says this playback was last touched. */
private fun SimklPlaybackSession.playedAtEpochMs(): Long? =
    (pausedAt ?: watchedAt)?.let(::parseSimklUtcEpochMs)
