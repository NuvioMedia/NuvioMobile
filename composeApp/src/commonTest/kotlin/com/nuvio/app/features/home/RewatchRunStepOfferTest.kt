package com.nuvio.app.features.home

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.tracking.RewatchRunPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RewatchRunStepOfferTest {
    private val run = RewatchRunPosition(
        contentId = "show",
        seasonNumber = 1,
        episodeNumber = 2,
        markedAtEpochMs = 0L,
    )

    @Test
    fun `hidden unaired step is skipped and the first aired one is offered`() {
        val details = details(
            episode(season = 1, number = 3, released = "2026-10-30"),
            episode(season = 1, number = 4, released = "2026-10-01"),
        )

        val step = details.offerableEpisodeAfterRun(run, showUnairedNextUp = false, todayIsoDate = "2026-10-19")

        assertEquals(4, step?.episode)
    }

    @Test
    fun `allowed unaired step inside the season is offered`() {
        val details = details(episode(season = 1, number = 3, released = "2026-10-30"))

        val step = details.offerableEpisodeAfterRun(run, showUnairedNextUp = true, todayIsoDate = "2026-10-19")

        assertEquals(3, step?.episode)
    }

    @Test
    fun `a next season is offered only inside the seven day window`() {
        val near = details(episode(season = 2, number = 1, released = "2026-10-25"))
        assertEquals(1, near.offerableEpisodeAfterRun(run, showUnairedNextUp = true, todayIsoDate = "2026-10-19")?.episode)

        val far = details(episode(season = 2, number = 1, released = "2027-01-10"))
        assertNull(far.offerableEpisodeAfterRun(run, showUnairedNextUp = true, todayIsoDate = "2026-10-19"))
    }

    private fun details(vararg videos: MetaVideo) = MetaDetails(
        id = "show",
        type = "series",
        name = "Show",
        videos = videos.toList(),
    )

    private fun episode(season: Int, number: Int, released: String) = MetaVideo(
        id = "s${season}e${number}",
        title = "Episode $number",
        season = season,
        episode = number,
        released = released,
    )
}
