package com.nuvio.app.features.tvremote

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable internal expect fun TvRemoteCard(modifier: Modifier = Modifier)
@Composable internal expect fun TvRemoteOverlay()
@Composable internal expect fun TvConnectionSettingsRow(isTablet: Boolean)
