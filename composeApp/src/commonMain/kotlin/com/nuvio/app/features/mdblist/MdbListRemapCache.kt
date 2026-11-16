package com.nuvio.app.features.mdblist

import kotlin.concurrent.Volatile

internal class MdbListRemapCache<T> {
    @Volatile
    private var last: Triple<MdbListProgressProjection, Map<String, MdbListEpisodeMapping>, T>? = null

    fun get(
        projection: MdbListProgressProjection,
        mappings: Map<String, MdbListEpisodeMapping>,
        build: () -> T,
    ): T {
        last?.let { (cachedProjection, cachedMappings, value) ->
            if (cachedProjection === projection && cachedMappings === mappings) return value
        }
        return build().also { last = Triple(projection, mappings, it) }
    }
}
