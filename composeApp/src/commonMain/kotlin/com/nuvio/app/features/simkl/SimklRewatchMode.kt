package com.nuvio.app.features.simkl

import com.nuvio.app.features.tracking.TRACKING_COMPLETION_BAR_PERCENT
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingSettingsRepository

/**
 * How Nuvio records rewatches on Simkl.
 *
 * Simkl requires an explicit opt-in for rewatch bookkeeping, so the default is [OFF].
 * [AUTOMATIC] sends `allow_rewatch=yes` on every finished playback, [SEMI_AUTOMATIC] asks once
 * before a playback that would open a run and records the rest of the run without asking.
 *
 * The mode only says how a run is started. A run the account holds is shown by Continue Watching
 * while either of the two recording modes is on, and never while it is [OFF].
 */
enum class SimklRewatchMode {
    OFF,
    SEMI_AUTOMATIC,
    AUTOMATIC,
    ;

    val isEnabled: Boolean
        get() = this != OFF

    companion object {
        val Default: SimklRewatchMode = OFF

        fun fromStorage(value: String?): SimklRewatchMode {
            val normalized = value?.trim().orEmpty()
            // "MANUAL" is the mode the first version of the setting shipped as; it asked for the
            // same consent, only after the playback instead of before it, so the stored answer
            // keeps its meaning and no migration is needed.
            if (normalized.equals("MANUAL", ignoreCase = true)) return SEMI_AUTOMATIC
            return entries.firstOrNull { mode -> mode.name.equals(normalized, ignoreCase = true) } ?: Default
        }
    }
}

/** Simkl marks a title watched at this progress, and rewatches can only exist where a watch does. */
internal const val SIMKL_REWATCH_MIN_PROGRESS_PERCENT = 80.0

/**
 * The window the user can pick the completion threshold from.
 *
 * Simkl itself marks a playback watched at 80%, so the slider cannot start lower; above that the
 * user decides when Nuvio reports a finished playback, and therefore when Simkl is told about it.
 * The top of the window is the whole scale: the threshold rides on the call as `min_progress`, so
 * 100 means only a playback that reached the very end is reported as finished.
 */
internal const val SIMKL_WATCHED_THRESHOLD_MIN_PERCENT = 80
internal const val SIMKL_WATCHED_THRESHOLD_MAX_PERCENT = 100
internal const val SIMKL_WATCHED_THRESHOLD_DEFAULT_PERCENT = 80

internal val SimklWatchedThresholdRange: IntRange =
    SIMKL_WATCHED_THRESHOLD_MIN_PERCENT..SIMKL_WATCHED_THRESHOLD_MAX_PERCENT

/**
 * Where a playback counts as finished, as the user set it.
 *
 * Read in one place so the reporting side (what Nuvio tells Simkl) and the reading side (what the app
 * shows for the account) cannot drift apart: a playback paused below the value stays in progress
 * everywhere instead of being reported as a pause and shown as a finished watch.
 */
internal val simklWatchedThresholdPercent: Double
    get() = TrackingSettingsRepository.uiState.value.simklWatchedThresholdPercent.toDouble()

internal fun coerceSimklWatchedThresholdPercent(percent: Int): Int = percent.coerceIn(
    minimumValue = SIMKL_WATCHED_THRESHOLD_MIN_PERCENT,
    maximumValue = SIMKL_WATCHED_THRESHOLD_MAX_PERCENT,
)

/**
 * Where a playback counts as finished, in percent, for everything Simkl is told about it.
 *
 * IntroDB carries the credits marker, and that is where the content really ends, so the marker is the
 * completion point whenever there is one: a playback that stopped before it has not finished, whatever
 * percentage the user set as their own floor. The percentage is the fallback, and it is what decides
 * for the items IntroDB knows nothing about.
 *
 * The marker arrives already read with its tolerance, five seconds early, from the player, so a
 * timestamp that is a few seconds off does not leave a finished playback reported as unfinished. Under
 * what Simkl needs to record a watch it cannot be used at all, since a stop reported there is not
 * counted by the account, so the threshold decides instead. That is also why the threshold itself is
 * never read below that bar.
 */
internal fun resolvedSimklCompletionPercent(
    userThresholdPercent: Double,
    contentEndPercent: Double?,
): Double {
    val userThreshold = userThresholdPercent
        .takeIf { value -> value.isFinite() }
        ?.coerceAtLeast(SIMKL_REWATCH_MIN_PROGRESS_PERCENT)
        ?: SIMKL_REWATCH_MIN_PROGRESS_PERCENT
    val marker = contentEndPercent?.takeIf { value -> value.isFinite() } ?: return userThreshold
    if (marker < SIMKL_REWATCH_MIN_PROGRESS_PERCENT) return userThreshold
    return marker
}

