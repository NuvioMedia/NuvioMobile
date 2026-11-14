package com.nuvio.app.features.player

import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_loading_buffering
import nuvio.composeapp.generated.resources.player_loading_building
import nuvio.composeapp.generated.resources.player_loading_starting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerLoadingStatusTest {
    private val opening = PlayerPlaybackSnapshot(isLoading = true, isOpening = true)
    private val buffering = PlayerPlaybackSnapshot(isLoading = true, isOpening = false)

    @Test
    fun `loading status defaults on like TV`() {
        assertTrue(PlayerSettingsUiState().showPlayerLoadingStatus)
    }

    @Test
    fun `disabled status hides every initialization step`() {
        for (controllerReady in listOf(false, true)) {
            for (snapshot in listOf(PlayerPlaybackSnapshot(), opening, buffering)) {
                assertNull(playerLoadingStatusResource(false, controllerReady, snapshot))
            }
        }
    }

    @Test
    fun `status follows the player start-up steps`() {
        assertEquals(Res.string.player_loading_building, playerLoadingStatusResource(true, false, opening))
        assertEquals(Res.string.player_loading_starting, playerLoadingStatusResource(true, true, opening))
        assertEquals(Res.string.player_loading_buffering, playerLoadingStatusResource(true, true, buffering))
    }

    @Test
    fun `a fresh snapshot is still opening the stream`() {
        assertEquals(
            Res.string.player_loading_starting,
            playerLoadingStatusResource(true, true, PlayerPlaybackSnapshot()),
        )
    }

    @Test
    fun `a snapshot that is not loading is not opening`() {
        assertFalse(PlayerPlaybackSnapshot(isLoading = false).isOpening)
    }
}
