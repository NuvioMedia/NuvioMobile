package com.nuvio.app.features.livetv.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Keeps a screen's content out from under the camera cutout and the navigation bar when the phone is
 * held sideways (they sit at the left or right edge then). Nothing changes upright.
 */
@Composable
internal fun Modifier.liveTvSideInsets(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
