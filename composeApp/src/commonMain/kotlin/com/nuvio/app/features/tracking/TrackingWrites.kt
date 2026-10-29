package com.nuvio.app.features.tracking

/**
 * Where a playback counts as already past the point the trackers report a completion at.
 *
 * One number for two sides that have to agree on it: the player, which reports a finished playback
 * from here up, and each provider, which decides for itself what a start sent above it means. Trakt
 * treats such a start as the beginning of a viewing, and the stop that follows would record the watch
 * twice, so its scrobbler skips the start; a tracker whose "watching now" state is its own wants to
 * hear about the playback anyway.
 */
internal const val TRACKING_COMPLETION_BAR_PERCENT = 80.0

enum class TrackingListStatus(val wireValue: String) {
    WATCHING("watching"),
    PLAN_TO_WATCH("plantowatch"),
    ON_HOLD("hold"),
    COMPLETED("completed"),
    DROPPED("dropped");

    companion object {
        internal fun fromWireValue(value: String?): TrackingListStatus? =
            entries.firstOrNull { status -> status.wireValue.equals(value, ignoreCase = true) }
    }
}

enum class TrackingScrobbleAction(val wireValue: String) {
    START("start"),
    PAUSE("pause"),
    STOP("stop"),
}

enum class TrackingSeekScrobblePolicy {
    NONE,
    STOP_AND_RESTART,
}

data class TrackingHistoryItem(
    val media: TrackingMediaReference,
    val watchedAtEpochMs: Long? = null,
    /**
     * The run of this item a repeat viewing joins, when the account already holds one.
     *
     * Simkl keeps a rewatch of an item in a session, and two writes of the same episode without a pin
     * split it into two runs. Providers without the concept leave it unset and it is then never sent.
     */
    val rewatchId: Long? = null,
    /**
     * Whether this write records a repeat viewing rather than a first watch.
     *
     * Simkl records a plain add of something the account has already watched as nothing at all, so a
     * mark that is a repeat has to say so for the account to book it: the write then joins the run of
     * the item, or opens one. Providers without the concept leave it unset and nothing is sent.
     */
    val isRewatch: Boolean = false,
)

data class TrackingScrobbleEvent(
    val media: TrackingMediaReference,
    val progressPercent: Double,
    /**
     * Where the content really ends, in percent of its duration, when IntroDB knows the credits.
     *
     * A playback that reached the credits is over, even when the user set the completion point higher
     * than that. Null means no marker is known for this item, and the percentage decides on its own.
     */
    val contentEndPercent: Double? = null,
    /**
     * A scrobble of a playback no session owns, for the trackers that keep a watching state of
     * their own.
     *
     * A playback that sits above the completion bar never arms the pause and stop policy, so the
     * events it produces belong to no session. Simkl wants them anyway; for Trakt a pause is a stop,
     * and a stop above the bar records a watch, so such an event would write a viewing that never
     * happened. The player marks them and the coordinator leaves out the scrobblers that do not
     * accept them (see [TrackingScrobbler.acceptsSessionlessScrobbles]).
     */
    val sessionless: Boolean = false,
    /**
     * Whether the stop ends a playback session the player opened.
     *
     * A playback that only ever reported its start above the completion bar opens no session, so a
     * stop that ends it is not a viewing that can be repeated and must not ask for a rewatch.
     */
    val hasPlaybackSession: Boolean = false,
    /**
     * Identifies one run of the player for the item.
     *
     * A tracker that records a watch on a stop uses it to collapse a completion it already recorded
     * when the same playback ends again after a pause, without suppressing a later, separate viewing.
     * Zero means the caller has no playback identity, and nothing is collapsed.
     */
    val playbackId: Long = 0L,
)

data class TrackingMutationResult(
    val attemptedCount: Int,
    val notFoundCount: Int = 0,
    val resolutions: List<TrackingMutationResolution> = emptyList(),
) {
    val resolvedListStatuses: List<TrackingListStatus>
        get() = resolutions.mapNotNull(TrackingMutationResolution::listStatus)

    val isComplete: Boolean
        get() = notFoundCount == 0
}

data class TrackingMutationResolution(
    val listStatus: TrackingListStatus? = null,
    val mediaKind: TrackingMediaKind? = null,
    val providerSubtype: String? = null,
    /** What the account did with a write that asked for a repeat viewing, when it reports one. */
    val rewatchStatus: String? = null,
)

interface TrackingListWriter {
    val providerId: TrackingProviderId

    suspend fun moveToList(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
        destination: TrackingListStatus,
    ): TrackingMutationResult

    suspend fun removeFromList(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
    ): TrackingMutationResult
}

interface TrackingHistoryWriter {
    val providerId: TrackingProviderId

    suspend fun addToHistory(
        profileId: Int,
        items: Collection<TrackingHistoryItem>,
    ): TrackingMutationResult

    suspend fun removeFromHistory(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
    ): TrackingMutationResult
}

interface TrackingScrobbler {
    val providerId: TrackingProviderId
    val seekScrobblePolicy: TrackingSeekScrobblePolicy
        get() = TrackingSeekScrobblePolicy.NONE

    suspend fun scrobble(
        profileId: Int,
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent,
    )

    /**
     * Whether a scrobble of a playback no session owns is welcome here.
     *
     * Off by default. For Trakt a pause is a stop and a stop above the completion bar records a
     * watch, so a sessionless pause or stop would write a viewing the user never finished. A tracker
     * that keeps its own "watching now" state, such as Simkl, turns it on.
     */
    val acceptsSessionlessScrobbles: Boolean
        get() = false
}
