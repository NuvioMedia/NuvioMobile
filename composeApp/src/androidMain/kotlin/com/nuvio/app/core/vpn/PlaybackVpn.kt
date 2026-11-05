package com.nuvio.app.core.vpn

import android.content.Context
import com.nuvio.vpn.android.VpnPlaybackProtection
import kotlinx.coroutines.suspendCancellableCoroutine

internal suspend fun awaitVpnForPlayback(context: Context, source: String) {
    suspendCancellableCoroutine<Unit> { continuation ->
        val request = VpnPlaybackProtection.prepare(context, source)
        continuation.invokeOnCancellation { request.cancel(false) }
        request.whenComplete { _, failure ->
            if (continuation.isActive) {
                continuation.resumeWith(
                    if (failure == null) Result.success(Unit)
                    else Result.failure(failure.cause ?: failure)
                )
            }
        }
    }
}

internal suspend fun awaitNativeVpnProxy(context: Context): VpnPlaybackProtection.NativeProxy? =
    suspendCancellableCoroutine { continuation ->
        val request = VpnPlaybackProtection.proxyForNativePlayer(context)
        continuation.invokeOnCancellation {
            if (!request.cancel(false)) request.thenAccept { it?.close() }
        }
        request.whenComplete { value, failure ->
            if (!continuation.isActive) value?.close()
            else continuation.resumeWith(
                if (failure == null) Result.success(value) else Result.failure(failure.cause ?: failure)
            )
        }
    }
