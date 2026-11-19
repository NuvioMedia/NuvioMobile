package com.nuvio.app.features.usenet

import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.flow.StateFlow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.usenet_error_damaged_parts
import nuvio.composeapp.generated.resources.usenet_error_empty_release
import nuvio.composeapp.generated.resources.usenet_error_missing_parts
import nuvio.composeapp.generated.resources.usenet_error_provider_login
import nuvio.composeapp.generated.resources.usenet_error_provider_quota
import nuvio.composeapp.generated.resources.usenet_failed
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

    fun onPlaybackFailure(url: String?, failure: String?) = UsenetPlatform.onPlaybackFailure(url, failure)

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
