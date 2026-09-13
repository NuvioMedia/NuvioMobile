package com.nuvio.app.features.details

import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
import kotlin.random.Random

/** Shared eligibility and selection rules for continuous series shuffle. */
object RandomEpisodePicker {
    fun eligibleEpisodes(videos: List<MetaVideo>): List<MetaVideo> {
        val candidates = videos.filter { (it.season ?: 0) > 0 && it.episode != null }
        val unavailableSeasons = candidates.groupBy { it.season }
            .filter { (_, episodes) ->
                val first = episodes.minByOrNull { it.episode ?: Int.MAX_VALUE }
                first == null ||
                    !first.available ||
                    PlayerNextEpisodeRules.hasEpisodeAired(first.released) == false
            }
            .keys
        return candidates
            .filter { it.season !in unavailableSeasons }
            .filter { it.available && PlayerNextEpisodeRules.hasEpisodeAired(it.released) }
    }

    fun pick(
        videos: List<MetaVideo>,
        excludedVideoIds: Set<String> = emptySet(),
        currentVideoId: String? = null,
        canPlay: (MetaVideo) -> Boolean = { true },
        random: Random = Random.Default,
    ): MetaVideo? {
        val playable = eligibleEpisodes(videos).filter(canPlay)
        val unseen = playable.filter { it.id !in excludedVideoIds }
        val pool = unseen.ifEmpty { playable.filter { it.id != currentVideoId }.ifEmpty { playable } }
        return pool.takeIf { it.isNotEmpty() }?.random(random)
    }
}

/** Process-local history for one shuffle cycle; it is deliberately not watch history. */
object ContinuousShuffleSession {
    private val playedByContentId = mutableMapOf<String, MutableSet<String>>()

    @Synchronized
    fun start(contentId: String, firstVideoId: String) {
        playedByContentId[contentId] = mutableSetOf(firstVideoId)
    }

    @Synchronized
    fun record(contentId: String, videoId: String) {
        playedByContentId.getOrPut(contentId) { mutableSetOf() }.add(videoId)
    }

    @Synchronized
    fun history(contentId: String): Set<String> = playedByContentId[contentId]?.toSet().orEmpty()

    @Synchronized
    fun restartCycle(contentId: String, currentVideoId: String?) {
        playedByContentId[contentId] = currentVideoId?.let { mutableSetOf(it) } ?: mutableSetOf()
    }
}
