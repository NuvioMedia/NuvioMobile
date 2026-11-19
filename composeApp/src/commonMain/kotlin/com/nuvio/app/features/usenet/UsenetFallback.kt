package com.nuvio.app.features.usenet

import co.touchlab.kermit.Logger
import com.nuvio.app.features.streams.StreamItem
import kotlin.coroutines.coroutineContext
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Automatic Usenet fallback for one selection: a finite snapshot of the shown order after the
 * selected release, holding Usenet releases only. It never wraps and never retries a release
 * that failed. Torrent and other selections never fall back.
 */
internal class UsenetFallbackSession(
    selected: StreamItem,
    orderedStreams: List<StreamItem>,
    private val maxAttempts: Int = 5,
    private val nowMs: () -> Long = ::monotonicMs,
    private val isEnabled: () -> Boolean = { false },
    /** Releases that already failed for good with these providers; trying them again only costs time. */
    private val isUnplayable: (StreamItem) -> Boolean = { false },
    private val messages: UsenetFallbackMessages = UsenetFallbackMessages(),
) {
    private val selectedIsUsenet = selected.isUsenet
    private var playbackUrl: String? = null
    val enabled: Boolean get() = selectedIsUsenet && isEnabled()
    private val attempted = mutableSetOf<FallbackKey>()
    private val remaining: ArrayDeque<StreamItem>
    private var preparationSpentMs = 0L
    private var engineFailed = false
    private val failedProviders = mutableSetOf<List<String>>()

    /** The last failure as the engine or the app worded it. */
    var lastFailure: Throwable? = null
        private set

    /** Why the last release failed, in a few words, for the next attempt's message. */
    var lastFailureReason: String? = null
        private set

    val budgetExhausted: Boolean get() = preparationSpentMs >= BUDGET_MS

    var attempts: Int = 0
        private set

    /** The release being prepared or played. */
    var current: StreamItem = selected
        private set

    val canAdvance: Boolean
        get() = enabled && !engineFailed && !budgetExhausted && attempts < maxAttempts.coerceIn(0, 50) &&
            remaining.any(::eligible)

    private fun eligible(stream: StreamItem) = stream.canAutoFallback() && stream.fallbackKey() !in attempted &&
        stream.nzbServers.sorted() !in failedProviders && !isUnplayable(stream)

    init {
        val key = selected.fallbackKey()
        val index = orderedStreams.indexOf(selected).takeIf { it >= 0 }
            ?: orderedStreams.indexOfFirst { it.fallbackKey() == key }
        remaining = ArrayDeque(orderedStreams.drop(if (index < 0) 0 else index + 1))
        attempted += key
    }

    fun next(): StreamItem? {
        if (!canAdvance) return null
        while (attempts < maxAttempts.coerceIn(0, 50) && remaining.isNotEmpty()) {
            val candidate = remaining.removeFirst()
            if (!eligible(candidate)) continue
            attempted += candidate.fallbackKey()
            attempts++
            current = candidate
            return candidate
        }
        return null
    }

    /** [stream] plays now; its engine URL is the one this session may replace. */
    fun resolved(stream: StreamItem) {
        attempted += stream.fallbackKey()
        playbackUrl = stream.playableDirectUrl
    }

    /** Whether [url] is the playback this session opened; a normal failure never reuses an older queue. */
    fun ownsPlayback(url: String?): Boolean = selectedIsUsenet && !url.isNullOrBlank() && url == playbackUrl

    fun canFallbackFrom(url: String?): Boolean = ownsPlayback(url) && canAdvance

    /** The playing release failed after it opened: the next attempt says so, not an older reason. */
    fun playbackFailed() {
        lastFailureReason = messages.playbackFailed
    }

    private fun recordFailure(stream: StreamItem, error: Throwable) {
        lastFailure = error
        lastFailureReason = messages.reason(error)
        when ((error as? UsenetPlaybackException)?.scope) {
            UsenetFailureScope.ENGINE -> engineFailed = true
            UsenetFailureScope.PROVIDER -> failedProviders += stream.nzbServers.sorted()
            else -> Unit
        }
    }

    private class Prepared<T>(val value: T)

    /**
     * Preparation time adds up across attempts and leaves out time spent watching. The selected
     * release gets 60 s when fallback is on and each replacement 30 s, within two minutes in all.
     * With fallback off, the single release keeps the whole two minutes.
     */
    suspend fun <T> prepareCandidate(stream: StreamItem, resolve: suspend () -> T): T {
        val allowance = minOf(
            BUDGET_MS - preparationSpentMs,
            if (!enabled) BUDGET_MS else if (attempts == 0) FIRST_ALLOWANCE_MS else NEXT_ALLOWANCE_MS,
        )
        if (allowance <= 0L) throw UsenetPlaybackException(messages.budgetExhausted)
        val start = nowMs()
        fun elapsed() = nowMs() - start
        val label = "attempt=$attempts source=\"${stream.name}\" release=\"${stream.title?.lineSequence()?.firstOrNull()}\""
        log.i { "start $label allowanceMs=$allowance spentMs=$preparationSpentMs" }
        try {
            val prepared = withTimeoutOrNull(allowance) { Prepared(resolve()) }
                ?: throw UsenetPlaybackException(messages.timedOut)
            log.i { "ready $label ms=${elapsed()}" }
            return prepared.value
        } catch (cancelled: CancellationException) {
            log.i { "cancelled $label ms=${elapsed()}" }
            throw cancelled
        } catch (error: Exception) {
            log.i {
                "failed $label ms=${elapsed()} kind=${(error as? UsenetPlaybackException)?.failure ?: "-"} " +
                    "error=${error::class.simpleName}: ${redactUrls(error.message.orEmpty())}"
            }
            recordFailure(stream, error)
            throw error
        } finally {
            preparationSpentMs += elapsed().coerceAtLeast(0L)
        }
    }

    /** Prepares the following releases until one opens. Cancellation is the user's intent, never a reason to go on. */
    suspend fun resolveNext(resolve: suspend (StreamItem, Int) -> StreamItem): StreamItem? {
        while (true) {
            coroutineContext.ensureActive()
            val candidate = next() ?: return null
            try {
                val result = prepareCandidate(candidate) { resolve(candidate, attempts) }
                coroutineContext.ensureActive()
                if (!enabled) return null
                resolved(result)
                return result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Recorded by prepareCandidate; the next distinct release is tried within the budget.
            }
        }
    }

    private companion object {
        const val BUDGET_MS = 120_000L
        const val FIRST_ALLOWANCE_MS = 60_000L
        const val NEXT_ALLOWANCE_MS = 30_000L
        val log = Logger.withTag("UsenetPrepare")
    }
}

