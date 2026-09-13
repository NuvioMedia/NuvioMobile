package com.nuvio.app.features.updater

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.i18n.localizedByteUnit
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

private const val gitHubOwner = "NuvioMedia"
private const val gitHubRepo = "NuvioMobile"
private const val gitHubApiBase = "https://api.github.com"
private const val releaseChannelBranch = "cmp-rewrite"

data class AppUpdate(
    val tag: String,
    val title: String,
    val notes: String,
    val releaseUrl: String?,
    val assetName: String,
    val assetUrl: String,
    val assetSizeBytes: Long?,
)

data class AppUpdaterUiState(
    val isChecking: Boolean = false,
    val update: AppUpdate? = null,
    val isUpdateAvailable: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float? = null,
    val downloadedApkPath: String? = null,
    val showDialog: Boolean = false,
    val showUnknownSourcesDialog: Boolean = false,
    val errorMessage: String? = null,
    val isDebugTest: Boolean = false,
)

@Serializable
private data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String? = null,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("target_commitish") val targetCommitish: String? = null,
    val assets: List<GitHubAssetDto> = emptyList(),
)

@Serializable
private data class GitHubAssetDto(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long? = null,
    @SerialName("content_type") val contentType: String? = null,
)

private val appUpdaterJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private class NoChannelReleaseException : IllegalStateException(
    runBlocking { getString(Res.string.updates_no_channel_release) },
)

private object VersionUtils {
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.trim().removePrefix("v").removePrefix("V")
    }

    fun parseVersionParts(raw: String?): List<Int>? {
        val normalized = normalize(raw)
        if (normalized.isBlank()) return null

        val parts = normalized.split('.', '-', '_')
            .filter { it.isNotBlank() }
            .mapNotNull { token -> token.takeWhile { it.isDigit() }.toIntOrNull() }

        return parts.takeIf { it.isNotEmpty() }
    }

    fun isRemoteNewer(remote: String?, local: String?): Boolean {
        val remoteParts = parseVersionParts(remote)
        val localParts = parseVersionParts(local)

        if (remoteParts == null || localParts == null) {
            val remoteValue = normalize(remote)
            val localValue = normalize(local)
            return remoteValue.isNotBlank() && localValue.isNotBlank() && remoteValue != localValue
        }

        val maxSize = maxOf(remoteParts.size, localParts.size)
        for (index in 0 until maxSize) {
            val remoteValue = remoteParts.getOrElse(index) { 0 }
            val localValue = localParts.getOrElse(index) { 0 }
            if (remoteValue != localValue) return remoteValue > localValue
        }
        return false
    }
}

private object AppUpdaterRepository {
    suspend fun getLatestChannelUpdate(): Result<AppUpdate> = runCatching {
        val response = httpRequestRaw(
            method = "GET",
            url = "$gitHubApiBase/repos/$gitHubOwner/$gitHubRepo/releases?per_page=20",
            headers = mapOf(
                "Accept" to "application/vnd.github+json",
                "User-Agent" to "NuvioMobile",
            ),
            body = "",
        )
        if (response.status !in 200..299) {
            error(getString(Res.string.updates_github_api_error, response.status))
        }

        val releases = appUpdaterJson.decodeFromString<List<GitHubReleaseDto>>(response.body)
        val release = releases.firstOrNull {
            it.matchesRequestedChannel() &&
                !it.draft &&
                (!it.prerelease || AppVersionConfig.PERSONAL_UPDATE_BUILD)
        }
            ?: throw NoChannelReleaseException()

        val tag = release.tagName?.takeIf { it.isNotBlank() }
            ?: release.name?.takeIf { it.isNotBlank() }
            ?: error(getString(Res.string.updates_release_missing_title))

        val asset = chooseBestApkAsset(release.assets)
            ?: error(getString(Res.string.updates_apk_asset_missing))

        AppUpdate(
            tag = tag,
            title = release.name?.takeIf { it.isNotBlank() } ?: tag,
            notes = release.body.orEmpty(),
            releaseUrl = release.htmlUrl,
            assetName = asset.name,
            assetUrl = asset.browserDownloadUrl,
            assetSizeBytes = asset.size,
        )
    }

