package com.nuvio.app.features.plugins

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.plugins_error_unavailable_build
import org.jetbrains.compose.resources.getString

actual object PluginRepository {
    private val disabledState = MutableStateFlow(PluginsUiState(pluginsEnabled = false))

    actual val uiState: StateFlow<PluginsUiState> = disabledState.asStateFlow()

    actual fun initialize() = Unit

    actual fun onProfileChanged(profileId: Int) = Unit

    actual fun clearLocalState() = Unit

    actual suspend fun pullFromServer(profileId: Int) = Unit

    actual suspend fun addRepository(rawUrl: String): AddPluginRepositoryResult =
        AddPluginRepositoryResult.Error(getString(Res.string.plugins_error_unavailable_build))

    actual fun removeRepository(manifestUrl: String) = Unit

    actual fun refreshAll() = Unit

    actual fun refreshRepository(manifestUrl: String, pushAfterRefresh: Boolean) = Unit

    actual fun toggleScraper(scraperId: String, enabled: Boolean) = Unit

    actual fun setPluginsEnabled(enabled: Boolean) = Unit

    actual fun setGroupStreamsByRepository(enabled: Boolean) = Unit

    actual fun setLocalPluginSearchPaused(paused: Boolean) = Unit

    actual fun getEnabledScrapersForType(type: String): List<PluginScraper> = emptyList()

    actual suspend fun testScraper(scraperId: String): Result<List<PluginRuntimeResult>> =
        Result.failure(UnsupportedOperationException(getString(Res.string.plugins_error_unavailable_build)))

    actual suspend fun executeScraper(
        scraper: PluginScraper,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
    ): Result<List<PluginRuntimeResult>> =
        Result.failure(UnsupportedOperationException(getString(Res.string.plugins_error_unavailable_build)))
}