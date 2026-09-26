package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerServerAudioTest {
    private val tracks = listOf(
        AudioTrack(index = 1, id = "1", label = "English - AC3 - 5.1 - Default", language = "eng", isSelected = true),
        AudioTrack(index = 2, id = "2", label = "Japanese - AAC - Stereo", language = "jpn"),
    )

    @Test
    fun titlePreferenceWinsOverLanguageSettings() {
        val preference = PersistedPlayerTrackPreference(audioLanguage = "jpn", audioName = "Japanese - AAC - Stereo")
        assertEquals(2, preferredServerAudioIndex(tracks, preference, targets = listOf("en")))
    }

    @Test
    fun fallsBackToPreferredLanguages() {
        assertEquals(2, preferredServerAudioIndex(tracks, preference = null, targets = listOf("fr", "ja", "en")))
    }

    @Test
    fun keepsServerDefaultWithoutAMatch() {
        assertNull(preferredServerAudioIndex(tracks, preference = null, targets = listOf("fr")))
    }
}
