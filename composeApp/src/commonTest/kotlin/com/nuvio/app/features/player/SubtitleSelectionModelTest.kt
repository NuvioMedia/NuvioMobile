package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SubtitleSelectionModelTest {

    @Test
    fun groupsTracksAndAddonsByLanguageWithPreferredLanguagesFirst() {
        val tracks = listOf(
            subtitleTrack(index = 0, language = "fr"),
            subtitleTrack(index = 1, language = "en"),
        )
        val addons = listOf(
            addonSubtitle(id = "es", language = "es"),
            addonSubtitle(id = "en", language = "en"),
        )

        val items = buildSubtitleLanguageItems(
            subtitleTracks = tracks,
            addonSubtitles = addons,
            preferredLanguage = "en",
            secondaryPreferredLanguage = "fr",
            showOnlyPreferredLanguages = false,
            selectedLanguageKey = "en",
        )

        assertEquals(
            listOf(SubtitleOffLanguageKey, "en", "fr", "es"),
            items.map { it.key },
        )
        assertEquals(2, items.first { it.key == "en" }.count)
    }

    @Test
    fun preferredOnlyModeKeepsTheCurrentlySelectedLanguage() {
        val items = buildSubtitleLanguageItems(
            subtitleTracks = listOf(
                subtitleTrack(index = 0, language = "en"),
                subtitleTrack(index = 1, language = "ja"),
            ),
            addonSubtitles = emptyList(),
            preferredLanguage = "en",
            secondaryPreferredLanguage = null,
            showOnlyPreferredLanguages = true,
            selectedLanguageKey = "ja",
        )

        assertEquals(listOf(SubtitleOffLanguageKey, "en", "ja"), items.map { it.key })
    }

    @Test
    fun detectsRegionalVariantsFromEmbeddedTrackLabels() {
        val items = buildSubtitleLanguageItems(
            subtitleTracks = listOf(
                subtitleTrack(index = 0, language = "por", label = "Portuguese (Brazilian)"),
                subtitleTrack(index = 1, language = "spa", label = "Español Latino"),
            ),
            addonSubtitles = emptyList(),
            preferredLanguage = SubtitleLanguageOption.NONE,
            secondaryPreferredLanguage = null,
            showOnlyPreferredLanguages = false,
            selectedLanguageKey = SubtitleOffLanguageKey,
        )

        assertEquals(
            setOf(SubtitleOffLanguageKey, "pt-br", "es-419"),
            items.map { it.key }.toSet(),
        )
    }

    @Test
    fun combinesBuiltInAndAddonOptionsWithoutDuplicateAddons() {
        val track = subtitleTrack(index = 2, language = "en")
        val addon = addonSubtitle(id = "main", language = "en")

        val options = buildSubtitleSelectionOptions(
            languageKey = "en",
            subtitleTracks = listOf(track),
            addonSubtitles = listOf(addon, addon),
        )

        assertEquals(2, options.size)
        assertIs<SubtitleSelectionOption.BuiltIn>(options[0])
        assertIs<SubtitleSelectionOption.Addon>(options[1])
    }

    @Test
    fun keepsAddonOptionsWithTheSameNameAndTitleWhenUrlsDiffer() {
        val first = addonSubtitle(
            id = "en",
            language = "en",
            url = "https://example.com/en-a.srt",
            display = "English",
        )
        val second = addonSubtitle(
            id = "en",
            language = "en",
            url = "https://example.com/en-b.srt",
            display = "English",
        )

        val options = buildSubtitleSelectionOptions(
            languageKey = "en",
            subtitleTracks = emptyList(),
            addonSubtitles = listOf(first, second),
        )

        assertEquals(listOf(first.url, second.url), options.map { (it as SubtitleSelectionOption.Addon).subtitle.url })
        assertEquals(2, options.map { it.id }.distinct().size)
        assertEquals(second, listOf(first, second).findSelectedAddon(second.selectionKey))
        assertEquals(second, listOf(first, second).findSelectedAddon(second.url))
    }

    @Test
    fun structureKeyIgnoresSelectionSoTheListDoesNotRebuildOnToggle() {
        val unselected = subtitleTrack(index = 0, language = "en", label = "English")
        val selected = unselected.copy(isSelected = true)

        assertEquals(subtitleTracksStructureKey(listOf(unselected)), subtitleTracksStructureKey(listOf(selected)))
    }

    @Test
    fun emptySubtitleRailShowsFetchActionWhenNoLanguagesAreAvailable() {
        assertEquals(
            SubtitleOptionsRailEmptyContent.FETCH,
            subtitleOptionsRailEmptyContent(
                selectedLanguageKey = SubtitleOffLanguageKey,
                hasAvailableLanguages = false,
                isLoadingAddonSubtitles = false,
            ),
        )
    }

    @Test
    fun emptySubtitleRailShowsLoadingWhileBackgroundFetchRuns() {
        assertEquals(
            SubtitleOptionsRailEmptyContent.LOADING,
            subtitleOptionsRailEmptyContent(
                selectedLanguageKey = SubtitleOffLanguageKey,
                hasAvailableLanguages = false,
                isLoadingAddonSubtitles = true,
            ),
        )
    }

    @Test
    fun subtitleRailShowsNoneWhenOffIsSelectedAndLanguagesExist() {
        assertEquals(
            SubtitleOptionsRailEmptyContent.NONE,
            subtitleOptionsRailEmptyContent(
                selectedLanguageKey = SubtitleOffLanguageKey,
                hasAvailableLanguages = true,
                isLoadingAddonSubtitles = false,
            ),
        )
    }

    private fun subtitleTrack(
        index: Int,
        language: String,
        label: String = "Track $index",
    ) = SubtitleTrack(
        index = index,
        id = "track-$index",
        label = label,
        language = language,
    )

    private fun addonSubtitle(
        id: String,
        language: String,
        url: String = "https://example.com/$id.srt",
        display: String = id,
    ) = AddonSubtitle(
        id = id,
        url = url,
        language = language,
        display = display,
        addonName = "Addon",
    )
}
