package com.nuvio.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

class VerticalBar {
    internal var padding by mutableStateOf(PaddingValues())
        private set

    fun update(start: Double, end: Double) {
        padding = PaddingValues(start = start.dp, end = end.dp)
    }
}
