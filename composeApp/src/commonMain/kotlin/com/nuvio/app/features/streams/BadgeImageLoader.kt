package com.nuvio.app.features.streams

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.CachePolicy
import coil3.request.crossfade
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Separate ImageLoader for badge images without CacheControlCacheStrategy.
 * Badges are static icons that should stay cached indefinitely.
 */
internal object BadgeImageLoader {

    private val lock = SynchronizedObject()

    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: PlatformContext): ImageLoader {
        instance?.let { return it }
        return synchronized(lock) {
            instance ?: ImageLoader.Builder(context)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .crossfade(false)
                .build()
                .also { instance = it }
        }
    }
}
