package com.nuvio.app.features.usenet

import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.usenet_error_damaged_parts
import nuvio.composeapp.generated.resources.usenet_error_empty_release
import nuvio.composeapp.generated.resources.usenet_error_missing_parts
import nuvio.composeapp.generated.resources.usenet_error_provider_login
import nuvio.composeapp.generated.resources.usenet_error_provider_quota
import nuvio.composeapp.generated.resources.usenet_failed
import nuvio.composeapp.generated.resources.usenet_fallback_exhausted
import nuvio.composeapp.generated.resources.usenet_preparation_budget_exhausted
import nuvio.composeapp.generated.resources.usenet_preparation_timed_out
import nuvio.composeapp.generated.resources.usenet_reason_blank_content
import nuvio.composeapp.generated.resources.usenet_reason_hole_limit
import nuvio.composeapp.generated.resources.usenet_reason_invalid_article
import nuvio.composeapp.generated.resources.usenet_reason_missing_article
import nuvio.composeapp.generated.resources.usenet_reason_playback_failed
import nuvio.composeapp.generated.resources.usenet_reason_provider
import nuvio.composeapp.generated.resources.usenet_reason_provider_authentication
import nuvio.composeapp.generated.resources.usenet_reason_provider_quota
import nuvio.composeapp.generated.resources.usenet_reason_timed_out
import nuvio.composeapp.generated.resources.usenet_trying_next
import nuvio.composeapp.generated.resources.usenet_trying_next_reason
import org.jetbrains.compose.resources.getString

/** What the stream screens and the player ask of Usenet playback. */
object UsenetPlayback {
    /** Increments when a release failed for good or played, so shown lists can be arranged again. */
    val outcomeChanges: StateFlow<Int> get() = UsenetPlatform.outcomeChanges

    /** [groups] with the built-in group arranged again; the same list when nothing changed. */
    fun arrangeOutcomes(groups: List<AddonStreamGroup>): List<AddonStreamGroup> {
        val arranged = groups.map(::arrangeOutcomes)
        return if (arranged.indices.all { arranged[it] === groups[it] }) groups else arranged
    }

    /** Labels and orders the built-in group by how its releases last went; other groups are returned as they are. */
    fun arrangeOutcomes(group: AddonStreamGroup): AddonStreamGroup {
        if (group.addonId != BUILT_IN_USENET_GROUP_ID || group.streams.isEmpty() || !UsenetRepository.isSupported) return group
        val availableFirst = UsenetRepository.sources().availableFirst
        return UsenetPlatform.arrangeOutcomes(listOf(group), availableFirst).firstOrNull() ?: group
    }

    /**
     * Opens [stream] in the engine. The result plays from the engine's local URL, which lives as
     * long as its session: it is never kept for reuse.
     */
    suspend fun prepare(stream: StreamItem, season: Int?, episode: Int?): StreamItem =
        UsenetPlatform.prepare(stream, season, episode, ProfileRepository.activeProfileId)

    /** Ends the session of [url] when the player that played it goes away. */
    fun release(url: String?) {
        if (UsenetPlatform.isEngineUrl(url)) UsenetPlatform.release(url)
    }

    fun isEngineUrl(url: String?): Boolean = UsenetPlatform.isEngineUrl(url)

    /**
     * With prefetching on, opens the first Usenet release of [streams] that is not marked
     * unavailable, so its Play starts from an open session. [owner] is the screen showing them.
     */
    fun prefetchFirst(owner: Any, streams: List<StreamItem>, season: Int?, episode: Int?) {
        if (!UsenetRepository.isSupported || !UsenetRepository.engineSettings().prefetchResults) return
        val first = streams.firstOrNull { it.needsUsenetPreparation && !isMarkedUnavailable(it) } ?: return
        UsenetPlatform.prefetch(owner, first, season, episode, ProfileRepository.activeProfileId)
    }

    /** The screen [owner] closed or chose something else: its prefetched session is not needed. */
    fun cancelPrefetch(owner: Any) {
        if (UsenetRepository.isSupported) UsenetPlatform.cancelPrefetch(owner)
    }

    private var firstFrameUrl: String? = null

    /** Playback of [url] started; each engine URL is reported once. */
    fun onFirstFrame(url: String?) {
        if (url == null || url == firstFrameUrl || !UsenetPlatform.isEngineUrl(url)) return
        firstFrameUrl = url
        UsenetPlatform.onFirstFrame(url)
    }

    private var failedForGoodUrl: String? = null

    fun onPlaybackFailure(url: String?, failure: String?) {
        if (failure != null) failedForGoodUrl = url
        UsenetPlatform.onPlaybackFailure(url, failure)
    }

    /** The engine gave up on [url] and said why, so playing it again cannot help. */
    fun failedForGood(url: String?): Boolean = url != null && url == failedForGoodUrl

