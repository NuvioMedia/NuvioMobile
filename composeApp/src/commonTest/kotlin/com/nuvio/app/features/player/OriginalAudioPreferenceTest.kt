package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsUiState
import com.nuvio.app.features.tmdb.TmdbEnrichment
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.tmdb.TmdbSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OriginalAudioPreferenceTest {
    @Test
    fun tmdbDetailsAvailableBeforePlaybackSelectOriginalAudio() {
        val controller = RecordingController(audioTracks())
        val runtime = runtime(controller)
        runtime.metaUiState = MetaDetailsUiState(meta = enrichedMeta())

        runtime.refreshTracks()

        assertEquals(originalLanguage, runtime.metaUiState.meta?.language)
        reportRequestedOriginalSelection(runtime, controller)
        assertEquals(1, runtime.selectedAudioIndex)
        assertEquals(emptyList(), controller.audioSelections)
    }

    @Test
    fun launchLanguageSelectsOriginalAudioWithoutDetailsState() {
        val controller = RecordingController(audioTracks())
        val runtime = runtime(controller, contentLanguage = originalLanguage)

        runtime.refreshTracks()

        reportRequestedOriginalSelection(runtime, controller)
        assertEquals(1, runtime.selectedAudioIndex)
        assertEquals(emptyList(), controller.audioSelections)
    }

    @Test
    fun originalLanguageArrivingAfterTracksReplacesDeviceAudio() {
        val controller = RecordingController(audioTracks())
        val runtime = runtime(controller)
        runtime.metaUiState = MetaDetailsUiState(isLoading = true)
        runtime.refreshTracks()
        assertEquals(0, runtime.selectedAudioIndex)

        runtime.metaUiState = MetaDetailsUiState(meta = enrichedMeta())
        runtime.refreshTracks()

        reportRequestedOriginalSelection(runtime, controller)
        assertEquals(1, runtime.selectedAudioIndex)
        assertEquals(emptyList(), controller.audioSelections)
    }

    @Test
    fun originalTrackArrivingAfterInitialTrackScanIsSelected() {
        val controller = RecordingController(audioTracks().take(1))
        val runtime = runtime(controller)
        runtime.metaUiState = MetaDetailsUiState(meta = enrichedMeta())
        runtime.refreshTracks()
        assertEquals(0, runtime.selectedAudioIndex)

        controller.tracks = audioTracks()
        runtime.refreshTracks()

        reportRequestedOriginalSelection(runtime, controller)
        assertEquals(1, runtime.selectedAudioIndex)
        assertEquals(emptyList(), controller.audioSelections)
    }

    @Test
    fun unrelatedDetailsDoNotOverrideThePlayingTitleLanguage() {
        val controller = RecordingController(audioTracks())
        val runtime = runtime(controller, contentLanguage = originalLanguage)
        runtime.metaUiState = MetaDetailsUiState(
            meta = MetaDetails(
                id = "tt-unrelated-title",
                type = "movie",
                name = "Another title",
                language = deviceLanguage,
            ),
        )

        runtime.refreshTracks()

        reportRequestedOriginalSelection(runtime, controller)
        assertEquals(1, runtime.selectedAudioIndex)
        assertEquals(emptyList(), controller.audioSelections)
    }

    @Test
    fun threeLetterMpvTracksReceiveCanonicalTmdbPreference() {
        val tracks = listOf(
            AudioTrack(0, "1", "English dub", "eng", true),
            AudioTrack(1, "2", "Japanese", "jpn"),
        )
        val controller = RecordingController(tracks)
        val runtime = runtime(controller, contentLanguage = "ja")

        runtime.refreshTracks()

        assertEquals(listOf(listOf("ja")), controller.audioLanguagePreferences)
        assertEquals(emptyList(), controller.audioSelections)
    }

    @Test
    fun fetchedPlayerMetadataSuppliesTheOriginalLanguage() {
        val controller = RecordingController(audioTracks())
        val runtime = runtime(controller)
        runtime.metaUiState = MetaDetailsUiState(meta = enrichedMeta().copy(id = "another-title", language = deviceLanguage))
        runtime.playerMeta = enrichedMeta()

        runtime.refreshTracks()

        reportRequestedOriginalSelection(runtime, controller)
        assertEquals(1, runtime.selectedAudioIndex)
    }

    @Test
    fun countryInferenceDoesNotOverrideAnExplicitLaunchLanguage() {
        val controller = RecordingController(audioTracks())
        val runtime = runtime(controller, contentLanguage = originalLanguage)
        runtime.metaUiState = MetaDetailsUiState(meta = enrichedMeta().copy(language = null, country = "US"))

        runtime.refreshTracks()

        reportRequestedOriginalSelection(runtime, controller)
        assertEquals(1, runtime.selectedAudioIndex)
    }

    @Test
    fun correctedOriginalLanguageReplacesAnEarlierAutomaticMatch() {
        val controller = RecordingController(audioTracks())
        val runtime = runtime(controller)
        runtime.metaUiState = MetaDetailsUiState(meta = enrichedMeta().copy(language = deviceLanguage))
        runtime.refreshTracks()
        assertEquals(0, runtime.selectedAudioIndex)

        runtime.metaUiState = MetaDetailsUiState(meta = enrichedMeta())
        runtime.refreshTracks()

        reportRequestedOriginalSelection(runtime, controller)
        assertEquals(1, runtime.selectedAudioIndex)
    }

    @Test
    fun pendingTrackRefreshUpgradesSecondaryAudioWhenOriginalAppears() {
        val controller = RecordingController(audioTracks().take(1))
        val runtime = runtime(controller)
        runtime.playerSettingsUiState = runtime.playerSettingsUiState.copy(
            secondaryPreferredAudioLanguage = deviceLanguage,
        )
        runtime.playerMeta = enrichedMeta()
        runtime.refreshTracks()
        repeat(12) { runtime.refreshAudioTracksIfChanged() }
        assertEquals(0, runtime.selectedAudioIndex)

        controller.tracks = audioTracks()
        runtime.refreshAudioTracksIfChanged()

        reportRequestedOriginalSelection(runtime, controller)
        assertEquals(1, runtime.selectedAudioIndex)
        assertEquals(emptyList(), controller.audioSelections)
    }

    @Test
    fun manualAudioSelectionSurvivesAutomaticRefresh() {
        val controller = RecordingController(audioTracks())
        val runtime = runtime(controller, contentLanguage = originalLanguage)
        runtime.args = runtime.args.copy(parentMetaId = "")
        runtime.refreshTracks()
        reportRequestedOriginalSelection(runtime, controller)
        assertEquals(1, runtime.selectedAudioIndex)

        runtime.selectedAudioIndex = 0
        runtime.persistAudioPreference(controller.tracks.first())
        controller.selectAudioTrack(0)
        runtime.refreshTracks()

        assertEquals(0, runtime.selectedAudioIndex)
        assertEquals(listOf(0), controller.audioSelections)
    }

    @Test
    fun savedAudioChoiceSurvivesOriginalMetadataArrivingLater() {
        val controller = RecordingController(audioTracks())
        val runtime = runtime(controller)
        runtime.audioTracks = controller.tracks
        runtime.selectedAudioIndex = 0
        runtime.restorePersistedAudioPreference(PersistedPlayerTrackPreference(audioLanguage = deviceLanguage))

        runtime.playerMeta = enrichedMeta()
        runtime.refreshTracks()

        assertEquals(0, runtime.selectedAudioIndex)
        assertEquals(listOf(0), controller.audioSelections)
        assertEquals(emptyList(), controller.audioLanguagePreferences)
    }

    @Test
    fun missingSavedTrackDoesNotBlockOriginalAudio() {
        val controller = RecordingController(audioTracks())
        val runtime = runtime(controller)
        runtime.audioTracks = controller.tracks
        runtime.restorePersistedAudioPreference(PersistedPlayerTrackPreference(audioTrackId = "missing-track"))
        runtime.playerMeta = enrichedMeta()

        runtime.refreshTracks()

        reportRequestedOriginalSelection(runtime, controller)
        assertEquals(1, runtime.selectedAudioIndex)
    }

    @Test
    fun explicitLanguagePreferenceKeepsPriorityOverOriginalMetadata() {
        val controller = RecordingController(audioTracks())
        val runtime = runtime(controller)
        runtime.playerSettingsUiState = runtime.playerSettingsUiState.copy(preferredAudioLanguage = deviceLanguage)
        runtime.playerMeta = enrichedMeta()

        runtime.refreshTracks()

        assertEquals(0, runtime.selectedAudioIndex)
        assertEquals(emptyList(), controller.audioSelections)
    }

    @Test
    fun sameLanguageTrackChosenByEngineIsPreserved() {
        val controller = RecordingController(audioTracks().map { it.copy(isSelected = false) } +
            AudioTrack(2, "3", "Original surround default", originalLanguage, true))
        val runtime = runtime(controller, contentLanguage = originalLanguage)

        runtime.refreshTracks()
        repeat(12) { runtime.refreshAudioTracksIfChanged() }

        assertEquals(listOf(listOf(originalLanguage)), controller.audioLanguagePreferences)
        assertEquals(2, runtime.selectedAudioIndex)
        assertEquals(emptyList(), controller.audioSelections)
    }

    @Test
    fun unmatchedLanguageLeavesFallbackSelectionToEngine() {
        val controller = RecordingController(listOf(
            AudioTrack(0, "1", "First track", "fr"),
            AudioTrack(1, "2", "Container default", "de", true),
        ))
        val runtime = runtime(controller, contentLanguage = "ja")
        runtime.playerSettingsUiState = runtime.playerSettingsUiState.copy(secondaryPreferredAudioLanguage = "en")

        runtime.refreshTracks()
        runtime.refreshAudioTracksIfChanged()

        assertEquals(listOf(listOf("ja", "en")), controller.audioLanguagePreferences)
        assertEquals(1, runtime.selectedAudioIndex)
        assertEquals(emptyList(), controller.audioSelections)
    }

    @Test
    fun defaultPreferenceClearsPreviouslyConfiguredLanguages() {
        val controller = RecordingController(audioTracks())
        val runtime = runtime(controller, contentLanguage = originalLanguage)
        runtime.refreshTracks()
        runtime.playerSettingsUiState = runtime.playerSettingsUiState.copy(preferredAudioLanguage = AudioLanguageOption.DEFAULT)

        runtime.refreshTracks()

        assertEquals(listOf(listOf(originalLanguage), emptyList()), controller.audioLanguagePreferences)
        assertEquals(emptyList(), controller.audioSelections)
    }

    @Test
    fun nativeSelectionUpdatesDoNotReapplyAutomaticPreferences() {
        val controller = RecordingController(audioTracks())
        val runtime = runtime(controller, contentLanguage = originalLanguage)
        runtime.refreshTracks()

        reportRequestedOriginalSelection(runtime, controller)
        repeat(12) { runtime.refreshAudioTracksIfChanged() }

        assertEquals(listOf(listOf(originalLanguage)), controller.audioLanguagePreferences)
        assertTrue(runtime.preferredAudioSelectionApplied)
    }

    @Test
    fun languageMetadataUpdatesWithTheSameTrackCountReapplyPreferences() {
        val controller = RecordingController(audioTracks().map { it.copy(language = null) })
        val runtime = runtime(controller, contentLanguage = originalLanguage)
        runtime.refreshTracks()
        controller.tracks = audioTracks()

        runtime.refreshAudioTracksIfChanged()

        assertEquals(listOf(listOf(originalLanguage), listOf(originalLanguage)), controller.audioLanguagePreferences)
    }

    @Test
    fun replacementControllerReceivesTheCurrentPreferences() {
        val controller = RecordingController(audioTracks())
        val runtime = runtime(controller, contentLanguage = originalLanguage)
        runtime.refreshTracks()
        val replacement = RecordingController(audioTracks())
        runtime.playerController = replacement

        runtime.refreshTracks()

        assertEquals(listOf(listOf(originalLanguage)), replacement.audioLanguagePreferences)
    }

    @Test
    fun emptyInitialTrackListDoesNotConsumeSavedAudioChoice() {
        val controller = RecordingController(emptyList())
        val runtime = runtime(controller)
        val preference = PersistedPlayerTrackPreference(audioLanguage = deviceLanguage)
        runtime.restorePersistedAudioPreference(preference)
        assertFalse(runtime.isUserExplicitAudioSelection)

        controller.tracks = audioTracks()
        runtime.audioTracks = controller.tracks
        runtime.restorePersistedAudioPreference(preference)
        runtime.playerMeta = enrichedMeta()
        runtime.refreshTracks()

        assertTrue(runtime.isUserExplicitAudioSelection)
        assertEquals(0, runtime.selectedAudioIndex)
        assertEquals(listOf(0), controller.audioSelections)
        assertEquals(emptyList(), controller.audioLanguagePreferences)
    }

    @Test
    fun manualChoiceSurvivesNewTracksAndCorrectedOriginalMetadata() {
        val controller = RecordingController(audioTracks())
        val runtime = runtime(controller, contentLanguage = originalLanguage)
        runtime.args = runtime.args.copy(parentMetaId = "")
        runtime.refreshTracks()
        runtime.persistAudioPreference(controller.tracks.first())
        controller.selectAudioTrack(0)
        controller.tracks += AudioTrack(2, "3", "Another original track", originalLanguage)
        runtime.args = runtime.args.copy(contentLanguage = "fr")

        runtime.refreshAudioTracksIfChanged()

        assertEquals(listOf(listOf(originalLanguage)), controller.audioLanguagePreferences)
        assertEquals(0, runtime.selectedAudioIndex)
    }

    @Test
    fun asynchronousAudioSelectionReevaluatesForcedSubtitles() {
        val controller = RecordingController(audioTracks())
        controller.subtitles = listOf(
            SubtitleTrack(0, "s1", "Full subtitles", deviceLanguage),
            SubtitleTrack(1, "s2", "Forced subtitles", deviceLanguage, isForced = true),
        )
        val runtime = runtime(controller, contentLanguage = originalLanguage)
        runtime.playerSettingsUiState = runtime.playerSettingsUiState.copy(
            preferredSubtitleLanguage = deviceLanguage,
            subtitleStyle = SubtitleStyleState(useForcedSubtitles = true),
        )
        runtime.preferredSubtitleSelectionApplied = false
        runtime.refreshTracks()
        runtime.refreshAudioTracksIfChanged()
        assertEquals(1, runtime.selectedSubtitleIndex)

        reportRequestedOriginalSelection(runtime, controller)

        assertEquals(0, runtime.selectedSubtitleIndex)
        assertEquals(listOf(1, 0), controller.subtitleSelections)
    }

    private fun reportRequestedOriginalSelection(runtime: PlayerScreenRuntime, controller: RecordingController) {
        assertEquals(originalLanguage, controller.audioLanguagePreferences.last().first())
        assertEquals(emptyList(), controller.audioSelections)
        controller.reportAudioSelection(1)
        runtime.refreshAudioTracksIfChanged()
    }

    private val deviceLanguage = DeviceLanguagePreferences.preferredLanguageCodes().first()
    private val originalLanguage = if (deviceLanguage.substringBefore('-') == "ja") "ko" else "ja"

    private fun audioTracks() = listOf(
        AudioTrack(0, "1", "Dub", deviceLanguage, true),
        AudioTrack(1, "2", "Original", originalLanguage),
    )

    private fun enrichedMeta() = TmdbMetadataService.applyEnrichment(
        meta = MetaDetails(id = "tt-original-audio-test", type = "movie", name = "Test movie"),
        enrichment = TmdbEnrichment(
            localizedTitle = null,
            description = null,
            genres = emptyList(),
            backdrop = null,
            logo = null,
            poster = null,
            people = emptyList(),
            director = emptyList(),
            writer = emptyList(),
            releaseInfo = null,
            rating = null,
            runtimeMinutes = null,
            ageRating = null,
            status = null,
            countries = listOf("US"),
            language = originalLanguage,
            productionCompanies = emptyList(),
            networks = emptyList(),
        ),
        episodeMap = emptyMap(),
        settings = TmdbSettings(enabled = true, useDetails = true),
    )

    private fun runtime(controller: RecordingController, contentLanguage: String? = null) =
        PlayerScreenRuntime(
            PlayerScreenArgs(
                profileId = 1,
                title = "Test movie",
                sourceUrl = "https://example.com/video.mkv",
                sourceAudioUrl = null,
                sourceHeaders = emptyMap(),
                sourceResponseHeaders = emptyMap(),
                streamType = null,
                providerName = "Test provider",
                streamTitle = "Dual audio",
                streamSubtitle = null,
                initialBingeGroup = null,
                pauseDescription = null,
                onBack = {},
                onOpenInExternalPlayer = null,
                onOpenExternalUrl = null,
                modifier = Modifier,
                logo = null,
                poster = null,
                background = null,
                seasonNumber = null,
                episodeNumber = null,
                episodeTitle = null,
                episodeThumbnail = null,
                contentType = "movie",
                videoId = "tt-original-audio-test",
                parentMetaId = "tt-original-audio-test",
                parentMetaType = "movie",
                providerAddonId = null,
                torrentInfoHash = null,
                torrentFileIdx = null,
                torrentFilename = null,
                torrentTrackers = emptyList(),
                initialPositionMs = 0L,
                initialProgressFraction = null,
                contentLanguage = contentLanguage,
            ),
        ).apply {
            playerController = controller
            playbackSnapshot = PlayerPlaybackSnapshot(isLoading = false, isPlaying = true)
            playerSettingsUiState = PlayerSettingsUiState(
                preferredAudioLanguage = AudioLanguageOption.ORIGINAL,
                preferredSubtitleLanguage = SubtitleLanguageOption.NONE,
            )
            trackPreferenceRestoreApplied = true
            preferredSubtitleSelectionApplied = true
        }

    private class RecordingController(var tracks: List<AudioTrack>) : PlayerEngineController {
        val audioSelections = mutableListOf<Int>()
        val audioLanguagePreferences = mutableListOf<List<String>>()
        val subtitleSelections = mutableListOf<Int>()
        var subtitles = emptyList<SubtitleTrack>()

        override fun getAudioTracks() = tracks
        override fun getSubtitleTracks() = subtitles
        override fun applyAudioLanguagePreferences(languages: List<String>) {
            audioLanguagePreferences += languages
        }
        override fun selectAudioTrack(index: Int) {
            audioSelections += index
            reportAudioSelection(index)
        }
        fun reportAudioSelection(index: Int) {
            tracks = tracks.map { it.copy(isSelected = it.index == index) }
        }
        override fun selectSubtitleTrack(index: Int) {
            subtitleSelections += index
            subtitles = subtitles.map { it.copy(isSelected = it.index == index) }
        }

        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(offsetMs: Long) = Unit
        override fun retry() = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun setSubtitleUri(url: String) = Unit
        override fun clearExternalSubtitle() = Unit
        override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
    }
}
