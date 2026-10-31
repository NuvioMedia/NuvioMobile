package com.nuvio.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.Fold

class Hinge {
    internal var fold by mutableStateOf<Fold?>(null)
        private set

    fun update(top: Double, bottom: Double) {
        fold = Fold(top = top.dp, bottom = bottom.dp)
    }

    fun clear() {
        fold = null
    }
}
