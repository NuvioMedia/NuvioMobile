package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.actions.VideoClickActionHolder

/** Android host ABI used by standard CloudStream .cs3 packages. */
abstract class Plugin : BasePlugin() {
    @Throws(Throwable::class)
    open fun load(context: Context) {
        load()
    }

    fun registerVideoClickAction(element: VideoClickAction) {
        element.sourcePlugin = filename
        VideoClickActionHolder.allVideoClickActions += element
    }

    var resources: Resources? = null
    var openSettings: ((Context) -> Unit)? = null
}
