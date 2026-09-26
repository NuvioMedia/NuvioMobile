package com.nuvio.app.features.servers

import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.servers_error_failed
import nuvio.composeapp.generated.resources.servers_error_forbidden
import nuvio.composeapp.generated.resources.servers_failure_auth
import nuvio.composeapp.generated.resources.servers_failure_incomplete
import nuvio.composeapp.generated.resources.servers_failure_not_found
import nuvio.composeapp.generated.resources.servers_failure_unreachable
import nuvio.composeapp.generated.resources.servers_failure_unsupported
import nuvio.composeapp.generated.resources.servers_playback_failed
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

internal fun Throwable.serverFailure(): ServerFailure = (this as? ServerException)?.failure ?: ServerFailure.FAILED

internal suspend fun Throwable.serverMessage(): String? = (this as? ServerException)?.let { getString(it.failure.message()) }

internal suspend fun Throwable.serverPlaybackMessage(): String = getString(
    serverFailure().takeUnless { it == ServerFailure.FAILED }?.message() ?: Res.string.servers_playback_failed,
)

internal fun ServerFailure.message(): StringResource = when (this) {
    ServerFailure.AUTH_REQUIRED -> Res.string.servers_failure_auth
    ServerFailure.UNREACHABLE -> Res.string.servers_failure_unreachable
    ServerFailure.NOT_FOUND -> Res.string.servers_failure_not_found
    ServerFailure.INCOMPLETE -> Res.string.servers_failure_incomplete
    ServerFailure.FORBIDDEN -> Res.string.servers_error_forbidden
    ServerFailure.UNSUPPORTED -> Res.string.servers_failure_unsupported
    ServerFailure.FAILED -> Res.string.servers_error_failed
}