    /**
     * Automatic fallback for [selected], over the Usenet releases after it in [shown], the order
     * the user sees. It does nothing unless Automatic Usenet fallback is on.
     */
    internal suspend fun fallbackSession(selected: StreamItem, shown: List<StreamItem>): UsenetFallbackSession {
        val settings = UsenetRepository.engineSettings()
        return UsenetFallbackSession(
            selected = selected,
            orderedStreams = shown,
            maxAttempts = settings.fallbackMaxAttempts,
            isEnabled = { UsenetRepository.isSupported && UsenetRepository.engineSettings().fallbackEnabled },
            isUnplayable = ::isMarkedUnavailable,
            messages = fallbackMessages(),
        )
    }

    /**
     * Opens [stream], and when it fails, the following releases of [session] until one opens.
     * [onNextAttempt] receives the message to show before each following release. The opened
     * stream's session is handed to the player, so a release that fails there falls back too.
     * Throws when nothing opened, with the message to show.
     */
    internal suspend fun prepareWithFallback(
        session: UsenetFallbackSession,
        stream: StreamItem,
        season: Int?,
        episode: Int?,
        onNextAttempt: suspend (String) -> Unit,
    ): StreamItem {
        val prepared = try {
            session.prepareCandidate(stream) { prepare(stream, season, episode) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!session.canAdvance) throw error
            session.resolveNext { candidate, attempt ->
                onNextAttempt(nextReleaseMessage(attempt, session.lastFailureReason))
                prepare(candidate, season, episode)
            } ?: throw UsenetFallbackExhaustedException(exhaustedMessage(session))
        }
        session.resolved(prepared)
        UsenetFallbackHandoff.put(prepared.playableDirectUrl, session)
        return prepared
    }

    /** The fallback session the stream screen handed over with the engine URL [url], if any. */
    internal fun takeFallback(url: String?): UsenetFallbackSession? = UsenetFallbackHandoff.take(url)

    /** "Articles missing. Trying next release (attempt 2)...", without the reason when none is known. */
    suspend fun nextReleaseMessage(attempt: Int, reason: String?): String =
        if (reason.isNullOrBlank()) getString(Res.string.usenet_trying_next, attempt.toString())
        else getString(Res.string.usenet_trying_next_reason, reason.trimEnd('.', ' '), attempt.toString())

    /** No release of [session] opened: say so, with why the last one failed, else [otherwise]. */
    internal suspend fun exhaustedMessage(session: UsenetFallbackSession, otherwise: String? = null): String {
        val last = session.lastFailure?.let { failureMessage(it) }
            ?: getString(Res.string.usenet_preparation_budget_exhausted).takeIf { session.budgetExhausted }
            ?: otherwise
        return listOfNotNull(getString(Res.string.usenet_fallback_exhausted), last).joinToString("\n")
    }

    private suspend fun fallbackMessages() = UsenetFallbackMessages(
        budgetExhausted = getString(Res.string.usenet_preparation_budget_exhausted),
        timedOut = getString(Res.string.usenet_preparation_timed_out),
        playbackFailed = getString(Res.string.usenet_reason_playback_failed),
        tookTooLong = getString(Res.string.usenet_reason_timed_out),
        reasons = mapOf(
            "missing-article" to getString(Res.string.usenet_reason_missing_article),
            "hole-limit" to getString(Res.string.usenet_reason_hole_limit),
            "invalid-article" to getString(Res.string.usenet_reason_invalid_article),
            "provider-authentication" to getString(Res.string.usenet_reason_provider_authentication),
            "provider-quota" to getString(Res.string.usenet_reason_provider_quota),
            "provider" to getString(Res.string.usenet_reason_provider),
            "blank-content" to getString(Res.string.usenet_reason_blank_content),
        ),
    )

    /** A release whose articles were found missing or damaged with the current providers. */
    fun isMarkedUnavailable(stream: StreamItem): Boolean =
        stream.isUsenet && stream.badges.any { it.name == USENET_BADGE_UNAVAILABLE && it.isLabelBadge() }

    /** The failure kind the engine sent with a stream response (X-Usenet-Failure), if any. */
    fun failureKind(responseHeaders: Map<*, List<String>?>): String? =
        responseHeaders.entries.firstOrNull { (it.key as? String)?.equals("X-Usenet-Failure", ignoreCase = true) == true }
            ?.value?.firstOrNull { it.isNotBlank() }?.trim()

    /** Plain advice for an engine failure kind; null for other failures. */
    suspend fun failureExplanationText(kind: String?): String? = failureExplanation(kind)?.let { getString(it) }

    /** What to tell the user about a failed open; engine verdicts read as plain advice. */
    suspend fun failureMessage(error: Throwable): String {
        if (error is UsenetFallbackExhaustedException) return error.message.orEmpty()
        val failure = (error as? UsenetPlaybackException)?.failure
        failureExplanation(failure)?.let { return getString(it) }
        return getString(Res.string.usenet_failed, error.message.orEmpty().ifBlank { error::class.simpleName.orEmpty() })
    }

    private fun failureExplanation(kind: String?) = when (kind) {
        "missing-article", "hole-limit" -> Res.string.usenet_error_missing_parts
        "invalid-article" -> Res.string.usenet_error_damaged_parts
        "provider-authentication" -> Res.string.usenet_error_provider_login
        "provider-quota" -> Res.string.usenet_error_provider_quota
        "blank-content" -> Res.string.usenet_error_empty_release
        else -> null
    }
}
