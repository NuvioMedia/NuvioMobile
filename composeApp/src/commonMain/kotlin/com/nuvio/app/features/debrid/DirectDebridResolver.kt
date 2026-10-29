package com.nuvio.app.features.debrid

import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamClientResolve
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.epochMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.debrid_missing_api_key
import nuvio.composeapp.generated.resources.debrid_not_cached
import nuvio.composeapp.generated.resources.debrid_resolve_failed
import nuvio.composeapp.generated.resources.debrid_stream_stale
import org.jetbrains.compose.resources.getString

object DirectDebridPlaybackResolver {
    private val localAddonStreamResolver = LocalDebridAddonStreamResolver()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val resolvedCache = mutableMapOf<String, CachedDirectDebridResolve>()
    private val inFlightResolves = mutableMapOf<String, Deferred<DirectDebridResolveResult>>()

    suspend fun resolve(stream: StreamItem, season: Int?, episode: Int?): DirectDebridResolveResult {
        if (!shouldResolveToPlayableStream(stream)) {
            return DirectDebridResolveResult.Stale
        }
        val cacheKey = stream.debridResolveCacheKey(season, episode)
        if (cacheKey == null) {
            return resolveUncached(stream, season, episode)
        }
        getCachedResult(cacheKey)?.let {
            return it
        }

        var ownsResolve = false
        val newResolve = scope.async(start = CoroutineStart.LAZY) {
            resolveUncached(stream, season, episode)
        }
        val activeResolve = mutex.withLock {
            getCachedResultLocked(cacheKey)?.let { cached ->
                return@withLock null to cached
            }
            val existing = inFlightResolves[cacheKey]
            if (existing != null) {
                existing to null
            } else {
                inFlightResolves[cacheKey] = newResolve
                ownsResolve = true
                newResolve to null
            }
        }
        activeResolve.second?.let {
            newResolve.cancel()
            return it
        }
        val deferred = activeResolve.first ?: return DirectDebridResolveResult.Error
        if (!ownsResolve) newResolve.cancel()
        if (ownsResolve) deferred.start()

        return try {
            val result = deferred.await()
            if (ownsResolve && result is DirectDebridResolveResult.Success) {
                mutex.withLock {
                    resolvedCache[cacheKey] = CachedDirectDebridResolve(
                        result = result,
                        cachedAtMs = epochMs(),
                    )
                }
            }
            result
        } finally {
            if (ownsResolve) {
                mutex.withLock {
                    if (inFlightResolves[cacheKey] === deferred) {
                        inFlightResolves.remove(cacheKey)
                    }
                }
            }
        }
    }

    suspend fun cachedPlayableStream(stream: StreamItem, season: Int?, episode: Int?): StreamItem? {
        if (!shouldResolveToPlayableStream(stream)) return null
        val cacheKey = stream.debridResolveCacheKey(season, episode) ?: return null
        return getCachedResult(cacheKey)
            ?.let { result -> stream.withResolvedDebridUrl(result) }
    }

    private suspend fun getCachedResult(cacheKey: String): DirectDebridResolveResult.Success? =
        mutex.withLock { getCachedResultLocked(cacheKey) }

    private fun getCachedResultLocked(cacheKey: String): DirectDebridResolveResult.Success? {
        val cached = resolvedCache[cacheKey] ?: return null
        val age = epochMs() - cached.cachedAtMs
        return if (age in 0..DEBRID_RESOLVE_CACHE_TTL_MS) {
            cached.result
        } else {
            resolvedCache.remove(cacheKey)
            null
        }
    }

    fun shouldResolveToPlayableStream(stream: StreamItem): Boolean {
        val settings = DebridSettingsRepository.snapshot()
        if (!settings.canResolvePlayableLinks) return false
        if (stream.needsLocalDebridResolve) {
            return stream.isInstalledAddonStream && localTorrentResolveCredential(settings) != null
        }
        if (!stream.isInstalledAddonStream || !stream.isDirectDebridStream || stream.playableDirectUrl != null) {
            return false
        }
        val providerId = DebridProviders.byId(stream.clientResolve?.service)?.id ?: return false
        return providerId == settings.activeResolverProviderId &&
            settings.apiKeyFor(providerId).isNotBlank() &&
            DebridProviderApis.apiFor(providerId) != null
    }

    private suspend fun resolveUncached(stream: StreamItem, season: Int?, episode: Int?): DirectDebridResolveResult {
        if (stream.needsLocalDebridResolve) {
            return localAddonStreamResolver.resolve(stream, season, episode)
        }
        val providerId = DebridProviders.byId(stream.clientResolve?.service)?.id
            ?: return DirectDebridResolveResult.Error
        val settings = DebridSettingsRepository.snapshot()
        if (providerId != settings.activeResolverProviderId) {
            return DirectDebridResolveResult.Stale
        }
        val apiKey = settings
            .apiKeyFor(providerId)
            .trim()
            .takeIf { it.isNotBlank() }
            ?: return DirectDebridResolveResult.MissingApiKey
        val api = DebridProviderApis.apiFor(providerId) ?: return DirectDebridResolveResult.Error
        return api.resolveClientStream(stream, apiKey, season, episode)
    }

