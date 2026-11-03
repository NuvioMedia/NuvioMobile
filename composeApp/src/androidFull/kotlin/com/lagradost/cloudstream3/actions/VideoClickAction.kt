package com.lagradost.cloudstream3.actions

import android.content.Context
import com.lagradost.cloudstream3.utils.ExtractorLinkType

/** Compatibility ABI for the uncommon plugins that register custom click actions. */
abstract class VideoClickAction {
    open val name: Any? = null
    open val oneSource: Boolean = false
    open val isPlayer: Boolean = false
    open val sourceTypes: Set<ExtractorLinkType> = emptySet()
    var sourcePlugin: String? = null

    open fun shouldShow(context: Context, episode: Any): Boolean = false
}

object VideoClickActionHolder {
    val allVideoClickActions: MutableList<VideoClickAction> = mutableListOf()
}
