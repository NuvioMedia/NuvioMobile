package com.nuvio.app.features.home

import com.nuvio.app.features.watchprogress.CachedNextUpItem
import com.nuvio.app.features.watchprogress.calculateReleaseAlertState
import com.nuvio.app.features.watchprogress.parseReleaseDateToEpochMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CachedNextUpReleaseTest {
    @Test
    fun parsedEpochsMatchExistingReleaseParsing() {
        listOf(
            null,
            "",
            "not a date",
            "2026-09-15",
            "2026-09-15T12:30:00",
            "2026-09-15T12:30:00.123",
            "2026-09-15T12:30:00Z",
            " 2026-09-15T12:30:00.123Z ",
            "2026-09-15T12:30:00+05:30",
            "2026-09-15T12:30:00-0430",
            "released on 2026-09-15",
            "2026-13-45T25:00:00",
        ).forEach { released ->
            val cached = CachedNextUpRelease(item(released))
            repeat(2) {
                assertEquals(parseReleaseDateToEpochMs(released), cached.epochMs(), released)
            }
        }
    }

    @Test
    fun reusedEpochStillCrossesExactReleaseAndAlertExpiry() {
        val released = "2026-09-15T12:30:00Z"
        val epoch = requireNotNull(parseReleaseDateToEpochMs(released))
        val item = item(released).copy(lastWatched = epoch - 1L, seedSeason = 1, season = 2)
        val cached = CachedNextUpRelease(item)

        assertFalse(cachedNextUpHasAired(item, epoch - 1L, cached.epochMs()))
        assertTrue(cachedNextUpHasAired(item, epoch, cached.epochMs()))
        listOf(
            epoch - 1L to false,
            epoch to true,
            epoch + 60L * 24 * 60 * 60 * 1_000 - 1L to true,
            epoch + 60L * 24 * 60 * 60 * 1_000 to false,
        ).forEach { (now, expectedAlert) ->
            val state = calculateReleaseAlertState(
                seedLastUpdatedEpochMs = item.lastWatched,
                seedSeasonNumber = item.seedSeason,
                nextSeasonNumber = item.season,
                releasedIso = item.released,
                releaseEpochMs = cached.epochMs(),
                nowEpochMs = now,
            )
            assertEquals(expectedAlert, state.isReleaseAlert)
            assertEquals(expectedAlert, state.isNewSeasonRelease)
        }
    }

    @Test
    fun malformedReleaseKeepsAiredFallbackWithoutCreatingAnAlert() {
        listOf(true, false).forEach { hasAired ->
            val item = item("unknown").copy(hasAired = hasAired)
            val cached = CachedNextUpRelease(item)
            assertEquals(hasAired, cachedNextUpHasAired(item, 1_000L, cached.epochMs()))
            val state = calculateReleaseAlertState(
                seedLastUpdatedEpochMs = item.lastWatched,
                seedSeasonNumber = item.seedSeason,
                nextSeasonNumber = item.season,
                releasedIso = item.released,
                releaseEpochMs = cached.epochMs(),
                nowEpochMs = 1_000L,
            )
            assertFalse(state.isReleaseAlert)
            assertFalse(state.isNewSeasonRelease)
        }
    }

    private fun item(released: String?) = CachedNextUpItem(
        contentId = "show",
        contentType = "series",
        name = "Show",
        videoId = "show:1:2",
        released = released,
        lastWatched = 1L,
        sortTimestamp = 1L,
    )
}
