package com.nuvio.app.features.simkl

/**
 * How much of a rewatch has to be on the account before it starts offering the next episode.
 *
 * The sessions themselves are recorded according to [SimklRewatchMode]; this only decides what the
 * app reads back out of them. [ALWAYS] is the default, because a rewatch the user started is an
 * offer to continue. [AFTER_TWO] waits for two consecutive episodes first, so a single random
 * rewatch does not propose the one after it, and [NEVER] keeps rewatches out of the app entirely.
 */
enum class SimklRewatchNextUpMode {
    ALWAYS,
    AFTER_TWO,
    NEVER,
    ;

    /**
     * Consecutive rewatched episodes a run needs before it is shown, or null when rewatches should
     * not be read back at all.
     */
    val minimumRunEpisodes: Int?
        get() = when (this) {
            ALWAYS -> 1
            AFTER_TWO -> 2
            NEVER -> null
        }

    companion object {
        val Default: SimklRewatchNextUpMode = ALWAYS

        fun fromStorage(value: String?): SimklRewatchNextUpMode {
            val normalized = value?.trim().orEmpty()
            return entries.firstOrNull { mode -> mode.name.equals(normalized, ignoreCase = true) } ?: Default
        }
    }
}