    suspend fun resolveToPlayableStream(
        stream: StreamItem,
        season: Int?,
        episode: Int?,
    ): DirectDebridPlayableResult {
        if (!shouldResolveToPlayableStream(stream)) {
            return DirectDebridPlayableResult.Success(stream)
        }
        return when (val result = resolve(stream, season, episode)) {
            is DirectDebridResolveResult.Success -> DirectDebridPlayableResult.Success(stream.withResolvedDebridUrl(result))
            DirectDebridResolveResult.MissingApiKey -> DirectDebridPlayableResult.MissingApiKey
            DirectDebridResolveResult.NotCached -> DirectDebridPlayableResult.NotCached
            DirectDebridResolveResult.Stale -> DirectDebridPlayableResult.Stale
            DirectDebridResolveResult.Error -> DirectDebridPlayableResult.Error
        }
    }
}

private const val DEBRID_RESOLVE_CACHE_TTL_MS = 15L * 60L * 1000L

private data class CachedDirectDebridResolve(
    val result: DirectDebridResolveResult.Success,
    val cachedAtMs: Long,
)

sealed class DirectDebridPlayableResult {
    data class Success(val stream: StreamItem) : DirectDebridPlayableResult()
    data object MissingApiKey : DirectDebridPlayableResult()
    data object NotCached : DirectDebridPlayableResult()
    data object Stale : DirectDebridPlayableResult()
    data object Error : DirectDebridPlayableResult()
}

sealed class DirectDebridResolveResult {
    data class Success(
        val url: String,
        val filename: String?,
        val videoSize: Long?,
    ) : DirectDebridResolveResult()

    data object MissingApiKey : DirectDebridResolveResult()
    data object NotCached : DirectDebridResolveResult()
    data object Stale : DirectDebridResolveResult()
    data object Error : DirectDebridResolveResult()
}

fun DirectDebridPlayableResult.toastMessage(): String? =
    when (this) {
        is DirectDebridPlayableResult.Success -> null
        DirectDebridPlayableResult.MissingApiKey -> runBlocking { getString(Res.string.debrid_missing_api_key) }
        DirectDebridPlayableResult.NotCached -> runBlocking { getString(Res.string.debrid_not_cached) }
        DirectDebridPlayableResult.Stale -> runBlocking { getString(Res.string.debrid_stream_stale) }
        DirectDebridPlayableResult.Error -> runBlocking { getString(Res.string.debrid_resolve_failed) }
    }

