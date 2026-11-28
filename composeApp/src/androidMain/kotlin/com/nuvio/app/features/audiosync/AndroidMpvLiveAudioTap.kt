package com.nuvio.app.features.audiosync

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import `is`.xyz.mpv.MPV
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Android bridge to libmpv's live audio tap JNI methods on [MPV].
 *
 * Uses a single class-level reflective binding so the app builds cleanly while `mpv-android-lib`
 * adopts `patches/libmpv/0010-audio-tap.patch`. All native tap calls (`create`, `read`,
 * `setPaused`, `destroy`) and lifecycle teardown (`detach`) are serialized under [lock] with
 * monotonic ownership tokens so a reader thread can never dereference a freed native ring buffer.
 */
internal class AndroidMpvLiveAudioTap(
    internal val mpv: MPV,
    hostView: View? = null,
) : MpvLiveAudioTap {
    private val lock = Any()
    private val hostViewRef = hostView?.let(::WeakReference)
    private val metaBuffer = LongArray(META_FIELDS)
    private var tapHandle: Long = 0L
    private var activeToken: Long = 0L
    private var nextToken: Long = 0L
    @Volatile
    private var detached: Boolean = false
    @Volatile
    private var apiVersionVerified: Boolean? = null

    override val isSupported: Boolean
        get() = bindings != null

    /** Lock-free availability check so Main-thread ticks never wait on [enable] / `mp_dispatch_lock`. */
    override val isAvailable: Boolean
        get() {
            val api = bindings ?: return false
            if (detached || !mpv.isInitialized) return false
            if (api.apiVersionIsStatic) return true
            val cached = apiVersionVerified
            if (cached != null) return cached
            val matches = runCatching {
                (api.apiVersion.invoke(mpv) as? Number)?.toInt() == MpvLiveAudioTap.API_VERSION
            }.getOrDefault(false)
            apiVersionVerified = matches
            return matches
        }

    fun isBackgroundOrPip(): Boolean {
        val view = hostViewRef?.get() ?: return false
        if (view.windowVisibility != View.VISIBLE) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val activity = view.context.findHostActivity()
            if (activity?.isInPictureInPictureMode == true) return true
        }
        return false
    }

    override fun enable(ringCapacitySamples: Int): Long {
        val api = bindings ?: return 0L
        synchronized(lock) {
            if (detached || !mpv.isInitialized) return 0L
            val target = if (api.apiVersionIsStatic) null else mpv
            val version = runCatching { (api.apiVersion.invoke(target) as? Number)?.toInt() }.getOrNull()
            if (version != MpvLiveAudioTap.API_VERSION) return 0L
            if (tapHandle != 0L) {
                val stale = tapHandle
                tapHandle = 0L
                activeToken = 0L
                runCatching { api.destroy.invoke(mpv, stale) }
            }
            val handle = runCatching {
                (api.create.invoke(mpv, ringCapacitySamples) as? Number)?.toLong() ?: 0L
            }.getOrDefault(0L)
            if (handle == 0L) return 0L
            tapHandle = handle
            activeToken = ++nextToken
            return activeToken
        }
    }

    override fun setPaused(token: Long, paused: Boolean) {
        val api = bindings ?: return
        if (token == 0L) return
        synchronized(lock) {
            if (detached || token != activeToken) return
            val handle = tapHandle
            if (handle == 0L) return
            runCatching { api.setPaused.invoke(mpv, handle, paused) }
        }
    }

    override fun readInto(token: Long, outSamples: FloatArray, maxSamples: Int, outMeta: MpvAudioTapChunk): Int {
        val api = bindings ?: return -1
        val limit = minOf(maxSamples, outSamples.size)
        if (limit <= 0) return -1
        synchronized(lock) {
            if (detached || token == 0L || token != activeToken || tapHandle == 0L) {
                outMeta.flags = MpvAudioTapFlags.EOF
                return 0
            }
            metaBuffer.fill(0L)
            val read = runCatching {
                (api.read.invoke(mpv, tapHandle, outSamples, limit, metaBuffer) as? Number)?.toInt() ?: -1
            }.getOrDefault(-1)
            outMeta.sampleRate = metaBuffer[0].toInt()
            outMeta.sampleCount = metaBuffer[1].toInt()
            outMeta.generation = metaBuffer[2].toInt()
            outMeta.ptsUs = metaBuffer[3]
            outMeta.flags = metaBuffer[4].toInt()
            return read
        }
    }

    override fun disable(token: Long) {
        val api = bindings ?: return
        if (token == 0L) return
        synchronized(lock) {
            if (token != activeToken) return
            val handle = tapHandle
            tapHandle = 0L
            activeToken = 0L
            if (handle != 0L) {
                runCatching { api.destroy.invoke(mpv, handle) }
            }
        }
    }

    /** Marks this mpv instance detached and synchronously destroys any active tap before `mpv.close()`. */
    fun detach() {
        val api = bindings
        synchronized(lock) {
            detached = true
            val handle = tapHandle
            tapHandle = 0L
            activeToken = 0L
            if (handle != 0L && api != null) {
                runCatching { api.destroy.invoke(mpv, handle) }
            }
        }
    }

    private class Bindings(
        val apiVersion: Method,
        val apiVersionIsStatic: Boolean,
        val create: Method,
        val read: Method,
        val setPaused: Method,
        val destroy: Method,
    )

    private companion object {
        const val META_FIELDS = 5

        val bindings: Bindings? by lazy {
            runCatching {
                val cls = MPV::class.java
                val apiVersion = cls.getMethod("audioTapApiVersion")
                val isStatic = Modifier.isStatic(apiVersion.modifiers)
                if (isStatic) {
                    val version = (apiVersion.invoke(null) as? Number)?.toInt()
                    if (version != MpvLiveAudioTap.API_VERSION) return@runCatching null
                }
                Bindings(
                    apiVersion = apiVersion,
                    apiVersionIsStatic = isStatic,
                    create = cls.getMethod("audioTapCreate", Int::class.javaPrimitiveType),
                    read = cls.getMethod(
                        "audioTapRead",
                        Long::class.javaPrimitiveType,
                        FloatArray::class.java,
                        Int::class.javaPrimitiveType,
                        LongArray::class.java,
                    ),
                    setPaused = cls.getMethod(
                        "audioTapSetPaused",
                        Long::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                    ),
                    destroy = cls.getMethod("audioTapDestroy", Long::class.javaPrimitiveType),
                )
            }.getOrNull()
        }

        private tailrec fun Context.findHostActivity(): Activity? =
            when (this) {
                is Activity -> this
                is ContextWrapper -> baseContext.findHostActivity()
                else -> null
            }
    }
}
