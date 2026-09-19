package com.nuvio.app.features.downloads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object DownloadLocationState {
    private val _locationLabel = MutableStateFlow("")
    val locationLabel: StateFlow<String> = _locationLabel.asStateFlow()

    fun refresh() {
        _locationLabel.value = DownloadLocationManager.currentLocationLabel()
    }
}
