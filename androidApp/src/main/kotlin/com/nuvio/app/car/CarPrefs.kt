package com.nuvio.app.car

import android.content.Context
import com.nuvio.app.features.player.CarPlayerOverrides

/**
 * Nuvio Auto settings, shared by the phone setup screen and the car screen. Every in-car extra has its own
 * switch so anything that misbehaves can be turned off on its own, and [basicMode] turns them all off at once.
 */
internal object CarPrefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nuvio_auto", Context.MODE_PRIVATE)

    /** A boolean setting with a default; [get] honours [basicMode] for extras. */
    class Flag(val key: String, val default: Boolean, val extra: Boolean = true) {
        fun stored(ctx: Context): Boolean = prefs(ctx).getBoolean(key, default)
        fun get(ctx: Context): Boolean = if (extra && basicMode(ctx)) false else stored(ctx)
        fun set(ctx: Context, on: Boolean) {
            prefs(ctx).edit().putBoolean(key, on).apply()
        }
    }

    // ---- Master switch -------------------------------------------------------------------------------------------

    /** Basic mode: Nuvio on the car screen with every extra off, for troubleshooting. */
    fun basicMode(ctx: Context): Boolean = prefs(ctx).getBoolean("basicMode", false)

    fun setBasicMode(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("basicMode", on).apply()
    }

    // ---- Startup --------------------------------------------------------------------------------------------------

    /** Profile the car opens into, skipping "Who's watching"; null asks as usual. */
    fun carProfile(ctx: Context): Int? = if (basicMode(ctx)) null else storedCarProfile(ctx)

    fun storedCarProfile(ctx: Context): Int? = prefs(ctx).getInt("carProfile", -1).takeIf { it >= 0 }

    fun setCarProfile(ctx: Context, profileIndex: Int?) {
        prefs(ctx).edit().putInt("carProfile", profileIndex ?: -1).apply()
    }

    val CAR_LOCK = Flag("carLock", false)
    val RESUME_PROMPT = Flag("resumePrompt", true)

    // ---- Playback -------------------------------------------------------------------------------------------------

    val FILL_VIDEO = Flag("fillVideo", true)
    val BIG_BUFFER = Flag("bigBuffer", true)
    val AUTO_SKIP_INTROS = Flag("autoSkipIntros", true)
    val AUTO_NEXT_EPISODE = Flag("autoNextEpisode", true)
    val SUBTITLE_BACKGROUND = Flag("subtitleBackground", true)
    val PAUSE_ON_DISCONNECT = Flag("pauseOnDisconnect", true)

    val SUBTITLE_SCALES = floatArrayOf(1f, 1.2f, 1.4f, 1.6f, 1.8f)
    val SUBTITLE_LABELS = arrayOf("Normal", "Large", "Larger", "Huge", "Huger")

    fun subtitleScaleIndex(ctx: Context): Int =
        prefs(ctx).getInt("subtitleScale", 2).coerceIn(0, SUBTITLE_SCALES.lastIndex)

    fun setSubtitleScaleIndex(ctx: Context, index: Int) {
        prefs(ctx).edit().putInt("subtitleScale", index).apply()
    }

    val STEERING_LABELS = linkedMapOf(
        CarPlayerOverrides.SteeringMode.EPISODES to "Next episode / back 10 s",
        CarPlayerOverrides.SteeringMode.SEEK to "Forward 30 s / back 10 s",
        CarPlayerOverrides.SteeringMode.DEFAULT to "Nuvio's default",
    )

    fun steering(ctx: Context): CarPlayerOverrides.SteeringMode = runCatching {
        CarPlayerOverrides.SteeringMode.valueOf(prefs(ctx).getString("steering", null) ?: "EPISODES")
    }.getOrDefault(CarPlayerOverrides.SteeringMode.EPISODES)

    fun setSteering(ctx: Context, mode: CarPlayerOverrides.SteeringMode) {
        prefs(ctx).edit().putString("steering", mode.name).apply()
    }

    // ---- Data -----------------------------------------------------------------------------------------------------

    /** When to prefer streams at or under 720p while Nuvio is on the car screen. */
    enum class DataSaver(val label: String) {
        ALWAYS("Always in the car"),
        MOBILE_DATA("Only on mobile data"),
        OFF("Off"),
    }

    fun dataSaver(ctx: Context): DataSaver = if (basicMode(ctx)) DataSaver.OFF else storedDataSaver(ctx)

    fun storedDataSaver(ctx: Context): DataSaver {
        val p = prefs(ctx)
        val stored = p.getString("dataSaverMode", null)
        if (stored != null) return runCatching { DataSaver.valueOf(stored) }.getOrDefault(DataSaver.ALWAYS)
        return if (p.contains("dataSaver") && !p.getBoolean("dataSaver", true)) DataSaver.OFF else DataSaver.ALWAYS
    }

    fun setDataSaver(ctx: Context, mode: DataSaver) {
        prefs(ctx).edit().putString("dataSaverMode", mode.name).apply()
    }

    const val DATA_SAVER_MAX_HEIGHT = 720

    // ---- Car screen -----------------------------------------------------------------------------------------------

    /** Car UI size, as a multiplier on the head unit's dpi: bigger dpi means bigger text and buttons. */
    val SIZE_SCALES = floatArrayOf(0.8f, 0.9f, 1.0f, 1.15f, 1.3f, 1.5f)
    val SIZE_LABELS = arrayOf("Smallest", "Small", "Normal", "Large", "Larger", "Largest")

    fun sizeIndex(ctx: Context): Int = prefs(ctx).getInt("size", 2).coerceIn(0, SIZE_SCALES.lastIndex)

    fun setSizeIndex(ctx: Context, index: Int) {
        prefs(ctx).edit().putInt("size", index.coerceIn(0, SIZE_SCALES.lastIndex)).apply()
    }

    fun nextSize(ctx: Context): Int {
        val next = (sizeIndex(ctx) + 1) % SIZE_SCALES.size
        setSizeIndex(ctx, next)
        return next
    }

    fun scaledDpi(ctx: Context, carDpi: Int): Int = (carDpi * SIZE_SCALES[sizeIndex(ctx)]).toInt().coerceAtLeast(80)

    val HIDE_WHILE_DRIVING = Flag("hideWhileDriving", true, extra = false)
    val AVOID_CAR_BUTTONS = Flag("avoidButtons2", false, extra = false)
    val HIDE_STATUS_OVERLAY = Flag("hideStatusOverlay", true)
    val BAR_AT_BOTTOM = Flag("barAtBottom", true, extra = false)

    val BAR_TIMEOUTS = longArrayOf(3_000L, 5_000L, 8_000L, 0L)
    val BAR_TIMEOUT_LABELS = arrayOf("3 seconds", "5 seconds", "8 seconds", "Always show")

    fun barTimeoutIndex(ctx: Context): Int = prefs(ctx).getInt("barTimeout", 1).coerceIn(0, BAR_TIMEOUTS.lastIndex)

    fun setBarTimeoutIndex(ctx: Context, index: Int) {
        prefs(ctx).edit().putInt("barTimeout", index).apply()
    }

    // ---- Phone ----------------------------------------------------------------------------------------------------

    val REMOTE_NOTIFICATION = Flag("remoteNotification", true)
    val CLUSTER_MIRROR = Flag("clusterMirror", false)

    // ---- Accessors used around the app ----------------------------------------------------------------------------

    fun hideVideoWhileDriving(ctx: Context) = HIDE_WHILE_DRIVING.get(ctx)
    fun avoidCarButtons(ctx: Context) = AVOID_CAR_BUTTONS.get(ctx)
    fun resumePrompt(ctx: Context) = RESUME_PROMPT.get(ctx)
    fun remoteNotification(ctx: Context) = REMOTE_NOTIFICATION.get(ctx)
    fun clusterMirror(ctx: Context) = CLUSTER_MIRROR.get(ctx)
    fun fillVideo(ctx: Context) = FILL_VIDEO.get(ctx)

    /** Profile names seen last time Nuvio was signed in, for the profile picker when Nuvio isn't loaded. */
    fun cachedProfiles(ctx: Context): List<Pair<Int, String>> =
        prefs(ctx).getString("profileCache", null).orEmpty().lines().mapNotNull { line ->
            val index = line.substringBefore('\t').toIntOrNull() ?: return@mapNotNull null
            index to line.substringAfter('\t')
        }

    fun cacheProfiles(ctx: Context, profiles: List<Pair<Int, String>>) {
        if (profiles.isEmpty()) return
        prefs(ctx).edit().putString("profileCache", profiles.joinToString("\n") { "${it.first}\t${it.second}" }).apply()
    }

    /** Back to defaults (the profile list cache is kept). */
    fun reset(ctx: Context) {
        val keepProfiles = prefs(ctx).getString("profileCache", null)
        prefs(ctx).edit().clear().putString("profileCache", keepProfiles).apply()
    }
}
