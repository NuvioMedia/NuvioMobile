package com.lagradost.cloudstream3.ui.settings.extensions

/** Binary-compatible repository descriptor used by a few CloudStream plugins. */
data class RepositoryData(
    val iconUrl: String?,
    val name: String,
    val url: String,
) {
    constructor(name: String, url: String) : this(null, name, url)
}
