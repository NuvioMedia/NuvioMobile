package com.nuvio.app.features.details

enum class EpisodeRatingsVisibility {
    SHOW_ALL,
    HIDE_EPISODES,
    HIDE_UNWATCHED_EPISODES;

    val showRatings: Boolean
        get() = this != HIDE_EPISODES

    fun showRating(isWatched: Boolean): Boolean = when (this) {
        SHOW_ALL -> true
        HIDE_EPISODES -> false
        HIDE_UNWATCHED_EPISODES -> isWatched
    }

    companion object {
        fun parse(value: String): EpisodeRatingsVisibility =
            entries.firstOrNull { it.name == value } ?: SHOW_ALL
    }
}
