package com.nuvio.app.features.cloudstream

import kotlinx.serialization.Serializable

@Serializable
data class CloudStreamRepositoryManifestDto(
    val name: String? = null,
    val description: String? = null,
    val iconUrl: String? = null,
    val manifestVersion: Int? = null,
    val pluginLists: List<String> = emptyList(),
)

@Serializable
data class CloudStreamPluginMetadataDto(
    val url: String? = null,
    val status: Int? = null,
    val version: Int? = null,
    val name: String? = null,
    val internalName: String? = null,
    val authors: List<String>? = null,
    val description: String? = null,
    val fileSize: Long? = null,
    val repositoryUrl: String? = null,
    val language: String? = null,
    val tvTypes: List<String>? = null,
    val iconUrl: String? = null,
    val apiVersion: Int? = null,
    val fileHash: String? = null,
)

data class CloudStreamRepositoryManifest(
    val sourceUrl: String,
    val name: String,
    val description: String?,
    val iconUrl: String?,
    val manifestVersion: Int,
    val pluginListUrls: List<String>,
)

data class CloudStreamPluginId(
    val normalizedRepositoryUrl: String,
    val internalName: String,
) {
    val value: String = "$normalizedRepositoryUrl#$internalName"
    val storageKey: String = sha256Hex(value.encodeToByteArray())
}

data class CloudStreamPluginMetadata(
    val id: CloudStreamPluginId,
    val repositoryManifestUrl: String,
    val packageUrl: String,
    val status: CloudStreamPluginStatus,
    val version: Int,
    val name: String,
    val internalName: String,
    val authors: List<String>,
    val description: String?,
    val fileSize: Long?,
    val projectUrl: String?,
    val language: String?,
    val tvTypes: List<CloudStreamTvType>,
    val rawTvTypes: List<String>,
    val iconUrl: String?,
    val apiVersion: Int,
    val fileHash: CloudStreamFileHash?,
)

enum class CloudStreamPluginStatus {
    Down,
    Ok,
    Slow,
    BetaOnly,
    Unknown,
    ;

    val canInstall: Boolean
        get() = this == Ok || this == Slow || this == BetaOnly

    companion object {
        fun fromWireValue(value: Int?): CloudStreamPluginStatus = when (value) {
            0 -> Down
            1 -> Ok
            2 -> Slow
            3 -> BetaOnly
            else -> Unknown
        }
    }
}

enum class CloudStreamTvType {
    Movie,
    TvSeries,
    Anime,
    AnimeMovie,
    Ova,
    Live,
    Documentary,
    Cartoon,
    AsianDrama,
    Music,
    Torrent,
    Other,
    ;

    val nuvioType: String
        get() = when (this) {
            Movie, AnimeMovie, Documentary -> "movie"
            TvSeries, Anime, Ova, Cartoon, AsianDrama -> "series"
            Live, Music -> "live"
            Torrent, Other -> "other"
        }

    companion object {
        fun fromWireValue(value: String): CloudStreamTvType = when (value.trim().lowercase()) {
            "movie" -> Movie
            "tvseries", "series", "show" -> TvSeries
            "anime" -> Anime
            "animemovie" -> AnimeMovie
            "ova" -> Ova
            "live", "livetv" -> Live
            "documentary" -> Documentary
            "cartoon" -> Cartoon
            "asiandrama" -> AsianDrama
            "music" -> Music
            "torrent" -> Torrent
            else -> Other
        }
    }
}

data class CloudStreamFileHash(
    val algorithm: String,
    val hex: String,
) {
    val wireValue: String = "$algorithm-$hex"

    fun matches(bytes: ByteArray): Boolean = when (algorithm.lowercase()) {
        "sha256" -> sha256Hex(bytes).equals(hex, ignoreCase = true)
        else -> false
    }

    companion object {
        fun parse(value: String?): CloudStreamFileHash? {
            val normalized = value?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
            val separator = normalized.indexOf('-')
            if (separator <= 0 || separator == normalized.lastIndex) return null
            val algorithm = normalized.substring(0, separator)
            val hex = normalized.substring(separator + 1)
            if (algorithm != "sha256" || hex.length != 64 || hex.any { it !in '0'..'9' && it !in 'a'..'f' }) {
                return null
            }
            return CloudStreamFileHash(algorithm = algorithm, hex = hex)
        }
    }
}

internal fun CloudStreamFileHash?.verifies(bytes: ByteArray): Boolean = this?.matches(bytes) == true

/**
 * Checks the checksum recorded for the installed artifact, not the latest repository
 * metadata. A null hash means the repository supplied no checksum; callers must still
 * validate the CS3 archive structure before accepting the package.
 */
internal fun matchesInstalledCloudStreamPackage(installedFileHash: String?, bytes: ByteArray): Boolean =
    installedFileHash == null || CloudStreamFileHash.parse(installedFileHash)?.matches(bytes) == true

enum class CloudStreamRuntimeKind {
    PrecompiledCrossPlatformAdapter,
    AndroidDex,
    UnsupportedAndroidDex,
}

