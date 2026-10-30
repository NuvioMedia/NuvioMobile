package com.nuvio.app

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

class AppGateController {
    private val profileSelectionChannel = Channel<Unit>(Channel.BUFFERED)
    private val _mainContentReady = MutableStateFlow(false)
    private val _appReady = MutableStateFlow(false)
    private val _contentGeneration = MutableStateFlow(0)

    internal val profileSelectionRequests = profileSelectionChannel.receiveAsFlow()
    internal val mainContentReady = _mainContentReady.asStateFlow()
    internal val appReady = _appReady.asStateFlow()
    internal val contentGeneration = _contentGeneration.asStateFlow()

    fun requestProfileSelection() {
        profileSelectionChannel.trySend(Unit)
    }

    internal fun beginContentReload() {
        _mainContentReady.value = false
        _contentGeneration.update { it + 1 }
    }

    internal fun reportMainContentReady(ready: Boolean) {
        _mainContentReady.value = ready
    }

    internal fun reportAppReady(ready: Boolean) {
        _appReady.value = ready
    }
}
