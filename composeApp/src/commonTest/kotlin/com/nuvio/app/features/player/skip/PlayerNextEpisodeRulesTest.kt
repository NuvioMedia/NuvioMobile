package com.nuvio.app.features.player.skip

import com.nuvio.app.features.details.MetaVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerNextEpisodeRulesTest {
    @Test
    fun resolvesAiredEpisodeAtItsOriginalIndex() {
        val episodes = listOf(
            MetaVideo(id = "s1e2", title = "Episode 2", released = "2020-01-02"),
            MetaVideo(id = "s1e1", title = "Episode 1", released = "2020-01-01"),
        )

        assertEquals("s1e1", PlayerNextEpisodeRules.resolvePlayableEpisodeAtIndex(episodes, 1)?.id)
    }

    @Test
    fun rejectsInvalidAndUnairedEpisodeIndices() {
        val episodes = listOf(
            MetaVideo(id = "future", title = "Future", released = "2999-01-01"),
        )

        assertNull(PlayerNextEpisodeRules.resolvePlayableEpisodeAtIndex(episodes, -1))
        assertNull(PlayerNextEpisodeRules.resolvePlayableEpisodeAtIndex(episodes, 1))
        assertNull(PlayerNextEpisodeRules.resolvePlayableEpisodeAtIndex(episodes, 0))
    }
}
