package com.nuvio.app.features.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.File
import java.util.concurrent.atomic.AtomicLong

@UnstableApi
internal object VodCache {
    private const val TAG = "VodCache"
    private const val DIR_NAME = "nuvio_vod_cache"
    private const val STALE_PREFIX = "nuvio_vod_cache_stale_"
    private const val FRAGMENT_BYTES = 8L * 1024L * 1024L
    private const val RETAIN_BEHIND_BYTES = 1024L * 1024L * 1024L
    private const val TRIM_STEP_BYTES = 64L * 1024L * 1024L
    private const val EVICTION_DELAY_MS = 5_000L

    @Volatile
    var playheadBytesProvider: (() -> Long)? = null

    private val lock = Any()
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sharedCache: SimpleCache? = null
    private var databaseProvider: StandaloneDatabaseProvider? = null
    private var configuredMaxBytes: Long = -1L
    private var disabledForSession: Boolean = false
    private var evictor: CountingCacheEvictor? = null
    private var pendingEviction: Job? = null
    private val bytesReadFromCache = AtomicLong(0L)
    private val writeCounters = VodCacheWriteSink.Counters()

    fun wrap(
        context: Context,
        upstream: DataSource.Factory,
        url: String,
        enabled: Boolean,
        sizeMode: VodCacheSizeMode,
        sizeMb: Int,
    ): DataSource.Factory {
        if (!enabled || !shouldCache(url)) return upstream
        pendingEviction?.cancel()
        pendingEviction = null
        evictor?.resetTrimAnchor()
        val appContext = context.applicationContext
        val maxBytes = VodCacheSizing.resolveMaxBytes(
            mode = sizeMode,
            manualSizeMb = sizeMb,
            reclaimableBytes = appContext.cacheDir.usableSpace.coerceAtLeast(0L) + currentSpaceBytes(),
        )
        if (maxBytes <= 0L || disabledForSession) {
            Log.i(TAG, "inactive enabled=$enabled maxBytes=$maxBytes")
            return upstream
        }
        val cache = obtain(appContext, maxBytes) ?: return upstream
        Log.i(TAG, "active capMb=${maxBytes / (1024L * 1024L)} mode=$sizeMode")
        val cached = cacheFactory(upstream, cache)
        return DataSource.Factory { BufferedReadDataSource(cached.createDataSource()) }
    }

    fun evictCachedSession() {
        pendingEviction?.cancel()
        pendingEviction = maintenanceScope.launch {
            delay(EVICTION_DELAY_MS)
            val cache = synchronized(lock) { sharedCache } ?: return@launch
            clearAll(cache)
        }
    }

    private fun shouldCache(url: String): Boolean {
        val scheme = Uri.parse(url).scheme?.lowercase()
        return scheme == "https" || scheme == "http"
    }

