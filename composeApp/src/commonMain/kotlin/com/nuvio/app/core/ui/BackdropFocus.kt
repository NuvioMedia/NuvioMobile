package com.nuvio.app.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import coil3.Image
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Horizontal "interest" profile of a backdrop, used to pick the crop window
 * when a wide backdrop is shown in a narrower hero (ContentScale.Crop).
 *
 * [columns] holds the normalized saliency energy per sampled column.
 */
internal class BackdropFocusProfile(val columns: FloatArray) {

    /**
     * Returns the horizontal bias (-1..1) that best frames the salient region
     * when only [visibleFraction] of the image width is visible.
     */
    fun horizontalBias(visibleFraction: Float): Float {
        val count = columns.size
        if (count == 0 || visibleFraction >= MinCropFraction) return 0f
        val window = (visibleFraction * count).roundToInt().coerceIn(1, count)
        val maxStart = count - window
        if (maxStart <= 0) return 0f

        var sum = 0f
        for (i in 0 until window) sum += columns[i]
        var bestSum = sum
        var bestStart = 0
        for (start in 1..maxStart) {
            sum += columns[start + window - 1] - columns[start - 1]
            if (sum > bestSum) {
                bestSum = sum
                bestStart = start
            }
        }

        val centerStart = maxStart / 2
        var centerSum = 0f
        for (i in centerStart until centerStart + window) centerSum += columns[i]
        // Keep centered framing unless the best window is clearly better.
        if (bestSum <= centerSum * CenterPreferenceRatio) return 0f

        val bias = (2f * bestStart / maxStart - 1f) * BiasDamping
        return if (abs(bias) < BiasDeadZone) 0f else bias.coerceIn(-1f, 1f)
    }

    private companion object {
        const val MinCropFraction = 0.97f
        const val CenterPreferenceRatio = 1.12f
        const val BiasDamping = 0.9f
        const val BiasDeadZone = 0.12f
    }
}

/** Downsampled ARGB pixels of a loaded image. */
internal class SampledPixels(val pixels: IntArray, val width: Int, val height: Int)

/** Platform hook: decode a loaded Coil image into small ARGB pixels (about [targetWidth] wide). */
internal expect fun sampleBackdropPixels(image: Image, targetWidth: Int): SampledPixels?

internal const val BackdropFocusSampleWidth = 96

/**
 * Cheap saliency heuristic: edge strength + saturation + local contrast + skin tones,
 * with the bottom area (covered by gradient, logo and buttons) down-weighted.
 */
internal fun analyzeBackdropFocus(sample: SampledPixels): BackdropFocusProfile {
    val w = sample.width
    val h = sample.height
    if (w < 3 || h < 3) return BackdropFocusProfile(FloatArray(0))

    val luma = FloatArray(w * h)
    val sat = FloatArray(w * h)
    val skin = FloatArray(w * h)
    var lumaSum = 0f
    for (i in 0 until w * h) {
        val p = sample.pixels[i]
        val r = ((p shr 16) and 0xFF) / 255f
        val g = ((p shr 8) and 0xFF) / 255f
        val b = (p and 0xFF) / 255f
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        luma[i] = y
        lumaSum += y
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        sat[i] = if (mx <= 0f) 0f else (mx - mn) / mx
        val cb = 128f + (-0.168736f * r - 0.331264f * g + 0.5f * b) * 255f
        val cr = 128f + (0.5f * r - 0.418688f * g - 0.081312f * b) * 255f
        skin[i] = if (y > 0.15f && cb in 77f..127f && cr in 133f..173f) 1f else 0f
    }
    val lumaMean = lumaSum / (w * h)

    val columns = FloatArray(w)
    for (y in 1 until h - 1) {
        val rowFraction = y.toFloat() / (h - 1)
        val rowWeight = when {
            rowFraction > 0.78f -> 0.35f
            rowFraction < 0.08f -> 0.6f
            else -> 1f
        }
        for (x in 1 until w - 1) {
            val i = y * w + x
            val gx = (luma[i - w + 1] + 2 * luma[i + 1] + luma[i + w + 1]) -
                (luma[i - w - 1] + 2 * luma[i - 1] + luma[i + w - 1])
            val gy = (luma[i + w - 1] + 2 * luma[i + w] + luma[i + w + 1]) -
                (luma[i - w - 1] + 2 * luma[i - w] + luma[i - w + 1])
            val edge = min(1f, sqrt(gx * gx + gy * gy))
            val contrast = abs(luma[i] - lumaMean)
            val energy = edge * 1.0f + sat[i] * 0.35f + contrast * 0.4f + skin[i] * 0.6f
            columns[x] += energy * rowWeight
        }
    }
    columns[0] = columns[1]
    columns[w - 1] = columns[w - 2]

    // Light smoothing so a single noisy column does not pull the window.
    val smoothed = FloatArray(w)
    for (x in 0 until w) {
        var acc = 0f
        var n = 0
        for (k in -2..2) {
            val xx = x + k
            if (xx in 0 until w) {
                acc += columns[xx]
                n++
            }
        }
        smoothed[x] = acc / n
    }
    val total = smoothed.sum()
    if (total > 0f) for (x in 0 until w) smoothed[x] /= total
    return BackdropFocusProfile(smoothed)
}

