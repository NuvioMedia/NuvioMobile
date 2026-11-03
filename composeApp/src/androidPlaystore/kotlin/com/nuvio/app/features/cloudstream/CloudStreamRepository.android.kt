package com.nuvio.app.features.cloudstream

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual object CloudStreamRepository {
    private val disabled = MutableStateFlow(CloudStreamUiState())
    actual val uiState: StateFlow<CloudStreamUiState> = disabled.asStateFlow()
    actual fun initialize() = Unit
    actual fun onProfileChanged(profileId: Int) = Unit
    actual fun clearLocalState() = Unit
    actual fun acceptSecurityWarning() = Unit
    actual fun setHideAllHomeCatalogs(hidden: Boolean) = Unit
    actual fun setPluginHomeCatalogsHidden(pluginId: String, hidden: Boolean) = Unit
    actual suspend fun addRepository(rawUrl: String): AddCloudStreamRepositoryResult = AddCloudStreamRepositoryResult.Error("CloudStream is available only in full builds")
    actual fun refreshRepository(manifestUrl: String) = Unit
    actual fun refreshAll(force: Boolean) = Unit
    actual fun removeRepository(manifestUrl: String) = Unit
    actual suspend fun installPlugin(pluginId: String): CloudStreamInstallResult = CloudStreamInstallResult.Error("CloudStream is available only in full builds")
    actual suspend fun updatePlugin(pluginId: String): CloudStreamInstallResult = CloudStreamInstallResult.Error("CloudStream is available only in full builds")
    actual suspend fun installAndEnablePlugins(pluginIds: List<String>): CloudStreamBulkInstallResult =
        CloudStreamBulkInstallResult(
            requestedCount = pluginIds.distinct().size,
            installedCount = 0,
            enabledCount = 0,
            skippedCount = 0,
            failures = listOf(
                CloudStreamBulkInstallFailure(
                    pluginName = "CloudStream",
                    message = "CloudStream is available only in full builds",
                ),
            ),
        )
    actual fun setPluginEnabled(pluginId: String, enabled: Boolean) = Unit
    actual fun setGroupStreamsByRepository(enabled: Boolean) = Unit
    actual fun removePlugin(pluginId: String) = Unit
    actual suspend fun getMainPage(providerId: String, page: Int) = Result.failure<List<Pair<String, List<CloudStreamSearchItem>>>>(UnsupportedOperationException())
    actual suspend fun search(query: String, providerId: String?) = emptyList<Result<List<CloudStreamSearchItem>>>()
    actual suspend fun loadByExternalId(providerId: String, externalId: String) = Result.failure<CloudStreamLoadItem?>(UnsupportedOperationException())
    actual suspend fun load(providerId: String, data: String) = Result.failure<CloudStreamLoadItem>(UnsupportedOperationException())
    actual suspend fun loadLinks(providerId: String, data: String) = Result.failure<List<CloudStreamPlaybackSource>>(UnsupportedOperationException())
}
