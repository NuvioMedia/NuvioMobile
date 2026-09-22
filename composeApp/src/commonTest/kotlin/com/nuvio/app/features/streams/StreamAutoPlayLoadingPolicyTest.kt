package com.nuvio.app.features.streams

import com.nuvio.app.features.player.PlayerSettingsUiState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamAutoPlayLoadingPolicyTest {

    private val autoPlaySettings = PlayerSettingsUiState(streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM)

    @Test
    fun `autoplay shows loading before the stream request starts`() {
        assertTrue(StreamsUiState().shouldShowAutoPlayLoading("new", autoPlaySettings, false))
        assertTrue(
            StreamsUiState(requestToken = "new")
                .shouldShowAutoPlayLoading("new", autoPlaySettings, false),
        )
    }

    @Test
    fun `previous request cannot hide a new autoplay loading screen`() {
        val previous = StreamsUiState(requestToken = "old", autoPlayDecided = true)
        assertTrue(previous.shouldShowAutoPlayLoading("new", autoPlaySettings, false))
    }

    @Test
    fun `manual selection and invalid regex do not start autoplay loading`() {
        val initial = StreamsUiState()
        assertFalse(initial.shouldShowAutoPlayLoading("new", autoPlaySettings, true))
        assertFalse(initial.shouldShowAutoPlayLoading("new", PlayerSettingsUiState(), false))
        assertFalse(initial.shouldShowAutoPlayLoading(
            "new",
            PlayerSettingsUiState(streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH, streamAutoPlayRegex = "["),
            false,
        ))
    }

    @Test
    fun `settled autoplay fallback reveals the stream picker`() {
        val loading = StreamsUiState(
            requestToken = "new",
            autoPlayDecided = true,
            showDirectAutoPlayOverlay = true,
        )
        assertTrue(loading.shouldShowAutoPlayLoading("new", autoPlaySettings, false))
        assertFalse(
            loading.copy(showDirectAutoPlayOverlay = false)
                .shouldShowAutoPlayLoading("new", autoPlaySettings, false),
        )
    }

    @Test
    fun `last link reuse starts with loading even in manual mode`() {
        assertTrue(
            StreamsUiState().shouldShowAutoPlayLoading(
                "new", PlayerSettingsUiState(streamReuseLastLinkEnabled = true), false,
            ),
        )
    }

    @Test
    fun `predicted autoplay loading does not rotate before the request starts`() {
        val state = StreamsUiState()
        val settings = listOf(
            autoPlaySettings,
            PlayerSettingsUiState(streamReuseLastLinkEnabled = true),
            PlayerSettingsUiState(streamAutoPlayReuseBingeGroup = true),
        )

        settings.forEach { playerSettings ->
            assertTrue(state.shouldShowAutoPlayLoading("new", playerSettings, false))
            assertFalse(state.shouldUseLandscapeAutoPlayLoading("new", false))
        }
    }

    @Test
    fun `confirmed autoplay loading rotates only for the current automatic request`() {
        val state = StreamsUiState(
            requestToken = "new",
            autoPlayDecided = true,
            isDirectAutoPlayFlow = true,
            showDirectAutoPlayOverlay = true,
        )

        assertTrue(state.shouldUseLandscapeAutoPlayLoading("new", false))
        assertFalse(state.shouldUseLandscapeAutoPlayLoading("other", false))
        assertFalse(state.shouldUseLandscapeAutoPlayLoading("new", true))
        assertFalse(state.copy(autoPlayDecided = false).shouldUseLandscapeAutoPlayLoading("new", false))
        assertFalse(state.copy(showDirectAutoPlayOverlay = false).shouldUseLandscapeAutoPlayLoading("new", false))
    }

    @Test
    fun `stream picker and external preparation overlays do not rotate`() {
        val state = StreamsUiState(requestToken = "new", autoPlayDecided = true)

        assertFalse(state.shouldUseLandscapeAutoPlayLoading("new", false))
        assertFalse(state.shouldUseLandscapeAutoPlayLoading("new", true))
        assertFalse(
            state.copy(showDirectAutoPlayOverlay = true)
                .shouldUseLandscapeAutoPlayLoading("new", false),
        )
    }

    @Test
    fun `installed addons are loaded while plugins are still loading`() {
        val groups = listOf(
            group(addonId = "addon:torrentio", isLoading = false),
            group(addonId = "plugin:comet", isLoading = true),
        )

        assertTrue(
            groups.areAutoPlaySourcesLoaded(
                source = StreamAutoPlaySource.INSTALLED_ADDONS_ONLY,
                installedAddonIds = setOf("addon:torrentio"),
            ),
        )
    }

    @Test
    fun `installed addons remain loading until every addon finishes`() {
        val groups = listOf(
            group(addonId = "addon:torrentio", isLoading = false),
            group(addonId = "addon:comet", isLoading = true),
            group(addonId = "plugin:mediafusion", isLoading = false),
        )

        assertFalse(
            groups.areAutoPlaySourcesLoaded(
                source = StreamAutoPlaySource.INSTALLED_ADDONS_ONLY,
                installedAddonIds = setOf("addon:torrentio", "addon:comet"),
            ),
        )
    }

    @Test
    fun `enabled plugins are loaded while addons are still loading`() {
        val groups = listOf(
            group(addonId = "addon:torrentio", isLoading = true),
            group(addonId = "plugin:comet", isLoading = false),
        )

        assertTrue(
            groups.areAutoPlaySourcesLoaded(
                source = StreamAutoPlaySource.ENABLED_PLUGINS_ONLY,
                installedAddonIds = setOf("addon:torrentio"),
            ),
        )
    }

    @Test
    fun `all sources wait for addons and plugins`() {
        val groups = listOf(
            group(addonId = "addon:torrentio", isLoading = false),
            group(addonId = "plugin:comet", isLoading = true),
        )

        assertFalse(
            groups.areAutoPlaySourcesLoaded(
                source = StreamAutoPlaySource.ALL_SOURCES,
                installedAddonIds = setOf("addon:torrentio"),
            ),
        )
    }

    private fun group(addonId: String, isLoading: Boolean): AddonStreamGroup =
        AddonStreamGroup(
            addonName = addonId,
            addonId = addonId,
            streams = emptyList(),
            isLoading = isLoading,
        )
}