    private fun cacheFactory(upstream: DataSource.Factory, cache: SimpleCache): DataSource.Factory {
        val sinkFactory = DataSink.Factory {
            VodCacheWriteSink(
                CacheDataSink.Factory()
                    .setCache(cache)
                    .setFragmentSize(FRAGMENT_BYTES)
                    .createDataSink(),
                writeCounters,
            )
        }
        return CacheDataSource.Factory()
            .setCache(cache)
            .setCacheWriteDataSinkFactory(sinkFactory)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setEventListener(object : CacheDataSource.EventListener {
                override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
                    bytesReadFromCache.addAndGet(cachedBytesRead)
                }

                override fun onCacheIgnored(reason: Int) {
                    Log.w(TAG, "read bypassed the cache, reason=$reason")
                }
            })
    }

    private fun obtain(context: Context, maxBytes: Long): SimpleCache? {
        synchronized(lock) {
            if (disabledForSession) return null
            val existing = sharedCache
            if (existing != null) {
                if (configuredMaxBytes != maxBytes) {
                    Log.i(
                        TAG,
                        "keeping cap ${configuredMaxBytes / (1024L * 1024L)}MB, " +
                            "${maxBytes / (1024L * 1024L)}MB applies next launch",
                    )
                }
                return existing
            }
            val dir = File(context.cacheDir, DIR_NAME)
            val movedAside = moveStaleAside(dir)
            var created = create(context, dir, maxBytes)
            if (created == null) {
                dir.deleteRecursively()
                created = create(context, dir, maxBytes)
            }
            if (created == null) {
                disabledForSession = true
                Log.w(TAG, "unavailable for this session")
            } else {
                configuredMaxBytes = maxBytes
                bytesReadFromCache.set(0L)
                writeCounters.reset()
                if (!movedAside) {
                    val cache = created
                    maintenanceScope.launch { clearAll(cache) }
                }
            }
            deleteStaleFolders(context)
            sharedCache = created
            return created
        }
    }

    private fun create(context: Context, dir: File, maxBytes: Long): SimpleCache? {
        var cache: SimpleCache? = null
        return try {
            dir.mkdirs()
            val builtEvictor = CountingCacheEvictor(maxBytes)
            val built = SimpleCache(dir, builtEvictor, database(context))
            evictor = builtEvictor
            cache = built
            built.cacheSpace
            built
        } catch (_: Throwable) {
            cache?.let { runCatching { it.release() } }
            null
        }
    }

    private fun database(context: Context): StandaloneDatabaseProvider =
        synchronized(lock) {
            databaseProvider ?: StandaloneDatabaseProvider(context.applicationContext).also {
                databaseProvider = it
            }
        }

    private fun moveStaleAside(dir: File): Boolean {
        if (!dir.exists()) return true
        return dir.renameTo(File(dir.parentFile, "$STALE_PREFIX${System.currentTimeMillis()}"))
    }

    private fun deleteStaleFolders(context: Context) {
        val appContext = context.applicationContext
        maintenanceScope.launch {
            val folders = appContext.cacheDir.listFiles { file ->
                file.name.startsWith(STALE_PREFIX)
            } ?: return@launch
            for (folder in folders) {
                try {
                    SimpleCache.delete(folder, database(appContext))
                } catch (_: Throwable) {
                    folder.deleteRecursively()
                }
            }
        }
    }

    private suspend fun clearAll(cache: SimpleCache) {
        val keys = try {
            cache.keys.toList()
        } catch (_: Exception) {
            return
        }
        for (key in keys) {
            val spans = try {
                cache.getCachedSpans(key)
            } catch (_: Exception) {
                continue
            }
            for (span in spans) {
                try {
                    cache.removeSpan(span)
                } catch (_: Exception) {
                }
                yield()
            }
        }
    }

    private fun currentSpaceBytes(): Long {
        val cache = sharedCache ?: return 0L
        return try {
            cache.cacheSpace
        } catch (_: Throwable) {
            0L
        }
    }

    private class CountingCacheEvictor(maxBytes: Long) : CacheEvictor {
        private val delegate = LeastRecentlyUsedCacheEvictor(maxBytes)

        @Volatile
        private var lastTrimPosition = 0L

        fun resetTrimAnchor() {
            lastTrimPosition = 0L
        }

        override fun requiresCacheSpanTouches(): Boolean = delegate.requiresCacheSpanTouches()

        override fun onCacheInitialized() = delegate.onCacheInitialized()

        override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
            trimBehindPlayhead(cache, key)
            delegate.onStartFile(cache, key, position, length)
        }

        override fun onSpanAdded(cache: Cache, span: CacheSpan) = delegate.onSpanAdded(cache, span)

        override fun onSpanRemoved(cache: Cache, span: CacheSpan) = delegate.onSpanRemoved(cache, span)

        override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) =
            delegate.onSpanTouched(cache, oldSpan, newSpan)

        private fun trimBehindPlayhead(cache: Cache, key: String) {
            val playhead = runCatching { playheadBytesProvider?.invoke() }.getOrNull() ?: return
            if (playhead <= 0L) return
            val cutoff = playhead - RETAIN_BEHIND_BYTES
            if (cutoff <= 0L) return
            if (playhead - lastTrimPosition < TRIM_STEP_BYTES) return
            lastTrimPosition = playhead
            val spans = try {
                cache.getCachedSpans(key)
            } catch (_: Exception) {
                return
            }
            for (span in spans) {
                if (span.position + span.length > cutoff) break
                try {
                    cache.removeSpan(span)
                } catch (_: Exception) {
                    break
                }
            }
        }
    }
}
