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
    private val client by lazy { NewznabClient(indexerStateStorage) { UsenetRepository.engineSettings().searchAgent } }
    private val tester by lazy { NntpProviderTester() }
    private val service by lazy { BuiltInUsenetService(client, unplayable = unplayable, labels = ::localizedLabels) }
    private val sidecar by lazy { UsenetSidecar(appContext, { UsenetRepository.engineSettings() }, unplayable) }

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    private val ready: Boolean get() = ::appContext.isInitialized

    actual val isSupported: Boolean = true

    actual fun loadSources(profileId: Int): UsenetSourceConfiguration = sourceStorage.read(profileId)

    actual fun saveSources(profileId: Int, value: UsenetSourceConfiguration) = sourceStorage.update(profileId, value)

    actual fun removeProfile(profileId: Int) = sourceStorage.removeProfile(profileId)

    actual fun clearAll() {
        if (!ready) return
        sourceStorage.clear()
        indexerStateStorage.clear()
        unplayableStorage.clear()
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
            throw UsenetPlaybackException(e.message.orEmpty(), e.failure)
        } catch (e: Exception) {
            throw UsenetPlaybackException(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    actual fun release(url: String?) {
        if (ready) sidecar.release(url)
    }

    actual fun isEngineUrl(url: String?): Boolean = url != null && UsenetEngineUrls.isSession(url)

    actual fun onFirstFrame(url: String?) {
        if (ready && url != null && UsenetEngineUrls.isSession(url)) sidecar.firstFrame(url)
    }

    actual fun onPlaybackFailure(url: String?, failure: String?) {
        if (ready && url != null && UsenetEngineUrls.isSession(url)) sidecar.playbackFailed(url, failure)
    }

    actual fun onAppForegrounded() {
        UsenetSidecar.appForeground = true
        if (ready && UsenetRepository.isSupported && UsenetRepository.engineSettings().prewarmOnLaunch) sidecar.prewarm()
    }

    actual fun onAppBackgrounded() {
        UsenetSidecar.appForeground = false
        if (ready) sidecar.onAppBackgrounded()
    }

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
