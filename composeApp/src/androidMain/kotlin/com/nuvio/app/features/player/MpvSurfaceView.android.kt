package com.nuvio.app.features.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import `is`.xyz.mpv.MPV
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal abstract class MpvSurfaceView(context: Context, private val vo: String) : FrameLayout(context) {
    val mpv = MPV()
    protected val mpvDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NuvioLibmpv").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val mpvScope = CoroutineScope(SupervisorJob() + mpvDispatcher)
    protected val surfaceReady = CompletableDeferred<Unit>()
    protected val released = AtomicBoolean(false)
    private var destroyJob: Job? = null
    private var surfaceAttached = false

    protected abstract fun initOptions()
    protected abstract fun observeProperties()

    fun initialize(configDir: String, cacheDir: String) {
        mpv.create(context)
        mpv.setOptionString("config", "yes")
        mpv.setOptionString("config-dir", configDir)
        mpv.setOptionString("gpu-shader-cache-dir", cacheDir)
        mpv.setOptionString("icc-cache-dir", cacheDir)
        mpv.setOptionString("vo", vo)
        initOptions()
        mpv.init()
        mpv.setOptionString("force-window", "no")
        mpv.setOptionString("idle", "once")
        observeProperties()
        addView(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) createSurfaceView() else createTextureView())
    }

    protected fun executeMpv(block: () -> Unit) {
        if (released.get()) return
        mpvScope.launch {
            if (!released.get()) runCatching(block).onFailure(::logFailure)
        }
    }

    private fun attach(surface: Surface) = executeMpv {
        mpv.attachSurface(surface)
        surfaceAttached = true
        mpv.setOptionString("force-window", "yes")
        mpv.setPropertyString("vo", vo)
        surfaceReady.complete(Unit)
    }

    private fun resize(width: Int, height: Int) = executeMpv {
        mpv.setPropertyString("android-surface-size", "${width}x$height")
    }

    private fun detach(releaseSurface: () -> Unit) {
        val job = destroyJob ?: mpvScope.launch { detachSurface() }
        job.invokeOnCompletion { releaseSurface() }
    }

    private fun detachSurface() {
        if (!surfaceAttached) return
        surfaceAttached = false
        runCatching {
            try {
                mpv.setPropertyString("vo", "null")
                mpv.setPropertyString("force-window", "no")
            } finally {
                mpv.detachSurface()
            }
        }.onFailure(::logFailure)
    }

    fun releaseMpv() {
        if (!released.compareAndSet(false, true)) return
        surfaceReady.cancel()
        destroyJob = mpvScope.launch {
            try {
                detachSurface()
                runCatching { mpv.destroy() }.onFailure(::logFailure)
            } finally {
                mpvDispatcher.close()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createSurfaceView() = SurfaceView(context).apply {
        holder.addCallback(object : SurfaceHolder.Callback {
            private lateinit var layer: SurfaceControl
            private lateinit var output: Surface

            override fun surfaceCreated(holder: SurfaceHolder) {
                val frame = holder.surfaceFrame
                layer = SurfaceControl.Builder()
                    .setName("MPV video")
                    .setParent(surfaceControl)
                    .setBufferSize(frame.width(), frame.height())
                    .build()
                output = Surface(layer)
                SurfaceControl.Transaction().use {
                    it.setLayer(layer, 1).setVisibility(layer, true).apply()
                }
                attach(output)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                SurfaceControl.Transaction().use { it.setBufferSize(layer, width, height).apply() }
                resize(width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                val oldLayer = layer
                val oldOutput = output
                SurfaceControl.Transaction().use { it.reparent(oldLayer, null).apply() }
                detach {
                    oldOutput.release()
                    oldLayer.release()
                }
            }
        })
    }

    private fun createTextureView() = TextureView(context).apply {
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            private lateinit var output: Surface

            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                output = Surface(texture)
                attach(output)
                resize(width, height)
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                resize(width, height)
            }

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                val oldOutput = output
                detach {
                    oldOutput.release()
                    texture.release()
                }
                return false
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
    }

    private fun logFailure(error: Throwable) {
        Log.w("MpvSurfaceView", "libmpv operation failed", error)
    }
}
