package com.nuvio.app.features.streams

import com.nuvio.app.core.build.AppFeaturePolicy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

@Serializable
data class StreamSubtitle(
    val url: String,
    val language: String,
    val name: String? = null,
    val headers: Map<String, String>? = null
)

data class StreamItem(
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val externalUrl: String? = null,
    val sources: List<String> = emptyList(),
    val sourceName: String? = null,
    val addonName: String,
    val addonId: String,
    val addonLogo: String? = null,
    val streamType: String? = null,
    val behaviorHints: StreamBehaviorHints = StreamBehaviorHints(),
    val clientResolve: StreamClientResolve? = null,
    val debridCacheStatus: StreamDebridCacheStatus? = null,
    val externalSubtitles: List<StreamSubtitle> = emptyList(),
    val badges: List<StreamBadge> = emptyList(),
) {
    val streamLabel: String
        get() = name ?: runBlocking { getString(Res.string.stream_default_name) }

    val streamSubtitle: String?
        get() = description

    val directPlaybackUrl: String?
        get() = url?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * First URL that can be handed directly to a player or HTTP consumer.
     * `magnet:` and `torrent://` URLs are filtered out. `externalUrl` is not
     * a media URL in the Stremio SDK contract and must be opened externally.
     */
    val playableDirectUrl: String?
        get() = directPlaybackUrl?.takeIf { !it.isMagnetLink() && !it.isTorrentSchemeUrl() }

    val externalOpenUrl: String?
        get() = externalUrl
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.isMagnetLink() && !it.isTorrentSchemeUrl() }

    val shouldOpenExternally: Boolean
        get() = url.isNullOrBlank() &&
            infoHash.isNullOrBlank() &&
            clientResolve == null &&
            externalOpenUrl != null

    val torrentMagnetUri: String?
        get() = listOfNotNull(url, externalUrl)
            .firstOrNull { it.isMagnetLink() }

    val torrentSchemeUri: String?
        get() = listOfNotNull(url, externalUrl)
            .firstOrNull { it.isTorrentSchemeUrl() }

    val isDirectDebridStream: Boolean
        get() = clientResolve?.isDirectDebridCandidate == true

    val isInstalledAddonStream: Boolean
        get() = addonId.startsWith("addon:") || addonId.startsWith("plugin:")

    val isTorrentStream: Boolean
        get() = !isDirectDebridStream && (
            !infoHash.isNullOrBlank() ||
            url.isMagnetLink() ||
            externalUrl.isMagnetLink() ||
            url.isTorrentSchemeUrl() ||
            externalUrl.isTorrentSchemeUrl()
        )

    val isCachedDebridTorrentStream: Boolean
        get() = isTorrentStream && debridCacheStatus?.state == StreamDebridCacheState.CACHED

    val needsLocalDebridResolve: Boolean
        get() = isTorrentStream && playableDirectUrl == null

    val p2pInfoHash: String?
        get() = infoHash.normalizedInfoHash()
            ?: clientResolve?.infoHash.normalizedInfoHash()
            ?: torrentMagnetUri.extractBtihInfoHash()
            ?: torrentSchemeUri.extractTorrentSchemeInfoHash()

    val p2pFileIdx: Int?
        get() = fileIdx ?: torrentSchemeUri.extractTorrentSchemeFileIdx()

    val p2pTrackers: List<String>
        get() = sources
            .asSequence()
            .filter { it.startsWith("tracker:") }
            .map { it.removePrefix("tracker:").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    val isAddonDebridCandidate: Boolean
        get() = isInstalledAddonStream && (needsLocalDebridResolve || isDirectDebridStream)

    val hasPlayableSource: Boolean
        get() = url != null || infoHash != null || externalUrl != null || clientResolve != null
}

data class StreamBadge(
    val name: String,
    val imageURL: String = "",
    val tagColor: String = "",
    val tagStyle: String = "",
    val textColor: String = "",
    val borderColor: String = "",
)

fun normalizeStreamType(raw: String?): String? =
    raw?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

private fun String?.isMagnetLink(): Boolean =
    this?.trimStart()?.startsWith("magnet:", ignoreCase = true) == true

private fun String?.isTorrentSchemeUrl(): Boolean =
    this?.trimStart()?.startsWith("torrent://", ignoreCase = true) == true

private fun String?.extractTorrentSchemeInfoHash(): String? {
    val raw = this?.trimStart()?.takeIf { it.isTorrentSchemeUrl() } ?: return null
    return raw.removeRange(0, "torrent://".length)
        .substringBefore('/')
        .substringBefore('?')
        .trim()
        .takeIf { it.isValidInfoHash() }
}

private fun String?.extractTorrentSchemeFileIdx(): Int? {
    val raw = this?.trimStart()?.takeIf { it.isTorrentSchemeUrl() } ?: return null
    val path = raw.removeRange(0, "torrent://".length).substringBefore('?')
    if ('/' !in path) return null
    return path.substringAfter('/')
        .trim()
        .takeIf { segment -> segment.isNotEmpty() && segment.all { it.isDigit() } }
        ?.toIntOrNull()
}