/** Simkl merges two watches of the same item this close together, so asking would be pointless. */
internal const val SIMKL_REWATCH_MIN_GAP_MS = 48L * 60L * 60L * 1_000L

/**
 * How long to wait before each read of the rewatch sessions, in order. Simkl publishes a session some
 * time after the write that created it, so the reads are spaced out. The prompt is answered by the
 * write itself, which is why these can be long: they only keep Continue Watching up to date.
 */
internal val SIMKL_REWATCH_SESSION_READ_DELAYS_MS = longArrayOf(3_000L, 10_000L, 25_000L)


/** Query Simkl expects on the calls that are allowed to record a rewatch session. */
internal val SIMKL_ALLOW_REWATCH_QUERY: Map<String, String> = mapOf("allow_rewatch" to "yes")

/**
 * The completion threshold a scrobble call carries, as Simkl's `min_progress`.
 *
 * Simkl reads a whole number from 50 to 100 out of the query string, drops the decimals and clamps
 * what falls outside. It is floored and clamped here, once, so the account and the client judge a
 * stop by the same number: at or above it the stop marks the item watched, below it the account
 * keeps the resumable pause the client asked for.
 */
internal fun simklMinProgressPercent(completionThresholdPercent: Double): Int =
    completionThresholdPercent
        .takeIf { value -> value.isFinite() }
        ?.toInt()
        ?.coerceIn(SIMKL_MIN_PROGRESS_MIN_PERCENT, SIMKL_MIN_PROGRESS_MAX_PERCENT)
        ?: SIMKL_REWATCH_MIN_PROGRESS_PERCENT.toInt()

/** The bounds Simkl accepts for `min_progress`. */
internal const val SIMKL_MIN_PROGRESS_MIN_PERCENT = 50
internal const val SIMKL_MIN_PROGRESS_MAX_PERCENT = 100

/** The query key Simkl reads the completion threshold from. It only works in the URL. */
internal const val SIMKL_MIN_PROGRESS_QUERY_KEY = "min_progress"

/**
 * The query a scrobble call carries: the completion threshold the call is judged by, and the rewatch
 * flag where the call is one that may record a session.
 */
internal fun simklScrobbleQuery(
    completionThresholdPercent: Double,
    recordRewatch: Boolean,
): Map<String, String> = buildMap {
    put(SIMKL_MIN_PROGRESS_QUERY_KEY, simklMinProgressPercent(completionThresholdPercent).toString())
    if (recordRewatch) putAll(SIMKL_ALLOW_REWATCH_QUERY)
}

/**
 * Whether the scrobble call itself should carry `allow_rewatch=yes`.
 *
 * [AUTOMATIC] asks for a rewatch on every finished playback. [SEMI_AUTOMATIC] does it only where
 * the user said so: a run of the item is open on the account, or the question before the playback
 * was answered with yes. A semi-automatic write is therefore never the client guessing at a run.
 */
internal fun shouldRecordSimklRewatchOnStop(
    mode: SimklRewatchMode,
    accountType: String?,
    action: TrackingScrobbleAction,
    progressPercent: Double,
    completionThresholdPercent: Double = SIMKL_REWATCH_MIN_PROGRESS_PERCENT,
    hasRunningSession: Boolean = false,
    consented: Boolean = false,
    hasPlaybackSession: Boolean = true,
): Boolean = when {
    mode == SimklRewatchMode.OFF -> false
    !isSimklRewatchPlanEligible(accountType) -> false
    action != TrackingScrobbleAction.STOP -> false
    !hasPlaybackSession -> false
    progressPercent < completionThresholdPercent -> false
    mode == SimklRewatchMode.AUTOMATIC -> true
    // Semi-automatic writes only where the user said so: a run of the item is open on the account,
    // or the question before the playback was answered with yes. It is never the client guessing.
    else -> hasRunningSession || consented
}

/**
 * Whether a manual mark of an item is written as a repeat viewing.
 *
 * A mark of something the account has already watched is a repeat, and Simkl records a plain add of it
 * as nothing at all: the write has to travel with the rewatch flag to land. A run the account already
 * holds makes the mark part of that run whatever the item's own state says, and a mark of something the
 * account has never watched stays a plain first watch, because there is nothing to repeat.
 *
 * The mode and the plan are the gates the scrobble path uses. A semi-automatic account does not ask
 * here: the user marked the episode watched themselves, so the question the mode would ask before a
 * playback has been answered by the mark. A free account is never sent the flag, because Simkl drops a
 * write carrying it silently and the watch would be lost with it.
 */
