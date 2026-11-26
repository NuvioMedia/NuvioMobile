package com.nuvio.app.features.audiosync

import android.app.ActivityManager
import android.content.Context

/** Device limits for sampling audio across the film over extra connections. */
internal object SpotConnections {
    /** 2 GB boxes report a little under 2 GB; the same line the Live TV previews use. */
    private const val LOW_MEMORY_BYTES = 2_560L * 1024 * 1024

    fun isLowMemoryTv(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return true
        if (manager.isLowRamDevice) return true
        val info = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        return info.totalMem in 1 until LOW_MEMORY_BYTES
    }
}
