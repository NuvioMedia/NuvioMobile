package com.nuvio.app.features.home

import com.nuvio.app.features.watchprogress.CachedNextUpItem
import com.nuvio.app.features.watchprogress.parseReleaseDateToEpochMs
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CachedNextUpReleaseTimeZoneTest {
    @Test
    fun localTimestampsFollowTimeZoneChangesWhileAbsoluteDatesStayFixed() {
        val originalTimeZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val local = CachedNextUpRelease(item("2026-09-15T12:30:00"))
            val absolute = CachedNextUpRelease(item("2026-09-15T12:30:00Z"))
            val date = CachedNextUpRelease(item("2026-09-15"))
            val localEpoch = local.epochMs()
            val absoluteEpoch = absolute.epochMs()
            val dateEpoch = date.epochMs()

            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))

            assertNotEquals(localEpoch, local.epochMs())
            assertEquals(parseReleaseDateToEpochMs(local.item.released), local.epochMs())
            assertEquals(absoluteEpoch, absolute.epochMs())
            assertEquals(dateEpoch, date.epochMs())
        } finally {
            TimeZone.setDefault(originalTimeZone)
        }
    }

    private fun item(released: String) = CachedNextUpItem(
        contentId = "show",
        contentType = "series",
        name = "Show",
        videoId = "show:1:2",
        released = released,
        lastWatched = 1L,
        sortTimestamp = 1L,
    )
}
