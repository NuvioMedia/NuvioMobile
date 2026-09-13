package com.nuvio.app.features.details

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RandomEpisodePickerTest {
    private fun episode(id: String, season: Int = 1, number: Int = 1, available: Boolean = true) =
        MetaVideo(id = id, title = id, season = season, episode = number, available = available, released = "2020-01-01")

    @Test
    fun excludesUnavailableSeasonsAndPlayedEpisodes() {
        val videos = listOf(episode("a"), episode("b", number = 2), episode("missing", season = 2, available = false))
        assertEquals("b", RandomEpisodePicker.pick(videos, setOf("a"), random = Random(0))?.id)
    }

    @Test
    fun resetsToPlayableCurrentCycleWhenAllEpisodesWerePlayed() {
        val videos = listOf(episode("a"), episode("b", number = 2))
        assertTrue(RandomEpisodePicker.pick(videos, setOf("a", "b"), currentVideoId = "a", random = Random(0)) != null)
    }

    @Test
    fun sessionHistoryIsIsolatedByContent() {
        ContinuousShuffleSession.start("show-a", "a")
        assertEquals(setOf("a"), ContinuousShuffleSession.history("show-a"))
        assertTrue(ContinuousShuffleSession.history("show-b").isEmpty())
    }
}