    private fun GitHubReleaseDto.matchesRequestedChannel(): Boolean {
        if (AppVersionConfig.PERSONAL_UPDATE_BUILD && prerelease) {
            return true
        }
        val channel = releaseChannelBranch
        if (targetCommitish?.trim()?.equals(channel, ignoreCase = true) == true) {
            return true
        }

        return listOf(tagName, name)
            .filterNotNull()
            .any { value -> value.contains(channel, ignoreCase = true) }
    }

    private fun chooseBestApkAsset(assets: List<GitHubAssetDto>): GitHubAssetDto? {
        val apkAssets = assets.filter { asset ->
            asset.name.endsWith(".apk", ignoreCase = true) ||
                asset.contentType == "application/vnd.android.package-archive"
        }
        if (apkAssets.isEmpty()) return null
        if (apkAssets.size == 1) return apkAssets.first()

        val supportedAbis = AppUpdaterPlatform.getSupportedAbis()
        for (abi in supportedAbis) {
            val candidate = apkAssets.firstOrNull { asset ->
                asset.name.contains(abi, ignoreCase = true)
            }
            if (candidate != null) return candidate
        }

        return apkAssets.firstOrNull { asset ->
            val name = asset.name.lowercase()
            name.contains("universal") || name.contains("all")
        } ?: apkAssets.first()
    }
}

