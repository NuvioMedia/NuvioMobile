package com.nuvio.app.features.usenet

import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/** iOS cannot launch the Usenet engine as a child process: the feature stays hidden. */
internal actual object UsenetPlatform {
    private val unchanged = MutableStateFlow(0).asStateFlow()

    actual val isSupported: Boolean = false

    actual fun loadSources(profileId: Int): UsenetSourceConfiguration = UsenetSourceConfiguration()

    actual fun saveSources(profileId: Int, value: UsenetSourceConfiguration) =
        throw UnsupportedOperationException("Usenet is not available on this device")

    actual fun removeProfile(profileId: Int) = Unit

    actual fun clearAll() = Unit

    actual fun loadEngineSettings(): UsenetConfiguration = UsenetConfiguration()

    actual fun saveEngineSettings(value: UsenetConfiguration) = Unit

    actual suspend fun testProvider(provider: UsenetProvider, allowPrivateNetwork: Boolean): UsenetProviderTestResult =
        UsenetProviderTestResult.UNREACHABLE

    actual suspend fun testIndexer(indexer: UsenetIndexer): Boolean = false

    actual val indexerStatusChanges: StateFlow<Int> = unchanged

    actual fun indexerStatus(indexer: UsenetIndexer): UsenetIndexerStatus = UsenetIndexerStatus()

    actual fun search(
        config: UsenetSourceConfiguration,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        groupName: String,
    ): Flow<UsenetSearchUpdate> = emptyFlow()

    actual val outcomeChanges: StateFlow<Int> = unchanged

    actual fun arrangeOutcomes(groups: List<AddonStreamGroup>, availableFirst: Boolean): List<AddonStreamGroup> = groups

    actual suspend fun prepare(stream: StreamItem, season: Int?, episode: Int?, profileId: Int): StreamItem =
        throw UsenetPlaybackException("Usenet is not available on this device")

    actual fun release(url: String?) = Unit

    actual fun markStartup(url: String, name: String) = Unit

    actual fun markStartupValue(url: String, name: String, value: Long) = Unit

    actual val lastStartupReport: StateFlow<String> = MutableStateFlow("").asStateFlow()

    actual fun restoredStream(nzbUrl: String, name: String, title: String?, size: Long?, publishedAt: Long?, series: Boolean): StreamItem? = null

    actual fun prefetch(owner: Any, stream: StreamItem, season: Int?, episode: Int?, profileId: Int) = Unit

    actual fun cancelPrefetch(owner: Any) = Unit

    actual fun previewRanking(profile: StreamRankingProfile, samples: List<RankingSample>, mediaType: String): RankingPreview? = null

    actual fun isEngineUrl(url: String?): Boolean = false

    actual fun onFirstFrame(url: String?) = Unit

    actual fun onPlaybackFailure(url: String?, failure: String?) = Unit

    actual fun onAppForegrounded() = Unit

    actual fun onAppBackgrounded() = Unit

    actual fun dateTime(epochMs: Long): String = clockTime(epochMs)

    actual fun clockTime(epochMs: Long): String = ""
}

internal actual fun isValidRankingPattern(value: String): Boolean =
    runCatching { Regex(value, RegexOption.IGNORE_CASE) }.isSuccess