private fun String.isValidInfoHash(): Boolean =
    (length == 40 && all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) ||
        (length == 32 && all { it in '2'..'7' || it.lowercaseChar() in 'a'..'z' })

private fun String?.normalizedInfoHash(): String? =
    this
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun String?.extractBtihInfoHash(): String? {
    val raw = this?.trim()?.takeIf { it.startsWith("magnet:", ignoreCase = true) } ?: return null
    val marker = "btih:"
    val markerIndex = raw.indexOf(marker, ignoreCase = true)
    if (markerIndex < 0) return null
    val start = markerIndex + marker.length
    val end = raw.indexOf('&', start).takeIf { it >= 0 } ?: raw.length
    return raw.substring(start, end)
        .trim()
        .takeIf { it.isNotEmpty() }
}

fun StreamItem.isSelectableForPlayback(debridEnabled: Boolean): Boolean =
    playableDirectUrl != null ||
        shouldOpenExternally ||
        (AppFeaturePolicy.p2pEnabled && needsLocalDebridResolve && p2pInfoHash != null) ||
        (debridEnabled && isAddonDebridCandidate)

data class StreamBehaviorHints(
    val bingeGroup: String? = null,
    val notWebReady: Boolean = false,
    val videoHash: String? = null,
    val videoSize: Long? = null,
    val filename: String? = null,
    val proxyHeaders: StreamProxyHeaders? = null,
)

data class StreamProxyHeaders(
    val request: Map<String, String>? = null,
    val response: Map<String, String>? = null,
)

enum class StreamDebridCacheState {
    CHECKING,
    CACHED,
    NOT_CACHED,
    UNKNOWN,
}

data class StreamDebridCacheStatus(
    val providerId: String,
    val providerName: String,
    val state: StreamDebridCacheState,
    val cachedName: String? = null,
    val cachedSize: Long? = null,
)

data class StreamClientResolve(
    val type: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val magnetUri: String? = null,
    val sources: List<String> = emptyList(),
    val torrentName: String? = null,
    val filename: String? = null,
    val mediaType: String? = null,
    val mediaId: String? = null,
    val mediaOnlyId: String? = null,
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val service: String? = null,
    val serviceIndex: Int? = null,
    val serviceExtension: String? = null,
    val isCached: Boolean? = null,
    val stream: StreamClientResolveStream? = null,
) {
    val isDirectDebridCandidate: Boolean
        get() = type.equals("debrid", ignoreCase = true) &&
            !service.isNullOrBlank() &&
            isCached == true
}

data class StreamClientResolveStream(
    val raw: StreamClientResolveRaw? = null,
)

data class StreamClientResolveRaw(
    val torrentName: String? = null,
    val filename: String? = null,
    val size: Long? = null,
    val folderSize: Long? = null,
    val tracker: String? = null,
    val indexer: String? = null,
    val network: String? = null,
    val parsed: StreamClientResolveParsed? = null,
)

data class StreamClientResolveParsed(
    val rawTitle: String? = null,
    val parsedTitle: String? = null,
    val year: Int? = null,
    val resolution: String? = null,
    val seasons: List<Int> = emptyList(),
    val episodes: List<Int> = emptyList(),
    val quality: String? = null,
    val hdr: List<String> = emptyList(),
    val codec: String? = null,
    val audio: List<String> = emptyList(),
    val channels: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val group: String? = null,
    val network: String? = null,
    val edition: String? = null,
    val duration: Long? = null,
    val bitDepth: String? = null,
    val extended: Boolean? = null,
    val theatrical: Boolean? = null,
    val remastered: Boolean? = null,
    val unrated: Boolean? = null,
)

data class AddonStreamGroup(
    val addonName: String,
    val addonId: String,
    val streams: List<StreamItem>,
    val isLoading: Boolean = false,
    val error: String? = null,
)

enum class StreamsEmptyStateReason {
    NoAddonsInstalled,
    NoCompatibleAddons,
    NoStreamsFound,
    StreamFetchFailed,
}

data class StreamsUiState(
    val requestToken: String? = null,
    val groups: List<AddonStreamGroup> = emptyList(),
    val activeAddonIds: Set<String> = emptySet(),
    val selectedFilter: String? = null,
    val isAnyLoading: Boolean = false,
    val emptyStateReason: StreamsEmptyStateReason? = null,
    val autoPlayStream: StreamItem? = null,
    val autoPlayCandidates: List<StreamItem> = emptyList(),
    val isDirectAutoPlayFlow: Boolean = false,
    val showDirectAutoPlayOverlay: Boolean = false,
    val overlayMessage: String? = null,
) {
    val filteredGroups: List<AddonStreamGroup>
        get() = if (selectedFilter == null) groups
                else groups.filter { it.addonId == selectedFilter }

    val allStreams: List<StreamItem>
        get() = filteredGroups.flatMap { it.streams }

    val hasAnyStreams: Boolean
        get() = groups.any { it.streams.isNotEmpty() }
}
