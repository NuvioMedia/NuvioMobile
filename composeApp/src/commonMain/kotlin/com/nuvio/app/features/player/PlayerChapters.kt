package com.nuvio.app.features.player

import androidx.compose.runtime.Immutable

/** A chapter of the playing file, as its container lists it. */
@Immutable
data class PlayerChapter(
    val startMs: Long,
    val title: String?,
)

internal object PlayerChapters {

    /** Going back within this much of a chapter's start goes to the chapter before, as in mpv. */
    const val PREVIOUS_RESTART_THRESHOLD_MS = 3_000L

    /**
     * Sorted, one per start time, and only when there are at least two: a lone chapter (often a
     * single "Chapter 1" at 0) has nothing to skip to.
     */
    fun normalize(chapters: List<PlayerChapter>): List<PlayerChapter> {
        val sorted = chapters
            .filter { it.startMs >= 0L }
            .sortedBy { it.startMs }
            .distinctBy { it.startMs }
        return if (sorted.size < 2) emptyList() else sorted
    }

    /** Index of the chapter playing at [positionMs], or -1 before the first one starts. */
    fun indexAt(chapters: List<PlayerChapter>, positionMs: Long): Int =
        chapters.indexOfLast { it.startMs <= positionMs }

    fun nextStartMs(chapters: List<PlayerChapter>, positionMs: Long): Long? =
        chapters.firstOrNull { it.startMs > positionMs }?.startMs

    /** The current chapter's start, or the one before when [positionMs] is right after that start. */
    fun previousStartMs(chapters: List<PlayerChapter>, positionMs: Long): Long? {
        val index = indexAt(chapters, positionMs)
        if (index < 0) return null
        val current = chapters[index].startMs
        if (positionMs - current > PREVIOUS_RESTART_THRESHOLD_MS || index == 0) return current
        return chapters[index - 1].startMs
    }
}
