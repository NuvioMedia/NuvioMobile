package com.nuvio.app.features.servers

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerUserStateProjectionTest {
    private val id = ServerItemRef("cfake", "7").encode()

    @AfterTest
    fun tearDown() {
        WatchProgressRepository.clearProgress(id)
        removeFakeServer()
        WatchProgressRepository.clearLocalState()
    }

    @Test
    fun importsServerProgressOnlyWhenEnabled() {
        val connection = installFakeServer()
        val details = ServerItemDetails(
            meta = MetaDetails(id = id, type = "movie", name = "Item 7"),
            externalIds = TrackingExternalIds(),
            userStates = listOf(
                ServerUserState(id, positionMs = 30_000L, durationMs = 60_000L, played = false, lastPlayedEpochMs = 5_000L),
            ),
        )

        ServerUserStateProjection.apply(details)
        assertNull(WatchProgressRepository.progressForVideo(id))

        ServerRepository.setImportWatchState(connection.id, true)
        ServerUserStateProjection.apply(details)
        assertEquals(30_000L, WatchProgressRepository.progressForVideo(id)?.lastPositionMs)
    }
}
