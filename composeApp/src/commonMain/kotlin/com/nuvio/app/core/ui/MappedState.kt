package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
fun <T, R> StateFlow<T>.collectMappedAsState(transform: (T) -> R): State<R> {
    val flow = remember(this) { map(transform).distinctUntilChanged() }
    return flow.collectAsStateWithLifecycle(initialValue = transform(value))
}
