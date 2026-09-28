package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.nuvio.app.core.ui.NuvioToastController
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.external_player_failed
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

@Composable
actual fun rememberExternalPlayerLauncher(
    onResult: (ExternalPlaybackResult?) -> Unit,
): (ExternalPlayerIntentResult.Success) -> Boolean {
    val currentOnResult by rememberUpdatedState(onResult)
    LaunchedEffect(Unit) {
        infusePlaybackCallbacks.restoreResult()
        infusePlaybackCallbacks.results.collect { session ->
            if (session != null) {
                currentOnResult(session.playbackResult())
                if (session.failed) {
                    NuvioToastController.show(getString(Res.string.external_player_failed))
                    infusePlaybackCallbacks.consume(session.id)
                }
            }
        }
    }
    return remember {
        { intentResult: ExternalPlayerIntentResult.Success ->
            val url = intentResult.intent
            if (url is NSURL) {
                UIApplication.sharedApplication.openURL(
                    url = url,
                    options = emptyMap<Any?, Any>(),
                    completionHandler = { opened ->
                        if (!opened) infusePlaybackCallbacks.cancelLaunch(url.absoluteString.orEmpty())
                    },
                )
                true
            } else {
                false
            }
        }
    }
}
