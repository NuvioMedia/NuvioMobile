package com.nuvio.app.features.tvremote

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal object AndroidTvRemote {
    fun initialize(context: Context) {}
    fun foreground(visible: Boolean) {}
    fun handleIntent(intent: Intent?) {}
    fun localPlayback(owner: Any, active: Boolean) {}
}
@Composable internal actual fun TvRemoteCard(modifier: Modifier) {}
@Composable internal actual fun TvRemoteOverlay() {}
@Composable internal actual fun TvConnectionSettingsRow(isTablet: Boolean) {}
