package com.nuvio.app.car

import android.content.Context

/** Nuvio Auto settings, shared by the phone setup screen and the car screen. */
internal object CarPrefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nuvio_auto", Context.MODE_PRIVATE)

    /** Car UI size, as a multiplier on the head unit's dpi: bigger dpi means bigger text and buttons. */
    val SIZE_SCALES = floatArrayOf(0.8f, 0.9f, 1.0f, 1.15f, 1.3f, 1.5f)
    val SIZE_LABELS = arrayOf("Smallest", "Small", "Normal", "Large", "Larger", "Largest")
    private const val DEFAULT_SIZE = 2

    fun sizeIndex(ctx: Context): Int =
        prefs(ctx).getInt("size", DEFAULT_SIZE).coerceIn(0, SIZE_SCALES.lastIndex)

    fun setSizeIndex(ctx: Context, index: Int) {
        prefs(ctx).edit().putInt("size", index.coerceIn(0, SIZE_SCALES.lastIndex)).apply()
    }

    fun nextSize(ctx: Context): Int {
        val next = (sizeIndex(ctx) + 1) % SIZE_SCALES.size
        setSizeIndex(ctx, next)
        return next
    }

    fun scaledDpi(ctx: Context, carDpi: Int): Int =
        (carDpi * SIZE_SCALES[sizeIndex(ctx)]).toInt().coerceAtLeast(80)

    /** Cover the video while the phone's GPS says the car is moving. Audio keeps playing. */
    fun hideVideoWhileDriving(ctx: Context): Boolean = prefs(ctx).getBoolean("hideWhileDriving", true)

    fun setHideVideoWhileDriving(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("hideWhileDriving", on).apply()
    }

    /**
     * Keep Nuvio's content out from under Android Auto's buttons (the host's stable area). Off by default:
     * the host's stable area is very conservative and left big black borders around Nuvio.
     */
    fun avoidCarButtons(ctx: Context): Boolean = prefs(ctx).getBoolean("avoidButtons2", false)

    fun setAvoidCarButtons(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("avoidButtons2", on).apply()
    }

    /** Prefer streams at or under 720p while in the car on mobile data. */
    fun dataSaver(ctx: Context): Boolean = prefs(ctx).getBoolean("dataSaver", true)

    fun setDataSaver(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("dataSaver", on).apply()
    }

    /** Offer to resume the newest Continue Watching item when the car connects. */
    fun resumePrompt(ctx: Context): Boolean = prefs(ctx).getBoolean("resumePrompt", true)

    fun setResumePrompt(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("resumePrompt", on).apply()
    }

    /** Show a phone notification with the remote while Nuvio is on the car screen. */
    fun remoteNotification(ctx: Context): Boolean = prefs(ctx).getBoolean("remoteNotification", true)

    fun setRemoteNotification(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("remoteNotification", on).apply()
    }

    const val DATA_SAVER_MAX_HEIGHT = 720
}
