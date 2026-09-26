package com.nuvio.app.features.tracking

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
}
