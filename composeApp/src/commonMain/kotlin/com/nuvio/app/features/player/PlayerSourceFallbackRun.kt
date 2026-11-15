package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamItem

/** The sources auto source selection tried before the one that plays, and how many further ones. */
internal data class PlayerSourceFallbackTried(val keys: Set<String>, val attempts: Int) {
    companion object {
        /** A source auto selection picked first, with nothing tried before it. */
        val None = PlayerSourceFallbackTried(keys = emptySet(), attempts = 0)
    }
}

/** What resolving one auto-selected source gave. */
internal sealed interface PlayerSourceResolveAttempt<out T, out F> {
    /** The source is ready to play. */
    data class Resolved<T>(val value: T) : PlayerSourceResolveAttempt<T, Nothing>

    /** The source cannot be played, in a way the next source may get past. */
    data class Failed<F>(val failure: F) : PlayerSourceResolveAttempt<Nothing, F>
}

internal data class PlayerSourceFallbackRun<T, F>(
    /** What the source that resolved gave, or null when none did. */
    val resolved: T?,
    /** The failure of the last source tried, to show when none resolved. */
    val failure: F?,
    /** The sources tried, the one that resolved included. */
    val tried: PlayerSourceFallbackTried,
)

/**
 * Resolves [first] and, while a source cannot be played, the one [nextCandidate] picks next, up to
 * [maxSources] further ones. With [maxSources] at 0 only [first] is tried.
 */
internal suspend fun <T : Any, F : Any> resolveWithSourceFallback(
    first: StreamItem,
    maxSources: Int,
    resolve: suspend (StreamItem) -> PlayerSourceResolveAttempt<T, F>,
    nextCandidate: (failed: StreamItem, triedKeys: Set<String>) -> StreamItem?,
): PlayerSourceFallbackRun<T, F> {
    val triedKeys = mutableSetOf<String>()
    var attempts = 0
    var candidate = first
    while (true) {
        val attempt = resolve(candidate)
        candidate.playerSourceIdentityKey()?.let(triedKeys::add)
        val failure = when (attempt) {
            is PlayerSourceResolveAttempt.Resolved -> {
                return PlayerSourceFallbackRun(attempt.value, null, PlayerSourceFallbackTried(triedKeys, attempts))
            }
            is PlayerSourceResolveAttempt.Failed -> attempt.failure
        }
        val next = if (attempts < maxSources) nextCandidate(candidate, triedKeys) else null
        if (next == null) return PlayerSourceFallbackRun(null, failure, PlayerSourceFallbackTried(triedKeys, attempts))
        attempts++
        candidate = next
    }
}

/**
 * Starts the fallback over for a source that replaced the playing one in place, such as the next
 * episode. Only a source auto source selection picked falls back, [autoSelection] then holds the
 * sources tried before it, and the count belongs to the episode the sources were tried for.
 */
internal fun PlayerScreenRuntime.restartSourceFallback(
    sourceUrl: String,
    autoSelection: PlayerSourceFallbackTried?,
) {
    sourceFallbackJob?.cancel()
    autoSelectedSourceUrl = sourceUrl.takeIf { autoSelection != null && it.isNotBlank() }
    sourceFallbackAttempts = autoSelection?.attempts ?: 0
    sourceFallbackTriedKeys.clear()
    autoSelection?.let { tried -> sourceFallbackTriedKeys += tried.keys }
}