enum class CloudStreamPlatformSupport {
    AndroidAndIos,
    AndroidOnly,
    Unsupported,
}

data class CloudStreamCompatibility(
    val runtimeKind: CloudStreamRuntimeKind,
    val platformSupport: CloudStreamPlatformSupport,
    val adapterId: String? = null,
    val reason: String,
) {
    val isRunnable: Boolean
        get() = when (runtimeKind) {
            CloudStreamRuntimeKind.PrecompiledCrossPlatformAdapter ->
                platformSupport == CloudStreamPlatformSupport.AndroidAndIos
            CloudStreamRuntimeKind.AndroidDex ->
                platformSupport == CloudStreamPlatformSupport.AndroidOnly
            CloudStreamRuntimeKind.UnsupportedAndroidDex -> false
        }
}

data class CloudStreamInstalledPlugin(
    val metadata: CloudStreamPluginMetadata,
    val installedVersion: Int,
    val enabled: Boolean,
    val verified: Boolean,
    val compatibility: CloudStreamCompatibility,
    val installedAtEpochMs: Long,
)

enum class CloudStreamInstallAction {
    Install,
    Update,
    Reinstall,
    None,
}

fun CloudStreamPluginMetadata.installAction(installed: CloudStreamInstalledPlugin?): CloudStreamInstallAction = when {
    installed == null -> CloudStreamInstallAction.Install
    version > installed.installedVersion -> CloudStreamInstallAction.Update
    version < installed.installedVersion -> CloudStreamInstallAction.Reinstall
    !installed.verified -> CloudStreamInstallAction.Reinstall
    else -> CloudStreamInstallAction.None
}

@Serializable
internal data class StoredCloudStreamState(
    val repositories: List<StoredCloudStreamRepository> = emptyList(),
    val plugins: List<StoredCloudStreamPlugin> = emptyList(),
    val securityWarningAccepted: Boolean = false,
    val hideAllHomeCatalogs: Boolean = false,
    val hiddenHomeCatalogProviderIds: Set<String> = emptySet(),
    val groupStreamsByRepository: Boolean = false,
)

@Serializable
internal data class StoredCloudStreamRepository(
    val manifestUrl: String,
    val name: String,
    val description: String? = null,
    val iconUrl: String? = null,
    val manifestVersion: Int = 1,
    val pluginListUrls: List<String> = emptyList(),
    val lastRefreshedAtEpochMs: Long = 0L,
)

@Serializable
internal data class StoredCloudStreamPlugin(
    val repositoryManifestUrl: String,
    val packageUrl: String,
    val status: String,
    val availableVersion: Int,
    val installedVersion: Int? = null,
    val name: String,
    val internalName: String,
    val authors: List<String> = emptyList(),
    val description: String? = null,
    val fileSize: Long? = null,
    val projectUrl: String? = null,
    val language: String? = null,
    val rawTvTypes: List<String> = emptyList(),
    val iconUrl: String? = null,
    val apiVersion: Int = 1,
    val fileHash: String? = null,
    val installedFileHash: String? = null,
    val enabled: Boolean = false,
    val verified: Boolean = false,
    val installedAtEpochMs: Long = 0L,
)

@Serializable
data class CloudStreamCs3Manifest(
    val pluginClassName: String? = null,
    val name: String? = null,
    val version: Int? = null,
    val requiresResources: Boolean = false,
)

@Serializable
data class CloudStreamSubtitle(
    val url: String,
    val language: String,
    val name: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

data class CloudStreamPlaybackSource(
    val name: String,
    val url: String,
    val quality: Int? = null,
    val referer: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<CloudStreamSubtitle> = emptyList(),
    val isHls: Boolean = false,
    val isDash: Boolean = false,
)

data class CloudStreamSearchItem(
    val providerId: String,
    val data: String,
    val name: String,
    val type: CloudStreamTvType,
    val posterUrl: String? = null,
    val backgroundUrl: String? = null,
    val description: String? = null,
    val year: Int? = null,
)

data class CloudStreamEpisode(
    val data: String,
    val name: String,
    val season: Int? = null,
    val episode: Int? = null,
    val posterUrl: String? = null,
    val description: String? = null,
)

data class CloudStreamLoadItem(
    val providerId: String,
    val data: String,
    val name: String,
    val type: CloudStreamTvType,
    val posterUrl: String? = null,
    val backgroundUrl: String? = null,
    val description: String? = null,
    val year: Int? = null,
    val ratingPercent: Int? = null,
    val tags: List<String> = emptyList(),
    val episodes: List<CloudStreamEpisode> = emptyList(),
)

sealed interface CloudStreamProviderError {
    val message: String

    data class Http(override val message: String, val status: Int? = null) : CloudStreamProviderError
    data class Parse(override val message: String) : CloudStreamProviderError
    data class Runtime(override val message: String) : CloudStreamProviderError
    data class Extractor(override val message: String) : CloudStreamProviderError
    data class NoLinks(override val message: String = "No links found") : CloudStreamProviderError
}