internal fun shouldRecordSimklRewatchOnMark(
    mode: SimklRewatchMode,
    accountType: String?,
    isRepeatViewing: Boolean,
    hasRunningSession: Boolean = false,
): Boolean = when {
    mode == SimklRewatchMode.OFF -> false
    !isSimklRewatchPlanEligible(accountType) -> false
    hasRunningSession -> true
    else -> isRepeatViewing
}

/**
 * Whether the user should be asked to record the playback Simkl just accepted as a repeat viewing.
 *
 * The question is only worth asking when Simkl would create a session for it: the plan has to allow
 * rewatches and the item has to be in the user's history already. A watch from the last 48 hours is
 * skipped, because Simkl folds those into the existing session and a confirmation would do nothing.
 */
internal fun shouldPromptSimklRewatch(
    mode: SimklRewatchMode,
    accountType: String?,
    action: TrackingScrobbleAction,
    outcome: SimklScrobbleOutcome,
    progressPercent: Double,
    priorWatch: SimklPriorWatch,
    nowEpochMs: Long,
    completionThresholdPercent: Double = SIMKL_REWATCH_MIN_PROGRESS_PERCENT,
): Boolean {
    if (mode != SimklRewatchMode.SEMI_AUTOMATIC) return false
    if (!isSimklRewatchPlanEligible(accountType)) return false
    if (action != TrackingScrobbleAction.STOP) return false
    if (outcome != SimklScrobbleOutcome.SCROBBLE) return false
    if (progressPercent < completionThresholdPercent) return false
    if (!priorWatch.wasWatched) return false
    val watchedAt = priorWatch.watchedAtEpochMs ?: return true
    return nowEpochMs - watchedAt >= SIMKL_REWATCH_MIN_GAP_MS
}

/**
 * Whether a playback that is about to start should ask the user about opening a run.
 *
 * Only the semi-automatic mode asks, and only where the playback is a rewatch of something the
 * account already finished: a start that is not a rewatch, or one that replaces the session the
 * account already runs, has nothing to ask about.
 */
internal fun shouldAskToStartSimklRewatch(
    mode: SimklRewatchMode,
    accountType: String?,
    action: TrackingScrobbleAction,
    priorWatch: SimklPriorWatch,
    nowEpochMs: Long,
    hasRunningSession: Boolean,
    progressPercent: Double = 0.0,
): Boolean {
    if (mode != SimklRewatchMode.SEMI_AUTOMATIC) return false
    // A start the player sends above the completion bar says that a playback that was already under
    // way is running again, not that a viewing is beginning: the question belongs before a playback,
    // and this one was asked when it began. The trackers still hear about the playback, which is why
    // the start itself travels.
    if (progressPercent >= TRACKING_COMPLETION_BAR_PERCENT) return false
    if (!isSimklRewatchPlanEligible(accountType)) return false
    if (action != TrackingScrobbleAction.START) return false
    if (hasRunningSession) return false
    if (!priorWatch.wasWatched) return false
    val watchedAt = priorWatch.watchedAtEpochMs ?: return true
    return nowEpochMs - watchedAt >= SIMKL_REWATCH_MIN_GAP_MS
}

/** What the local Simkl snapshot knew about the scrobbled item before this playback. */
internal data class SimklPriorWatch(
    val wasWatched: Boolean,
    val watchedAtEpochMs: Long?,
) {
    companion object {
        val None = SimklPriorWatch(wasWatched = false, watchedAtEpochMs = null)
    }
}

/**
 * Simkl Pro / VIP only. Unknown plans stay ineligible: sending the flag for a free account would
 * consume a rate-limit slot and be dropped server-side.
 */
internal fun isSimklRewatchPlanEligible(accountType: String?): Boolean {
    val normalized = accountType?.trim()?.lowercase().orEmpty()
    return normalized == "pro" || normalized == "vip"
}

/** The plan Simkl reports for an account without rewatches; the cached plan is corrected to it. */
internal const val SIMKL_FREE_PLAN = "free"

/** Free-tier accounts cannot record rewatches, so the picker keeps them off and offers an upgrade. */
internal fun isSimklRewatchModeSelectable(
    mode: SimklRewatchMode,
    accountType: String?,
): Boolean = mode == SimklRewatchMode.OFF || isSimklRewatchPlanEligible(accountType)
