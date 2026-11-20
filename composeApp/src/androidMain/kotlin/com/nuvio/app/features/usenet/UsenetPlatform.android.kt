package com.nuvio.app.features.usenet

import android.content.Context
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.usenet_indexer_search_failed
import nuvio.composeapp.generated.resources.usenet_indexer_search_paused
import nuvio.composeapp.generated.resources.usenet_size_pack
import nuvio.composeapp.generated.resources.usenet_size_pack_share
import nuvio.composeapp.generated.resources.usenet_size_season
import nuvio.composeapp.generated.resources.usenet_size_season_share
import org.jetbrains.compose.resources.getString

/** Android runs the engine as a child process, launched from the APK's native library directory. */
internal actual object UsenetPlatform {
    private lateinit var appContext: Context
    private val sourceStorage by lazy { UsenetSourceStorage(appContext) }
    private val engineStorage by lazy { UsenetEngineSettingsStorage(appContext) }
    private val indexerStateStorage by lazy { SharedPreferencesNewznabStateStorage(appContext) }
    private val unplayableStorage by lazy { SharedPreferencesUnplayableReleaseStorage(appContext) }
    // Exists from the start, so screens can watch it before the stored list is first read.
    private val outcomeRevision = MutableStateFlow(0)
    private val unplayable by lazy { UnplayableReleases(unplayableStorage, outcomeRevision) }
    private val statsStorage by lazy { SharedPreferencesUsenetStatsStorage(appContext) }
    private val sourceStats by lazy { UsenetSourceStats(statsStorage) }
    private val noStats = MutableStateFlow(0)
    private val client by lazy { NewznabClient(indexerStateStorage, sourceStats) { UsenetRepository.engineSettings().searchAgent } }
    private val tester by lazy { NntpProviderTester() }
    private val tuner by lazy { NntpProviderTuner(tester) }
    private val service by lazy { BuiltInUsenetService(client, unplayable = unplayable, labels = ::localizedLabels, stats = sourceStats) }
    private val sidecar by lazy { UsenetSidecar(appContext, { UsenetRepository.engineSettings() }, unplayable, sourceStats) }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        UsenetStartupReports.initialize(appContext)
    }

    private val ready: Boolean get() = ::appContext.isInitialized

    actual val isSupported: Boolean = true

    // The statistics learn the providers' server URLs, so engine reports, which name servers by URL, count for them.
    actual fun loadSources(profileId: Int): UsenetSourceConfiguration =
        sourceStorage.read(profileId).also { sourceStats.knowProviders(it.providers) }

    actual fun saveSources(profileId: Int, value: UsenetSourceConfiguration) {
        sourceStorage.update(profileId, value)
        sourceStats.knowProviders(value.providers)
    }

    actual fun removeProfile(profileId: Int) = sourceStorage.removeProfile(profileId)

    actual fun clearAll() {
        if (!ready) return
        sourceStorage.clear()
        indexerStateStorage.clear()
        unplayableStorage.clear()
        UsenetStartupReports.clear()
        statsStorage.clearAll()
    }

    actual fun loadEngineSettings(): UsenetConfiguration = if (ready) engineStorage.read() else UsenetConfiguration()

    actual fun saveEngineSettings(value: UsenetConfiguration) {
        engineStorage.update(value)
        sidecar.settingsChanged()
    }

    actual suspend fun testProvider(provider: UsenetProvider, allowPrivateNetwork: Boolean): UsenetProviderTestResult {
        if (runCatching { provider.validate() }.isFailure) return UsenetProviderTestResult.INVALID
        return tester.test(provider, allowPrivateNetwork)
    }

    actual suspend fun testIndexer(indexer: UsenetIndexer): Boolean = withContext(Dispatchers.IO) {
        try {
            indexer.validate()
            client.verify(indexer)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    actual suspend fun tuneProvider(
        provider: UsenetProvider,
        allowPrivateNetwork: Boolean,
        pipeline: Int,
        onProgress: (TuneProgress) -> Unit,
    ): TuneOutcome {
        if (runCatching { provider.validate() }.isFailure) return TuneOutcome.Failed(TuneFailure.CONNECT, UsenetProviderTestResult.INVALID)
        return tuner.tune(provider, allowPrivateNetwork, pipeline, onProgress)
    }

    actual val indexerStatusChanges: StateFlow<Int> get() = client.statusChanges

    actual fun indexerStatus(indexer: UsenetIndexer): UsenetIndexerStatus = client.status(indexer)

    actual fun search(
        config: UsenetSourceConfiguration,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        groupName: String,
    ): Flow<UsenetSearchUpdate> {
        if (!ready || !config.ready) return emptyFlow()
        // The engine is likely needed soon: start Go and its trust roots while the indexers answer.
        sidecar.prewarmForSources()
        return service.search(config, type, videoId, season, episode, groupName, BUILT_IN_USENET_GROUP_ID)
            .flowOn(Dispatchers.IO)
    }

    actual val outcomeChanges: StateFlow<Int> = outcomeRevision.asStateFlow()

    actual fun arrangeOutcomes(groups: List<AddonStreamGroup>, availableFirst: Boolean): List<AddonStreamGroup> {
        if (!ready || groups.none { it.addonId == BUILT_IN_USENET_GROUP_ID }) return groups
        return BuiltInUsenetService.arrangeOutcomes(groups, BUILT_IN_USENET_GROUP_ID, unplayable::contains,
            unplayable::played, availableFirst, unplayable::sibling)
    }

    actual suspend fun prepare(stream: StreamItem, season: Int?, episode: Int?, profileId: Int): StreamItem {
        check(ready) { "Usenet is not initialized" }
        return try {
            sidecar.resolve(stream, season, episode, profileId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: UsenetPreparationException) {
            val scope = when (e.scope) {
                UsenetPreparationException.Scope.SOURCE -> UsenetFailureScope.SOURCE
                UsenetPreparationException.Scope.PROVIDER -> UsenetFailureScope.PROVIDER
                UsenetPreparationException.Scope.ENGINE -> UsenetFailureScope.ENGINE
            }
            throw UsenetPlaybackException(e.message.orEmpty(), e.failure, scope)
        } catch (e: Exception) {
            throw UsenetPlaybackException(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    actual fun release(url: String?) {
        if (ready) sidecar.release(url)
    }

    actual fun restoredStream(nzbUrl: String, name: String, title: String?, size: Long?, publishedAt: Long?, series: Boolean): StreamItem? {
        if (!ready) return null
        val config = UsenetRepository.sources()
        val restored = BuiltInUsenetService.restoredStream(nzbUrl, name, title, size, publishedAt, config, series)
            ?.takeUnless(unplayable::contains) ?: return null
        // No search lists it: its download and playback count for the indexer its link names.
        UsenetKeptLinks.indexerOf(nzbUrl, config)?.let { indexer ->
            sourceStats.remember(listOfNotNull(restored.nzbUrl), indexer.id, if (series) ContentKind.SHOW else ContentKind.MOVIE)
        }
        return restored
    }

    actual fun prefetch(owner: Any, stream: StreamItem, season: Int?, episode: Int?, profileId: Int) {
        if (ready) sidecar.prefetch(owner, stream, season, episode, profileId)
    }

    actual fun cancelPrefetch(owner: Any) {
        if (ready) sidecar.cancelPrefetch(owner)
    }

    actual fun isEngineUrl(url: String?): Boolean = url != null && UsenetEngineUrls.isSession(url)

    actual fun onFirstFrame(url: String?) {
        if (ready && url != null && UsenetEngineUrls.isSession(url)) sidecar.firstFrame(url)
    }

    actual fun markStartup(url: String, name: String) {
        if (ready && UsenetEngineUrls.isSession(url)) sidecar.markStartup(url, name)
    }

    actual fun markStartupValue(url: String, name: String, value: Long) {
        if (ready && UsenetEngineUrls.isSession(url)) sidecar.markStartupValue(url, name, value)
    }

    actual val lastStartupReport: StateFlow<String> get() = UsenetStartupReports.latest

    actual fun onPlaybackFailure(url: String?, failure: String?) {
        if (ready && url != null && UsenetEngineUrls.isSession(url)) sidecar.playbackFailed(url, failure)
    }

    actual fun onAppForegrounded() {
        UsenetSidecar.appForeground = true
        if (ready && UsenetRepository.isSupported && UsenetRepository.engineSettings().prewarmOnLaunch) sidecar.prewarm()
    }

    actual fun onAppBackgrounded() {
        UsenetSidecar.appForeground = false
        if (ready) {
            sidecar.onAppBackgrounded()
            // Counts held back from frequent saves are written before the app may be stopped.
            sourceStats.flush()
        }
    }

    actual val statsRevision: StateFlow<Int> get() = if (ready) sourceStats.revision else noStats.asStateFlow()

    actual fun stats(indexers: List<UsenetIndexer>, providers: List<UsenetProvider>): UsenetStatsSnapshot {
        if (!ready) return UsenetStatsSnapshot()
        sourceStats.load()
        sourceStats.flush()
        val period = sourceStats.period.value
        val content = sourceStats.content.value
        return UsenetStatsSnapshot(
            period = period,
            content = content,
            indexers = indexers.associate { indexer ->
                indexer.id to IndexerStatsUi(
                    sourceStats.indexer(indexer.id, period, content),
                    sourceStats.indexerToday(indexer.id, content),
                    sourceStats.indexerSince(indexer.id),
                )
            },
            providers = providers.associate { provider ->
                provider.id to ProviderStatsUi(sourceStats.provider(provider.id, period, content), sourceStats.providerSince(provider.id))
            },
        )
    }

    actual fun setStatsPeriod(period: StatsPeriod) {
        if (ready) sourceStats.setPeriod(period)
    }

    actual fun setStatsContent(content: StatsContent) {
        if (ready) sourceStats.setContent(content)
    }

    actual fun resetStats() {
        if (ready) sourceStats.reset()
    }

    actual fun previewRanking(profile: StreamRankingProfile, samples: List<RankingSample>, mediaType: String): RankingPreview? {
        // Indexer names sort by their priority, as the indexers of real results do.
        val indexerOrder = UsenetRepository.sources().indexers.sortedBy { it.priority }
            .mapIndexed { index, indexer -> indexer.name.lowercase(java.util.Locale.ROOT) to index }.toMap()
        return RankingPreviewer.preview(profile, samples, mediaType, indexerOrder)
    }

    actual fun dateTime(epochMs: Long): String =
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM).format(java.util.Date(epochMs))

    actual fun clockTime(epochMs: Long): String =
        java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(epochMs))

    private suspend fun localizedLabels(): UsenetLabels = runCatching {
        UsenetLabels(
            indexerPaused = getString(Res.string.usenet_indexer_search_paused),
            indexerFailed = getString(Res.string.usenet_indexer_search_failed),
            sizeSeasonShare = getString(Res.string.usenet_size_season_share),
            sizePackShare = getString(Res.string.usenet_size_pack_share),
            sizeSeason = getString(Res.string.usenet_size_season),
            sizePack = getString(Res.string.usenet_size_pack),
        )
    }.getOrDefault(UsenetLabels())
}
