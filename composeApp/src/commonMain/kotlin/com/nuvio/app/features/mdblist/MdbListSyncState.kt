package com.nuvio.app.features.mdblist

enum class MdbListSyncError { UNAVAILABLE, INVALID_RESPONSE, RATE_LIMIT, AUTHORIZATION_REVOKED }

internal data class MdbListProgressState(
    val scope: MdbListAuthScope? = null,
    val projection: MdbListProgressProjection = MdbListProgressProjection.Empty
)

data class MdbListSyncState(
    val scope: MdbListAuthScope? = null,
    val snapshot: MdbListSyncSnapshot? = null,
    val isLoading: Boolean = false,
    val error: MdbListSyncError? = null,
    val retryAtEpochMs: Long? = null,
    val attemptedAtEpochMs: Long? = null
) {
    val hasLoaded: Boolean
        get() = snapshot?.isInitialized == true
}

internal fun Throwable.toMdbListSyncError(): MdbListSyncError = when {
    this is MdbListAuthException -> MdbListSyncError.AUTHORIZATION_REVOKED
    this is MdbListDecodingException -> MdbListSyncError.INVALID_RESPONSE
    this is MdbListApiException && status == 429 -> MdbListSyncError.RATE_LIMIT
    else -> MdbListSyncError.UNAVAILABLE
}