/** Automatic fallback tried every release it could and none opened; the message says why. */
internal class UsenetFallbackExhaustedException(message: String) : Exception(message)

/** What a fallback session tells the user. The defaults are the English wording, for tests. */
internal data class UsenetFallbackMessages(
    val budgetExhausted: String = "Usenet preparation time budget exhausted",
    val timedOut: String = "Usenet source preparation timed out",
    val playbackFailed: String = "Playback failed",
    val tookTooLong: String = "Took too long to open",
    /** A few words per engine failure kind (X-Usenet-Failure). */
    val reasons: Map<String, String> = mapOf(
        "missing-article" to "Articles missing on your providers",
        "hole-limit" to "Too many missing articles",
        "invalid-article" to "Damaged articles",
        "provider-authentication" to "Provider login failed",
        "provider-quota" to "Provider quota reached",
        "provider" to "Provider unavailable",
        "blank-content" to "Empty release",
    ),
) {
    /** The engine's kind when it sent a known one, else the first line of the message without URLs. */
    fun reason(error: Throwable): String? {
        reasons[(error as? UsenetPlaybackException)?.failure]?.let { return it }
        val message = error.message?.lineSequence()?.firstOrNull()?.trim()
        if (message == timedOut) return tookTooLong
        return message?.takeIf { it.isNotEmpty() }?.let { redactUrls(it).take(80) }
    }
}

/**
 * Hands a selection's fallback session from the stream screen to the player that plays it. Keyed
 * by the engine URL, which names one session, so it cannot reach another title or profile.
 * Used on the main thread only, like the screens that put and take.
 */
internal object UsenetFallbackHandoff {
    private val sessions = LinkedHashMap<String, UsenetFallbackSession>()

    fun put(url: String?, session: UsenetFallbackSession) {
        if (url.isNullOrBlank()) return
        sessions[url] = session
        while (sessions.size > 4) sessions.remove(sessions.keys.first())
    }

    fun take(url: String?): UsenetFallbackSession? = url?.let(sessions::remove)
}

/** [message] with each URL cut to its scheme and host: no path, query, key or login is kept. */
internal fun redactUrls(message: String): String =
    message.replace(Regex("""(https?|nntps?)://(?:[^@\s/"]*@)?([^/\s"?]+)[^\s"]*"""), "$1://$2/…").take(240)

private val monotonicOrigin = TimeSource.Monotonic.markNow()

private fun monotonicMs(): Long = monotonicOrigin.elapsedNow().inWholeMilliseconds

private fun StreamItem.canAutoFallback(): Boolean = isUsenet && externalUrl.isNullOrBlank() && ytId.isNullOrBlank()

/** Presentation (addon, badges, title) must not turn a copy of a release into a new attempt. */
private data class FallbackKey(
    val source: String,
    val fileIndex: Int? = null,
    val selector: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val servers: List<String> = emptyList(),
)

private fun StreamItem.fallbackKey(): FallbackKey {
    val headers = behaviorHints.proxyHeaders?.request.orEmpty()
    return when {
        !nzbUrl.isNullOrBlank() -> FallbackKey("nzb:$nzbUrl", fileIdx, fileMustInclude, headers, nzbServers.sorted())
        !url.isNullOrBlank() -> FallbackKey(url, headers = headers)
        else -> FallbackKey("torrent:${infoHash?.lowercase().orEmpty()}", fileIdx, behaviorHints.filename)
    }
}
