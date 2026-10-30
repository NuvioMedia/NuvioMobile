package com.nuvio.app.core.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp

data class Fold(val top: Dp, val bottom: Dp)

val LocalFold = compositionLocalOf<Fold?> { null }
