package com.nuvio.app.features.shuffle

import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.Serializable

@Serializable
internal data class EpisodeShuffleSettings(
    val enabled: Boolean = false,
    val includeWatched: Boolean = false
)

internal enum class ShuffleSurface { DETAIL, HOME, PLAYBACK }

internal class EpisodeShuffle {
    private data class Key(
        val profileId: Int,
        val contentId: String,
        val surface: ShuffleSurface,
        val includeWatched: Boolean
    )

    private class Session {
        var picker: RandomEpisodePicker? = null
        var selected: MetaVideo? = null
        var current: Pair<Int, Int>? = null
        var visit: Long = 0
    }

    private val sessions = linkedMapOf<Key, Session>()
    private val lock = SynchronizedObject()

    fun select(
        profileId: Int,
        contentId: String,
        videos: List<MetaVideo>,
        includeWatched: Boolean,
        watched: Set<Pair<Int, Int>> = emptySet(),
        progress: Map<Pair<Int, Int>, WatchProgressEntry> = emptyMap(),
        surface: ShuffleSurface,
        current: Pair<Int, Int>? = null,
        visit: Long = 0,
        preferredVideoId: String? = null
    ): MetaVideo? = synchronized(lock) {
        val key = Key(profileId, contentId, surface, includeWatched)
        val session = sessions.remove(key) ?: Session()
        sessions[key] = session
        while (sessions.size > 96) sessions.remove(sessions.keys.first())
        val picker = RandomEpisodePicker(contentId, videos, watched, progress)
        picker.inheritHistoryFrom(session.picker)
        if (current != null && (session.picker == null || session.current != current)) picker.recordPlayed(current)
        session.picker = picker
        if (session.current != current || session.visit != visit) session.selected = null
        session.current = current
        session.visit = visit
        session.selected = session.selected?.let { picker.find(it.id, includeWatched, current) }
            ?: preferredVideoId?.let { picker.find(it, includeWatched, current) }
            ?: picker.pick(includeWatched, current, consumeSelection = surface != ShuffleSurface.PLAYBACK || current == null)
        session.selected
    }

    fun clear() = synchronized(lock) { sessions.clear() }

    fun clearSelection(profileId: Int, contentId: String, surface: ShuffleSurface) = synchronized(lock) {
        sessions.filterKeys {
            it.profileId == profileId && it.contentId == contentId && it.surface == surface
        }.values.forEach { it.selected = null }
    }
}
