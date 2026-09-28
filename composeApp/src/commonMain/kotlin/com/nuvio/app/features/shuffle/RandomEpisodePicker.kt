package com.nuvio.app.features.shuffle

import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.filterUnavailableFutureSeasons
import com.nuvio.app.features.details.isReleasedBy
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.CurrentDateProvider

import kotlin.random.Random

internal class RandomEpisodePicker(
    private val contentId: String,
    videos: List<MetaVideo>,
    watchedEpisodes: Set<Pair<Int, Int>>,
    episodeProgress: Map<Pair<Int, Int>, WatchProgressEntry>,
    private val random: Random = Random.Default,
    todayIsoDate: String = CurrentDateProvider.todayIsoDate()
) {
    private val episodes = videos.filter { (it.season ?: 0) > 0 && it.episode != null }
        .filterUnavailableFutureSeasons(todayIsoDate)
        .filter { it.isReleasedBy(todayIsoDate) }
        .filter { it.id.isNotBlank() && (it.episode ?: 0) > 0 }
        .distinctBy { it.season to it.episode }
        .distinctBy { it.id }
    private val watchedKeys = watchedEpisodes + episodeProgress
        .filterValues { it.isEffectivelyCompleted }
        .keys
    private val unwatchedEpisodes = episodes.filterNot { isWatched(it) }
    private val shownIds = mutableSetOf<String>()
    private var lastPickedId: String? = null

    fun count(includeWatched: Boolean): Int = candidates(includeWatched).size

    fun isWatched(episode: MetaVideo): Boolean = (episode.season to episode.episode) in watchedKeys

    fun find(videoId: String, includeWatched: Boolean, current: Pair<Int, Int>? = null): MetaVideo? =
        candidates(includeWatched).firstOrNull { it.id == videoId && it.season to it.episode != current }

    fun inheritHistoryFrom(previous: RandomEpisodePicker?) {
        if (previous == null || previous.contentId != contentId) return
        shownIds.addAll(previous.shownIds)
        lastPickedId = previous.lastPickedId
    }

    fun recordPlayed(current: Pair<Int, Int>) {
        episodes.firstOrNull { it.season to it.episode == current }?.let { shownIds.add(it.id) }
    }

    fun pick(
        includeWatched: Boolean,
        current: Pair<Int, Int>? = null,
        consumeSelection: Boolean = true
    ): MetaVideo? {
        val candidates = candidates(includeWatched).filterNot { it.season to it.episode == current }
        if (candidates.isEmpty()) return null
        val unseen = candidates.filterNot { it.id in shownIds }
        val pool = unseen.ifEmpty {
            if (consumeSelection) shownIds.removeAll(candidates.map { it.id }.toSet()) else shownIds.clear()
            candidates.filterNot { it.id == lastPickedId }.ifEmpty { candidates }
        }
        return pool.random(random).also {
            if (consumeSelection) shownIds.add(it.id)
            lastPickedId = it.id
        }
    }

    private fun candidates(includeWatched: Boolean): List<MetaVideo> =
        if (includeWatched) episodes else unwatchedEpisodes
}
