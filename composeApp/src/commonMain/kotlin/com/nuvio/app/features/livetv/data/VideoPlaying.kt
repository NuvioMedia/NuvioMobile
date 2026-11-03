package com.nuvio.app.features.livetv.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * How many player screens are open right now (Live TV channels, catch-up, movies and episodes
 * all use the same player). Heavy background work, like finding channels for Sports games,
 * waits while one is open so the video stays smooth.
 */
object VideoPlaying {
    private val _open = MutableStateFlow(0)
    val open: StateFlow<Int> = _open.asStateFlow()

    fun opened() = _open.update { it + 1 }
    fun closed() = _open.update { (it - 1).coerceAtLeast(0) }
}
