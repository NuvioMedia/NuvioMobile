package com.nuvio.app.features.servers.silo

import co.touchlab.kermit.Logger
import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.nuvio.app.features.servers.ServerException
import com.nuvio.app.features.servers.ServerFailure
import com.nuvio.app.features.servers.ServerRepository
import io.ktor.http.Url
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.encodeURLParameter
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

internal enum class SiloArtworkKind { POSTER, BACKGROUND, LOGO }

/** The title a Silo artwork link belongs to, carried in the link's fragment (never sent to the server). */
internal data class SiloArtworkMark(val connectionId: String, val contentId: String, val kind: SiloArtworkKind)

/**
 * Silo signs its artwork links for a few hours. Each link the app keeps (rows, Continue watching, Library) names its
 * title, so an expired one can be swapped for a fresh link to the same picture.
 */
internal object SiloArtworkLink {
    private const val MARK = "nuvio-silo="

    fun mark(url: String, connectionId: String, contentId: String, kind: SiloArtworkKind): String {
        if ('#' in url || contentId.isBlank()) return url
        return "$url#$MARK${encode(connectionId)}.${encode(contentId)}.${kind.name.lowercase()}"
    }

    fun parse(url: String): SiloArtworkMark? {
        val value = url.substringAfter('#', "").split('&').firstOrNull { it.startsWith(MARK) }?.removePrefix(MARK)
            ?: return null
        val parts = value.split('.')
        if (parts.size != 3) return null
        val kind = SiloArtworkKind.entries.firstOrNull { it.name.equals(parts[2], ignoreCase = true) } ?: return null
        val connectionId = decode(parts[0])?.takeIf { it.isNotBlank() } ?: return null
        val contentId = decode(parts[1])?.takeIf { it.isNotBlank() } ?: return null
        return SiloArtworkMark(connectionId, contentId, kind)
    }

    /** Seconds since 1970 when Silo stops accepting [url]; null when the link carries no expiry of its own. */
    fun expiresAt(url: String): Long? =
        runCatching { Url(url.substringBefore('#')).parameters["exp"]?.toLongOrNull() }.getOrNull()

    fun withoutMark(url: String): String = url.substringBefore('#')

    private fun encode(value: String): String = value.encodeURLParameter().replace(".", "%2E")

    private fun decode(value: String): String? = runCatching { value.decodeURLQueryComponent() }.getOrNull()
}

/** Fresh Silo artwork links by title, fetched at most a few at a time and kept until shortly before they expire. */
internal class SiloArtworkRefresher(
    private val fetch: suspend (SiloArtworkMark) -> Map<SiloArtworkKind, String>? = ::fetchFromServer,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private class Entry(val links: Map<SiloArtworkKind, String>?, val untilMs: Long)

    private val lock = SynchronizedObject()
    private val entries = mutableMapOf<String, Entry>()
    private val locks = mutableMapOf<String, Mutex>()
    private val pausedUntil = mutableMapOf<String, Long>()
    private val permits = Semaphore(MAX_AT_ONCE)
    private val pace = Mutex()
    private var nextFetchMs = 0L

    /** A link to replace [stale] with, or null when there is no newer one. */
    suspend fun fresh(mark: SiloArtworkMark, stale: String): String? {
        val title = "${mark.connectionId}|${mark.contentId}"
        val titleLock = synchronized(lock) { locks.getOrPut(title) { Mutex() } }
        val entry = titleLock.withLock {
            synchronized(lock) { entries[title] }?.takeIf { now() < it.untilMs }
                ?: load(mark).also { loaded -> synchronized(lock) { entries[title] = loaded } }
        }
        val fresh = entry.links?.get(mark.kind) ?: return null
        return fresh.takeIf { it != SiloArtworkLink.withoutMark(stale) }
    }

    /** Silo allows about 20 calls a second per user, shared with browsing and playback: a few lookups at a time. */
    private suspend fun load(mark: SiloArtworkMark): Entry {
        synchronized(lock) {
            if (entries.size >= MAX_TITLES) {
                entries.clear()
                locks.clear()
            }
            pausedUntil[mark.connectionId]
        }?.takeIf { now() < it }?.let { return Entry(null, it) }
        var unreachable = true
        val links = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            try {
                permits.withPermit {
                    awaitSlot()
                    fetch(mark).also { unreachable = false }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ServerException) {
                log.w { "Fresh Silo artwork failed: ${error.failure} ${error.siloCode.orEmpty()}" }
                unreachable = error.failure == ServerFailure.UNREACHABLE || error.siloCode == "rate_limited"
                null
            } catch (error: Exception) {
                log.w { "Fresh Silo artwork failed: ${error::class.simpleName}" }
                unreachable = false
                null
            }
        }
        if (links == null) {
            val until = now() + RETRY_AFTER_MS
            if (unreachable) synchronized(lock) { pausedUntil[mark.connectionId] = until }
            return Entry(null, until)
        }
        val expiry = links.values.mapNotNull(SiloArtworkLink::expiresAt).minOrNull()
        val until = expiry?.let { it * 1_000L - RENEW_BEFORE_MS } ?: (now() + KEEP_MS)
        return Entry(links, until.coerceIn(now() + RETRY_AFTER_MS, now() + KEEP_MS))
    }

    private suspend fun awaitSlot() {
        val wait = pace.withLock {
            val start = maxOf(now(), nextFetchMs)
            nextFetchMs = start + FETCH_SPACING_MS
            start - now()
        }
        if (wait > 0) delay(wait)
    }

    private companion object {
        val log = Logger.withTag("SiloArtwork")
        const val MAX_AT_ONCE = 3
        const val FETCH_SPACING_MS = 200L
        const val MAX_TITLES = 512
        const val FETCH_TIMEOUT_MS = 6_000L
        const val RETRY_AFTER_MS = 60_000L
        const val RENEW_BEFORE_MS = 5 * 60_000L
        const val KEEP_MS = 60 * 60_000L
    }
}

private suspend fun fetchFromServer(mark: SiloArtworkMark): Map<SiloArtworkKind, String>? =
    ServerRepository.call(mark.connectionId) { provider, session ->
        (provider as? SiloProvider)?.freshArtwork(session, mark.contentId)
    }

/** Swaps an expired (or refused) Silo artwork link for a fresh one before the picture is downloaded. */
internal class SiloArtworkInterceptor(
    private val refresher: SiloArtworkRefresher = SiloArtworkRefresher(),
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = request.data as? String ?: return chain.proceed()
        val mark = SiloArtworkLink.parse(url) ?: return chain.proceed()
        val expiresAt = SiloArtworkLink.expiresAt(url)
        if (expiresAt != null && expiresAt - EXPIRY_MARGIN_S <= nowSeconds()) {
            refresher.fresh(mark, url)?.let { fresh ->
                return chain.withRequest(request.newBuilder().data(fresh).build()).proceed()
            }
        }
        val result = chain.proceed()
        val refused = (result as? ErrorResult)?.throwable as? HttpException
        if (refused == null || refused.response.code !in REFUSED) return result
        val fresh = refresher.fresh(mark, url) ?: return result
        return chain.withRequest(request.newBuilder().data(fresh).build()).proceed()
    }

    private companion object {
        const val EXPIRY_MARGIN_S = 60L
        val REFUSED = setOf(403, 404, 410)
    }
}