internal object BackdropFocusCache {
    private const val MaxEntries = 256
    private val entries = LinkedHashMap<String, BackdropFocusProfile>()

    fun get(url: String): BackdropFocusProfile? = entries[url]?.also {
        entries.remove(url)
        entries[url] = it
    }

    fun put(url: String, profile: BackdropFocusProfile) {
        entries.remove(url)
        entries[url] = profile
        while (entries.size > MaxEntries) entries.remove(entries.keys.first())
    }
}

/**
 * Alignment that keeps [fallback] for the vertical axis and picks a smart
 * horizontal offset when the image overflows the container horizontally.
 */
private class FocusedBackdropAlignment(
    private val profile: BackdropFocusProfile,
    private val strength: Float,
    private val fallback: Alignment,
) : Alignment {
    override fun align(size: IntSize, space: IntSize, layoutDirection: LayoutDirection): IntOffset {
        val base = fallback.align(size, space, layoutDirection)
        if (size.width <= space.width || size.width <= 0) return base
        val bias = profile.horizontalBias(space.width.toFloat() / size.width)
        val overflow = (space.width - size.width).toFloat()
        val baseBias = if (overflow == 0f) 0f else 2f * base.x / overflow - 1f
        val finalBias = baseBias + (bias - baseBias) * strength.coerceIn(0f, 1f)
        val x = (overflow / 2f * (1f + finalBias)).roundToInt()
        return IntOffset(x, base.y)
    }
}

@Stable
internal class BackdropFocusState(
    private val url: String?,
    initial: BackdropFocusProfile?,
) {
    var profile by mutableStateOf(initial)
        private set

    val wasCached: Boolean = initial != null
    private var pending: Image? by mutableStateOf(null)

    /** Call from AsyncImage onSuccess. */
    fun onImageLoaded(image: Image) {
        if (profile == null && url != null) pending = image
    }

    internal suspend fun process() {
        val image = pending ?: return
        val key = url ?: return
        val result = withContext(Dispatchers.Default) {
            runCatching {
                sampleBackdropPixels(image, BackdropFocusSampleWidth)?.let(::analyzeBackdropFocus)
            }.getOrNull()
        }
        pending = null
        if (result != null) {
            BackdropFocusCache.put(key, result)
            profile = result
        }
    }

    internal val pendingImage: Image? get() = pending
}

@Composable
internal fun rememberBackdropFocusState(url: String?): BackdropFocusState {
    val state = remember(url) {
        BackdropFocusState(url, url?.let(BackdropFocusCache::get))
    }
    LaunchedEffect(state, state.pendingImage) { state.process() }
    return state
}

/**
 * Returns the alignment to use for a cropped backdrop. Falls back to [fallback]
 * until the image is analyzed, then animates towards the focused framing.
 */
@Composable
internal fun BackdropFocusState.focusedAlignment(fallback: Alignment): Alignment {
    val currentProfile = profile ?: return fallback
    val strength = remember(currentProfile) { Animatable(if (wasCached) 1f else 0f) }
    LaunchedEffect(currentProfile) {
        strength.animateTo(1f, tween(durationMillis = 450))
    }
    val value = strength.value
    return remember(currentProfile, value, fallback) {
        FocusedBackdropAlignment(currentProfile, value, fallback)
    }
}
