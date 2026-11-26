package com.nuvio.app.features.audiosync.asr

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** A slow-to-create resource whose idle release and readiness checks never wait for creation. */
internal class SharedResource<T : Any>(
    private val scheduler: ScheduledExecutorService,
    private val keepAliveMs: Long,
    private val dispose: (T) -> Unit,
) {
    private val stateLock = Any()
    private val loadLock = Any()
    @Volatile private var instance: T? = null
    private var users = 0
    private var generation = 0L
    private var pendingClose: ScheduledFuture<*>? = null

    val isLoaded: Boolean get() = instance != null

    /** Serializes construction without holding the short-lived reference-count lock. */
    fun acquire(create: () -> T): T = synchronized(loadLock) acquisition@ {
        synchronized(stateLock) state@ {
            instance?.let { resource ->
                retain()
                return@state resource
            }
        }?.let { return@acquisition it }

        // Native model initialization can take a long time on a small TV. release() and
        // isLoaded must remain available while it runs; only other acquirers wait for it.
        val resource = create()
        synchronized(stateLock) {
            instance = resource
            retain()
        }
        resource
    }

    private fun retain() {
        users++
        generation++
        pendingClose?.cancel(false)
        pendingClose = null
    }

    fun release() = synchronized(stateLock) {
        check(users > 0) { "Shared resource released without an acquisition" }
        users--
        if (users != 0) return@synchronized
        val idleGeneration = ++generation
        pendingClose?.cancel(false)
        pendingClose = scheduler.schedule({ closeIfUnused(idleGeneration) }, keepAliveMs, TimeUnit.MILLISECONDS)
    }

    private fun closeIfUnused(idleGeneration: Long) = synchronized(loadLock) {
        val resource = synchronized(stateLock) state@ {
            // A cancelled task may already have started. It must not close a resource that
            // was acquired and released again since that task was scheduled.
            if (users != 0 || generation != idleGeneration) return@state null
            instance.also {
                instance = null
                pendingClose = null
            }
        }
        resource?.let(dispose)
    }
}