private class LocalDebridAddonStreamResolver(
    private val fileSelector: TorboxFileSelector = TorboxFileSelector(),
    private val premiumizeFileSelector: PremiumizeDirectDownloadFileSelector = PremiumizeDirectDownloadFileSelector(),
) {
    suspend fun resolve(stream: StreamItem, season: Int?, episode: Int?): DirectDebridResolveResult {
        val account = localTorrentResolveCredential() ?: return DirectDebridResolveResult.MissingApiKey
        val apiKey = account.apiKey.trim()

        val hash = stream.infoHash?.trim()?.lowercase()
        if (stream.debridCacheStatus?.state == StreamDebridCacheState.NOT_CACHED) {
            return DirectDebridResolveResult.NotCached
        }
        if (
            !hash.isNullOrBlank() &&
            stream.debridCacheStatus?.state != StreamDebridCacheState.CACHED &&
            account.provider.supports(DebridProviderCapability.LocalTorrentCacheCheck)
        ) {
            when (LocalDebridService.isCached(account, hash)) {
                false -> return DirectDebridResolveResult.NotCached
                true, null -> Unit
            }
        }

        val magnet = DebridMagnetBuilder.fromStream(stream)
            ?: return DirectDebridResolveResult.Stale
        val resolve = stream.toResolveMetadata(season, episode, account.provider.id)

        return when (account.provider.id) {
            DebridProviders.TORBOX_ID -> resolveTorbox(stream, resolve, apiKey, magnet, season, episode)
            DebridProviders.PREMIUMIZE_ID -> resolvePremiumizeDirectDownload(
                apiKey = apiKey,
                source = magnet,
                resolve = resolve,
                season = season,
                episode = episode,
                fallbackFilename = stream.behaviorHints.filename,
                fallbackSize = stream.behaviorHints.videoSize,
                fileSelector = premiumizeFileSelector,
            )
            else -> DirectDebridResolveResult.Error
        }
    }

    private suspend fun resolveTorbox(
        stream: StreamItem,
        resolve: StreamClientResolve,
        apiKey: String,
        magnet: String,
        season: Int?,
        episode: Int?,
    ): DirectDebridResolveResult {
        return try {
            val create = TorboxApiClient.createTorrent(apiKey = apiKey, magnet = magnet)
            val torrentId = create.body?.takeIf { it.success != false }?.data?.resolvedTorrentId()
                ?: return create.toFailureForCreate()

            val torrent = TorboxApiClient.getTorrent(apiKey = apiKey, id = torrentId)
            if (!torrent.isSuccessful) {
                return DirectDebridResolveResult.Stale
            }
            val files = torrent.body?.data?.files.orEmpty()
            val file = fileSelector.selectFile(files, resolve, season, episode)
                ?: return DirectDebridResolveResult.Stale
            val fileId = file.id ?: return DirectDebridResolveResult.Stale

            val link = TorboxApiClient.requestDownloadLink(
                apiKey = apiKey,
                torrentId = torrentId,
                fileId = fileId,
            )
            if (!link.isSuccessful) {
                return DirectDebridResolveResult.Stale
            }
            val url = link.body?.data?.takeIf { it.isNotBlank() }
                ?: return DirectDebridResolveResult.Stale

            DirectDebridResolveResult.Success(
                url = url,
                filename = file.displayName().takeIf { it.isNotBlank() }
                    ?: stream.behaviorHints.filename?.takeIf { it.isNotBlank() },
                videoSize = file.size ?: stream.behaviorHints.videoSize,
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            DirectDebridResolveResult.Error
        }
    }
}

private fun localTorrentResolveCredential(
    settings: DebridSettings = DebridSettingsRepository.snapshot(),
): DebridServiceCredential? =
    settings.activeResolverCredential
        ?.takeIf { credential -> credential.provider.supports(DebridProviderCapability.LocalTorrentResolve) }

private fun StreamItem.debridResolveCacheKey(season: Int?, episode: Int?): String? {
    val resolve = clientResolve
    if (resolve == null && needsLocalDebridResolve) {
        val account = localTorrentResolveCredential() ?: return null
        val apiKey = account.apiKey.trim().takeIf { it.isNotBlank() } ?: return null
        val identity = infoHash ?: torrentMagnetUri ?: behaviorHints.filename ?: return null
        return listOf(
            account.provider.id,
            apiKey.stableFingerprint(),
            identity.trim().lowercase(),
            fileIdx?.toString().orEmpty(),
            behaviorHints.filename.orEmpty().trim().lowercase(),
            season?.toString().orEmpty(),
            episode?.toString().orEmpty(),
        ).joinToString("|")
    }
    resolve ?: return null
    val providerId = DebridProviders.byId(resolve.service)?.id ?: return null
    val settings = DebridSettingsRepository.snapshot()
    if (providerId != settings.activeResolverProviderId) return null
    val apiKey = settings
        .apiKeyFor(providerId)
        .trim()
        .takeIf { it.isNotBlank() }
        ?: return null
    val identity = resolve.infoHash
        ?: resolve.magnetUri
        ?: resolve.torrentName
        ?: resolve.filename
        ?: return null

    return listOf(
        providerId,
        apiKey.stableFingerprint(),
        identity.trim().lowercase(),
        resolve.fileIdx?.toString().orEmpty(),
        (resolve.filename ?: behaviorHints.filename).orEmpty().trim().lowercase(),
        (season ?: resolve.season)?.toString().orEmpty(),
        (episode ?: resolve.episode)?.toString().orEmpty(),
    ).joinToString("|")
}

private fun StreamItem.toResolveMetadata(season: Int?, episode: Int?, providerId: String): StreamClientResolve =
    StreamClientResolve(
        type = "torrent",
        infoHash = infoHash,
        fileIdx = fileIdx,
        magnetUri = torrentMagnetUri,
        sources = sources,
        filename = behaviorHints.filename,
        season = season,
        episode = episode,
        service = providerId,
        isCached = debridCacheStatus?.state == StreamDebridCacheState.CACHED,
    )

private fun DebridApiResponse<TorboxEnvelopeDto<TorboxCreateTorrentDataDto>>.toFailureForCreate(): DirectDebridResolveResult =
    when (status) {
        401, 403 -> DirectDebridResolveResult.Error
        409 -> DirectDebridResolveResult.NotCached
        else -> DirectDebridResolveResult.Stale
    }

private fun String.stableFingerprint(): String {
    val hash = fold(1125899906842597L) { acc, char -> (acc * 31L) + char.code }
    return hash.toULong().toString(16)
}

private fun StreamItem.withResolvedDebridUrl(result: DirectDebridResolveResult.Success): StreamItem =
    copy(
        url = result.url,
        externalUrl = null,
        behaviorHints = behaviorHints.mergeResolvedDebridHints(result),
    )

private fun StreamBehaviorHints.mergeResolvedDebridHints(result: DirectDebridResolveResult.Success): StreamBehaviorHints =
    copy(
        filename = result.filename ?: filename,
        videoSize = result.videoSize ?: videoSize,
    )
