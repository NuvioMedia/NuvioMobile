package com.nuvio.app.features.cloudstream

import kotlinx.coroutines.flow.StateFlow

data class CloudStreamRepositoryItem(
    val manifest: CloudStreamRepositoryManifest,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val lastRefreshedAtEpochMs: Long = 0L,
)

data class CloudStreamPluginItem(
    val metadata: CloudStreamPluginMetadata,
    val compatibility: CloudStreamCompatibility,
    val installedVersion: Int? = null,
    val installedAtEpochMs: Long = 0L,
    /** Package passed structural inspection and any repository-declared checksum matched. */
    val installedFileHash: String? = null,
    val enabled: Boolean = false,
    val verified: Boolean = false,
    val isInstalling: Boolean = false,
    val errorMessage: String? = null,
) {
    val isInstalled: Boolean
        get() = installedVersion != null

    val hasUpdate: Boolean
        get() = installedVersion != null && metadata.version > installedVersion

    /**
     * Whether the repository currently permits downloading this .cs3 artifact.
     * Downloadability is deliberately separate from runtime compatibility: Nuvio
     * may retain and hash-verify an unsupported DEX package without executing it.
     */
    val canDownloadPackage: Boolean
        get() = metadata.status.canInstall && !isInstalling

    /** Only a validated package with a supported runtime may be enabled. */
    val canEnableProvider: Boolean
        get() = isInstalled && verified && compatibility.isRunnable && metadata.status.canInstall

    val isRunnable: Boolean
        get() = enabled && canEnableProvider
}

data class CloudStreamUiState(
    val repositories: List<CloudStreamRepositoryItem> = emptyList(),
    val plugins: List<CloudStreamPluginItem> = emptyList(),
    val registryRevision: Long = 0L,
    val securityWarningAccepted: Boolean = false,
    val hideAllHomeCatalogs: Boolean = false,
    val hiddenHomeCatalogProviderIds: Set<String> = emptySet(),
    val groupStreamsByRepository: Boolean = false,
)

sealed interface AddCloudStreamRepositoryResult {
    data class Success(val repository: CloudStreamRepositoryManifest) : AddCloudStreamRepositoryResult
    data class Error(val message: String) : AddCloudStreamRepositoryResult
}

sealed interface CloudStreamInstallResult {
    data class Success(val plugin: CloudStreamPluginItem) : CloudStreamInstallResult
    data class Error(val message: String) : CloudStreamInstallResult
}

data class CloudStreamBulkInstallFailure(
    val pluginName: String,
    val message: String,
)

data class CloudStreamBulkInstallResult(
    val requestedCount: Int,
    val installedCount: Int,
    val enabledCount: Int,
    val skippedCount: Int,
    val failures: List<CloudStreamBulkInstallFailure> = emptyList(),
)

expect object CloudStreamRepository {
    val uiState: StateFlow<CloudStreamUiState>

    fun initialize()
    fun onProfileChanged(profileId: Int)
    fun clearLocalState()
    fun acceptSecurityWarning()

    suspend fun addRepository(rawUrl: String): AddCloudStreamRepositoryResult
    fun refreshRepository(manifestUrl: String)
    fun refreshAll(force: Boolean = true)
    fun removeRepository(manifestUrl: String)

    suspend fun installPlugin(pluginId: String): CloudStreamInstallResult
    suspend fun updatePlugin(pluginId: String): CloudStreamInstallResult
    suspend fun installAndEnablePlugins(pluginIds: List<String>): CloudStreamBulkInstallResult
    fun setPluginEnabled(pluginId: String, enabled: Boolean)
    fun setHideAllHomeCatalogs(hidden: Boolean)
    fun setPluginHomeCatalogsHidden(pluginId: String, hidden: Boolean)
    fun setGroupStreamsByRepository(enabled: Boolean)
    fun removePlugin(pluginId: String)

    suspend fun getMainPage(providerId: String, page: Int = 1): Result<List<Pair<String, List<CloudStreamSearchItem>>>>
    suspend fun search(query: String, providerId: String? = null): List<Result<List<CloudStreamSearchItem>>>
    suspend fun loadByExternalId(providerId: String, externalId: String): Result<CloudStreamLoadItem?>
    suspend fun load(providerId: String, data: String): Result<CloudStreamLoadItem>
    suspend fun loadLinks(providerId: String, data: String): Result<List<CloudStreamPlaybackSource>>
}

internal expect object CloudStreamPlatformStorage {
    fun initialize(context: Any?)
    fun setActiveProfile(profileId: Int)
    fun loadState(profileId: Int): String?
    fun saveState(profileId: Int, payload: String)
    fun savePackageAtomically(storageKey: String, bytes: ByteArray)
    fun packageExists(storageKey: String): Boolean
    fun migratePackage(oldStorageKey: String, newStorageKey: String): Boolean
    fun packagePath(storageKey: String): String?
    fun deletePackage(storageKey: String)
    fun clearPackages()
    fun clearAllState()
}
