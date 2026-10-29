package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerAudioRestoreTest {
    @Test
    fun reusedIdDoesNotRestoreAnotherLanguage() {
        val tracks = listOf(
            AudioTrack(0, "1", "English", "en"),
            AudioTrack(1, "2", "Japanese", "ja"),
        )

        assertEquals(1, findPersistedAudioTrackIndex(tracks, PersistedPlayerTrackPreference(
            audioTrackId = "1", audioLanguage = "ja", audioName = "Japanese",
        )))
    }

    @Test
    fun trackNameKeepsSameLanguageChoiceWhenIdsChange() {
        val tracks = listOf(
            AudioTrack(0, "2", "Japanese stereo", "ja"),
            AudioTrack(1, "3", "Japanese surround", "ja"),
        )

        assertEquals(1, findPersistedAudioTrackIndex(tracks, PersistedPlayerTrackPreference(
            audioTrackId = "2", audioLanguage = "ja", audioName = "Japanese surround",
        )))
    }

    @Test
    fun nameMatchPrecedesFirstLanguageMatch() {
        val tracks = listOf(
            AudioTrack(0, "1", "Japanese stereo", "ja"),
            AudioTrack(1, "2", "Japanese surround AAC", "jpn"),
        )

        assertEquals(1, findPersistedAudioTrackIndex(tracks, PersistedPlayerTrackPreference(
            audioLanguage = "ja", audioName = "Japanese surround",
        )))
    }

    @Test
    fun changedNamesFallBackToTheRememberedLanguageVariant() {
        val tracks = listOf(
            AudioTrack(0, "1", "Portuguese Portugal", "pt"),
            AudioTrack(1, "2", "Portuguese Brazil", "pt"),
        )

        assertEquals(1, findPersistedAudioTrackIndex(tracks, PersistedPlayerTrackPreference(
            audioLanguage = "pt", audioName = "Brazilian dub surround",
        )))
    }

    @Test
    fun unavailableSavedLanguageDoesNotRestoreReusedIdOrName() {
        val tracks = listOf(AudioTrack(0, "1", "Main audio", "en"))

        assertEquals(-1, findPersistedAudioTrackIndex(tracks, PersistedPlayerTrackPreference(
            audioTrackId = "1", audioLanguage = "ja", audioName = "Main audio",
        )))
    }

    @Test
    fun idOnlyPreferenceStillRestoresTheTrack() {
        val tracks = listOf(AudioTrack(4, "5", "Unknown language"))

        assertEquals(4, findPersistedAudioTrackIndex(tracks, PersistedPlayerTrackPreference(audioTrackId = "5")))
    }
}
