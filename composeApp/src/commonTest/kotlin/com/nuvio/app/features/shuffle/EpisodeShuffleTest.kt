package com.nuvio.app.features.shuffle

import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.watchprogress.WatchProgressEntry

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.Test

class EpisodeShuffleTest {
    private val shuffle = EpisodeShuffle()
    private val episodes = (1..8).map { episode(it) }

    @Test
    fun `next selection stays stable as metadata and progress refresh`() {
        val first = select(current = 1 to 1)
        repeat(30) {
            assertEquals(first, select(current = 1 to 1))
        }
        val updated = episodes.map { it.copy(title = "Updated ${it.episode}") }
        assertEquals(first?.id, select(videos = updated, current = 1 to 1)?.id)
        assertEquals("Updated ${first?.episode}", select(videos = updated, current = 1 to 1)?.title)
    }

    @Test
    fun `all plays a complete cycle without repeating the current episode`() {
        var current = 1 to 1
        val played = mutableListOf(1)
        repeat(23) {
            val next = select(current = current)!!
            assertNotEquals(current, next.season to next.episode)
            played.add(next.episode!!)
            current = next.season!! to next.episode
        }
        played.chunked(8).forEach { assertEquals((1..8).toSet(), it.toSet()) }
        assertNotEquals(current, select(current = current).let { it?.season to it?.episode })
    }

    @Test
    fun `an explicitly chosen episode is honored before random continuation`() {
        val next = select(current = 1 to 6)!!
        assertNotEquals(6, next.episode)
        val afterManualChoice = select(current = 1 to 3)!!
        assertNotEquals(3, afterManualChoice.episode)
    }

    @Test
    fun `an unplayed next preview stays eligible after choosing a different episode`() {
        val catalogue = episodes.take(3)
        val preview = select(videos = catalogue, current = 1 to 1)!!
        val manual = catalogue.single { it.episode != 1 && it.id != preview.id }
        val next = select(videos = catalogue, current = manual.season!! to manual.episode!!)!!
        assertEquals(preview.id, next.id)
    }

    @Test
    fun `unwatched stops after the last eligible episode`() {
        val watched = (1..7).map { 1 to it }.toSet()
        assertEquals(8, select(includeWatched = false, watched = watched)?.episode)
        assertNull(select(includeWatched = false, watched = watched, current = 1 to 8))
        assertNull(select(includeWatched = false, watched = watched + (1 to 8)))
    }

    @Test
    fun `new completion invalidates an unwatched next target`() {
        val next = select(includeWatched = false)!!
        assertNotEquals(next, select(includeWatched = false, watched = setOf(next.season!! to next.episode!!)))
    }

    @Test
    fun `partial progress remains eligible and completed progress is excluded`() {
        val progress = mapOf(
            (1 to 1) to progress(1, 94f),
            (1 to 2) to progress(2, 80f)
        )
        assertEquals(2, select(videos = episodes.take(2), includeWatched = false, progress = progress)?.episode)
    }

    @Test
    fun `unavailable selection is replaced and an empty catalogue has no fallback`() {
        val next = select()!!
        val updated = episodes.map { if (it.id == next.id) it.copy(available = false) else it }
        assertNotEquals(next.id, select(videos = updated)?.id)
        assertNull(select(videos = emptyList()))
    }

    @Test
    fun `single episode cannot continue into itself`() {
        assertEquals(episodes.first(), select(videos = episodes.take(1)))
        assertNull(select(videos = episodes.take(1), current = 1 to 1))
    }

    @Test
    fun `home and detail browsing do not consume playback history`() {
        var current = 1 to 1
        val played = mutableSetOf(1)
        repeat(7) { visit ->
            repeat(4) { draw ->
                select(surface = ShuffleSurface.HOME, visit = (visit * 4 + draw).toLong())
                select(surface = ShuffleSurface.DETAIL, visit = (visit * 4 + draw).toLong())
            }
            val next = select(current = current)!!
            played.add(next.episode!!)
            current = next.season!! to next.episode
        }
        assertEquals((1..8).toSet(), played)
    }

    @Test
    fun `profiles shows and filters keep independent histories`() {
        val cycles = List(4) { mutableSetOf<Int>() }
        repeat(8) { visit ->
            cycles[0].add(select(visit = visit.toLong())!!.episode!!)
            cycles[1].add(select(profile = 2, visit = visit.toLong())!!.episode!!)
            cycles[2].add(select(show = "other", visit = visit.toLong())!!.episode!!)
            cycles[3].add(select(includeWatched = false, visit = visit.toLong())!!.episode!!)
        }
        cycles.forEach { assertEquals((1..8).toSet(), it) }
    }

    @Test
    fun `new visit advances selection while repeated emissions retain it`() {
        val first = select(surface = ShuffleSurface.HOME, visit = 1)
        assertEquals(first, select(surface = ShuffleSurface.HOME, visit = 1))
        assertNotEquals(first, select(surface = ShuffleSurface.HOME, visit = 2))
    }

    @Test
    fun `clearing a selection keeps the remaining bag intact`() {
        val picked = mutableSetOf<Int>()
        repeat(8) {
            picked.add(select()!!.episode!!)
            shuffle.clearSelection(1, "show", ShuffleSurface.PLAYBACK)
        }
        assertEquals((1..8).toSet(), picked)
    }

    @Test
    fun `specials malformed future and duplicate episodes are excluded`() {
        val invalid = listOf(
            episode(0), episode(2).copy(season = 0), episode(3).copy(available = false),
            episode(4).copy(released = "2999-01-01"), episode(5).copy(id = " "),
            episode(6).copy(season = null), episode(7).copy(episode = null)
        )
        val valid = episode(1)
        assertEquals(valid, select(videos = invalid + valid + valid.copy(id = "duplicate")))
    }

    @Test
    fun `late catalogue expansion preserves the displayed episode`() {
        val first = select(videos = episodes.take(2))
        assertEquals(first, select())
        val remaining = (1..7).map { select(visit = it.toLong())!!.id }
        assertFalse(first!!.id in remaining)
        assertEquals(7, remaining.toSet().size)
    }

    private fun select(
        videos: List<MetaVideo> = episodes,
        includeWatched: Boolean = true,
        watched: Set<Pair<Int, Int>> = emptySet(),
        progress: Map<Pair<Int, Int>, WatchProgressEntry> = emptyMap(),
        current: Pair<Int, Int>? = null,
        surface: ShuffleSurface = ShuffleSurface.PLAYBACK,
        visit: Long = 0,
        profile: Int = 1,
        show: String = "show"
    ) = shuffle.select(profile, show, videos, includeWatched, watched, progress, surface, current, visit)

    private fun episode(number: Int) = MetaVideo(
        id = "show:1:$number", title = "Episode $number", released = "2020-01-01",
        season = 1, episode = number,
    )

    private fun progress(number: Int, percent: Float) = WatchProgressEntry(
        contentType = "series", parentMetaId = "show", parentMetaType = "series",
        title = "Show", videoId = "show:1:$number", seasonNumber = 1, episodeNumber = number,
        lastPositionMs = 0L, durationMs = 0L, lastUpdatedEpochMs = 0L, progressPercent = percent,
    )
}
