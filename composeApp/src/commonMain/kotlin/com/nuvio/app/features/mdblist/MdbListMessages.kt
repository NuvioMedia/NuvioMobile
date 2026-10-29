package com.nuvio.app.features.mdblist

import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource

internal fun MdbListAuthError.messageResource(): StringResource = when (this) {
    MdbListAuthError.MISSING_CLIENT_ID -> Res.string.settings_mdblist_missing_credentials
    MdbListAuthError.INVALID_RESPONSE -> Res.string.settings_mdblist_invalid_response
    MdbListAuthError.INSUFFICIENT_SCOPE -> Res.string.settings_mdblist_write_required
    MdbListAuthError.CODE_EXPIRED -> Res.string.settings_mdblist_auth_expired
    MdbListAuthError.ACCESS_DENIED -> Res.string.settings_mdblist_auth_denied
    MdbListAuthError.AUTHORIZATION_REVOKED -> Res.string.settings_mdblist_auth_revoked
}

internal fun MdbListSyncError.messageResource(): StringResource = when (this) {
    MdbListSyncError.RATE_LIMIT -> Res.string.settings_mdblist_rate_limited
    MdbListSyncError.AUTHORIZATION_REVOKED -> Res.string.settings_mdblist_auth_revoked
    MdbListSyncError.INVALID_RESPONSE -> Res.string.settings_mdblist_invalid_response
    MdbListSyncError.UNAVAILABLE -> Res.string.settings_mdblist_unavailable
}

internal fun Throwable.mdbListMessageResource(): StringResource =
    (this as? MdbListAuthException)?.error?.messageResource() ?: toMdbListSyncError().messageResource()

internal fun Throwable.localizedMdbListMessage(): String = mdbListMessageResource().localizedMdbListMessage()

internal fun MdbListSyncError.localizedMdbListMessage(): String = messageResource().localizedMdbListMessage()

private fun StringResource.localizedMdbListMessage(): String =
    runCatching { runBlocking { getString(this@localizedMdbListMessage) } }
        .getOrDefault("Could not sync with MDBList. Please try again.")