class AppUpdaterController internal constructor(
    private val scope: CoroutineScope,
) {
    private val _uiState = MutableStateFlow(AppUpdaterUiState())
    val uiState: StateFlow<AppUpdaterUiState> = _uiState.asStateFlow()

    private var autoCheckStarted = false

    fun ensureAutoCheckStarted() {
        if (autoCheckStarted || !AppFeaturePolicy.inAppUpdaterEnabled || !AppUpdaterPlatform.isSupported) {
            return
        }
        autoCheckStarted = true
        checkForUpdates(force = false, showNoUpdateFeedback = false)
    }

    fun checkForUpdates(force: Boolean, showNoUpdateFeedback: Boolean) {
        if (!AppFeaturePolicy.inAppUpdaterEnabled || !AppUpdaterPlatform.isSupported) {
            if (showNoUpdateFeedback) {
                scope.launch {
                    NuvioToastController.show(getString(Res.string.updates_not_available))
                }
            }
            return
        }

        scope.launch {
            _uiState.update { state ->
                state.copy(
                    isChecking = true,
                    errorMessage = null,
                    showUnknownSourcesDialog = false,
                    isDebugTest = false,
                )
            }

            val ignoredTag = AppUpdaterPlatform.getIgnoredTag()
            val result = AppUpdaterRepository.getLatestChannelUpdate()

            result.onSuccess { update ->
                val remoteNewer = VersionUtils.isRemoteNewer(update.tag, AppVersionConfig.VERSION_NAME)
                val ignored = ignoredTag != null && ignoredTag == update.tag
                val shouldShowDialog = force || (remoteNewer && !ignored)

                _uiState.update { state ->
                    state.copy(
                        isChecking = false,
                        update = update.takeIf { remoteNewer },
                        isUpdateAvailable = remoteNewer,
                        isDownloading = false,
                        downloadProgress = null,
                        downloadedApkPath = state.downloadedApkPath.takeIf { remoteNewer },
                        showDialog = shouldShowDialog,
                        showUnknownSourcesDialog = false,
                        errorMessage = null,
                    )
                }

                if (showNoUpdateFeedback && !remoteNewer) {
                    NuvioToastController.show(getString(Res.string.updates_latest_version))
                }
            }.onFailure { error ->
                _uiState.update { state ->
                    state.copy(
                        isChecking = false,
                        isDownloading = false,
                        downloadProgress = null,
                        downloadedApkPath = null,
                        update = null,
                        isUpdateAvailable = false,
                        showDialog = force && error !is NoChannelReleaseException,
                        showUnknownSourcesDialog = false,
                        errorMessage = if (force && error !is NoChannelReleaseException) {
                            error.message ?: getString(Res.string.updates_check_failed)
                        } else {
                            null
                        },
                    )
                }

                if (showNoUpdateFeedback || error is NoChannelReleaseException) {
                    NuvioToastController.show(error.message ?: getString(Res.string.updates_check_failed))
                }
            }
        }
    }

    fun dismissDialog() {
        _uiState.update { state ->
            state.copy(
                showDialog = false,
                showUnknownSourcesDialog = false,
                errorMessage = null,
            )
        }
    }

    fun ignoreThisVersion() {
        val tag = _uiState.value.update?.tag ?: return
        AppUpdaterPlatform.setIgnoredTag(tag)
        dismissDialog()
    }

    fun downloadUpdate() {
        val update = _uiState.value.update ?: return
        if (_uiState.value.isDebugTest) {
            runDebugDownloadTest()
            return
        }

        scope.launch {
            _uiState.update { state ->
                state.copy(
                    isDownloading = true,
                    downloadProgress = 0f,
                    errorMessage = null,
                )
            }

            AppUpdaterPlatform.downloadApk(
                assetUrl = update.assetUrl,
                assetName = update.assetName,
            ) { downloadedBytes, totalBytes ->
                val progress = if (totalBytes != null && totalBytes > 0L) {
                    (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                } else {
                    null
                }
                _uiState.update { state -> state.copy(downloadProgress = progress) }
            }.onSuccess { path ->
                _uiState.update { state ->
                    state.copy(
                        isDownloading = false,
                        downloadProgress = null,
                        downloadedApkPath = path,
                        errorMessage = null,
                    )
                }
                installDownloadedUpdate()
            }.onFailure { error ->
                _uiState.update { state ->
                    state.copy(
                        isDownloading = false,
                        downloadProgress = null,
                        downloadedApkPath = null,
                        errorMessage = error.message ?: getString(Res.string.updates_download_failed),
                        showDialog = true,
                    )
                }
            }
        }
    }

    fun installDownloadedUpdate() {
        val apkPath = _uiState.value.downloadedApkPath ?: return
        if (!AppUpdaterPlatform.canRequestPackageInstalls()) {
            _uiState.update { state -> state.copy(showUnknownSourcesDialog = true, showDialog = true) }
            return
        }

        AppUpdaterPlatform.installDownloadedApk(apkPath).onSuccess {
            _uiState.update { state -> state.copy(showUnknownSourcesDialog = false) }
        }.onFailure { error ->
            scope.launch {
                val fallbackMessage = error.message ?: getString(Res.string.updates_install_failed)
                _uiState.update { state ->
                    state.copy(
                        errorMessage = fallbackMessage,
                        showDialog = true,
                    )
                }
            }
        }
    }

    fun resumeInstallation() {
        if (AppUpdaterPlatform.canRequestPackageInstalls()) {
            installDownloadedUpdate()
        } else {
            AppUpdaterPlatform.openUnknownSourcesSettings()
        }
    }

    fun showDebugTestUpdate() {
        if (!AppUpdaterPlatform.isDebugBuild || !AppUpdaterPlatform.isSupported) return

        _uiState.value = AppUpdaterUiState(
            update = AppUpdate(
                tag = "9.9.9",
                title = "Nuvio 9.9.9",
                notes = """
                    A local preview of the new update experience.

                    - The banner pushes the app content down.
                    - Download progress fills the banner with the primary accent.
                    - Release notes live behind the info button.
                """.trimIndent(),
                releaseUrl = null,
                assetName = "Nuvio-debug-preview.apk",
                assetUrl = "debug://update-preview",
                assetSizeBytes = 185L * 1024L * 1024L,
            ),
            isUpdateAvailable = true,
            showDialog = true,
            isDebugTest = true,
        )
    }

    private fun runDebugDownloadTest() {
        scope.launch {
            _uiState.update { state ->
                state.copy(
                    isDownloading = true,
                    downloadProgress = 0f,
                    errorMessage = null,
                )
            }

            for (step in 1..100) {
                delay(35)
                _uiState.update { state -> state.copy(downloadProgress = step / 100f) }
            }

            _uiState.update { state ->
                state.copy(
                    isDownloading = false,
                    isUpdateAvailable = false,
                    downloadProgress = 1f,
                )
            }
        }
    }
}

@Composable
fun rememberAppUpdaterController(): AppUpdaterController {
    val scope = rememberCoroutineScope()
    return remember(scope) { AppUpdaterController(scope) }
}

internal fun formatFileSize(sizeBytes: Long): String {
    if (sizeBytes <= 0L) return "0 ${localizedByteUnit("B")}"
    val units = listOf("B", "KB", "MB", "GB")
    var value = sizeBytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    val roundedValue = if (value >= 10 || unitIndex == 0) {
        value.toInt().toString()
    } else {
        ((value * 10).toInt() / 10.0).toString()
    }
    return "$roundedValue ${localizedByteUnit(units[unitIndex])}"
}
