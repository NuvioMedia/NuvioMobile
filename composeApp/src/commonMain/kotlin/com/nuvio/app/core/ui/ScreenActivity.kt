package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.CoroutineScope

internal val LocalScreenActive = compositionLocalOf { true }

@Composable
internal fun ScreenActivityEffect(
    vararg keys: Any?,
    block: suspend CoroutineScope.(Boolean) -> Unit,
) {
    val active = LocalScreenActive.current
    LaunchedEffect(active, *keys) { block(active) }
}
