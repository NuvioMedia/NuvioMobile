package com.nuvio.app.features.livetv.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaylistFilterTest {
    @Test
    fun aSavedPlaylistThatStillExistsIsUsed() {
        assertEquals("b", PlaylistFilter.effective("b", listOf("a", "b")))
    }

    @Test
    fun aMissingOrUnsetPlaylistMeansAllPlaylists() {
        assertNull(PlaylistFilter.effective(null, listOf("a", "b")))
        assertNull(PlaylistFilter.effective("gone", listOf("a", "b")))
        assertNull(PlaylistFilter.effective("a", emptyList()))
    }
}
