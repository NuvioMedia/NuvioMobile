package com.nuvio.app.core.ui

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

/**
 * Area-average (box filter) downsampling of ARGB pixels. Platform scalers use bilinear
 * sampling, which aliases badly at large ratios (e.g. 1280 to 96 px) and turns fine
 * detail into noise, so every platform goes through this instead.
 */
internal fun downsampleArgb(pixels: IntArray, srcWidth: Int, srcHeight: Int, targetWidth: Int): SampledPixels {
    val width = min(targetWidth, srcWidth).coerceAtLeast(1)
    val height = max(1, (srcHeight.toLong() * width / srcWidth).toInt())
    val out = IntArray(width * height)
    for (ty in 0 until height) {
        val y0 = (ty.toLong() * srcHeight / height).toInt()
        val y1 = max(y0 + 1, ((ty + 1).toLong() * srcHeight / height).toInt())
        for (tx in 0 until width) {
            val x0 = (tx.toLong() * srcWidth / width).toInt()
            val x1 = max(x0 + 1, ((tx + 1).toLong() * srcWidth / width).toInt())
            var r = 0L
            var g = 0L
            var b = 0L
            for (y in y0 until y1) {
                val row = y * srcWidth
                for (x in x0 until x1) {
                    val p = pixels[row + x]
                    r += (p shr 16) and 0xFF
                    g += (p shr 8) and 0xFF
                    b += p and 0xFF
                }
            }
            val n = (y1 - y0).toLong() * (x1 - x0)
            out[ty * width + tx] = (0xFF shl 24) or
                ((r / n).toInt() shl 16) or
                ((g / n).toInt() shl 8) or
                (b / n).toInt()
        }
    }
    return SampledPixels(out, width, height)
}

internal const val BackdropFocusSampleWidth = 96

private const val SkinWeight = 0.03f
private const val SkinCoverageCutoff = 0.15f

/**
 * Cheap saliency heuristic. Each column is scored by these signals, each normalized
 * across the image before being combined:
 * - coarse edges (Sobel on a blurred image): large shapes like people and objects,
 * - fine edges: detail, with a low weight so dense textures (city, foliage) do not win,
 * - saturation,
 * - skin tones, weighted by how much of the image they cover. Colour-based skin detection
 *   is only trustworthy when skin is rare: in warm or sepia images it matches walls, sky
 *   and whole backgrounds, so the signal fades out as coverage grows.
 * The bottom area (covered by gradient, logo and buttons) and the very top are down-weighted.
 */
internal fun analyzeBackdropFocus(sample: SampledPixels): BackdropFocusProfile {
    val w = sample.width
    val h = sample.height
    if (w < 3 || h < 3) return BackdropFocusProfile(FloatArray(0))

    val luma = FloatArray(w * h)
    val sat = FloatArray(w * h)
    val skin = BooleanArray(w * h)
    var skinCount = 0
    for (i in 0 until w * h) {
        val p = sample.pixels[i]
        val r = ((p shr 16) and 0xFF) / 255f
        val g = ((p shr 8) and 0xFF) / 255f
        val b = (p and 0xFF) / 255f
        luma[i] = 0.299f * r + 0.587f * g + 0.114f * b
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        sat[i] = if (mx <= 0f) 0f else (mx - mn) / mx
        val cb = 128f + (-0.168736f * r - 0.331264f * g + 0.5f * b) * 255f
        val cr = 128f + (0.5f * r - 0.418688f * g - 0.081312f * b) * 255f
        if (luma[i] > 0.08f && cb in 77f..127f && cr in 133f..173f) {
            skin[i] = true
            skinCount++
        }
    }
    val blurred = boxBlur(luma, w, h, radius = 2)

    val fineColumns = FloatArray(w)
    val coarseColumns = FloatArray(w)
    val satColumns = FloatArray(w)
    val skinColumns = FloatArray(w)
    for (y in 1 until h - 1) {
        val rowFraction = y.toFloat() / (h - 1)
        val rowWeight = when {
            rowFraction > 0.78f -> 0.35f
            rowFraction < 0.08f -> 0.6f
            else -> 1f
        }
        for (x in 1 until w - 1) {
            val i = y * w + x
            fineColumns[x] += sobel(luma, w, i) * rowWeight
            coarseColumns[x] += min(1f, sobel(blurred, w, i) * 3f) * rowWeight
            satColumns[x] += sat[i] * rowWeight
            if (skin[i]) skinColumns[x] += rowWeight
        }
    }

    val columns = FloatArray(w)
    accumulateNormalized(columns, fineColumns, weight = 0.25f)
    accumulateNormalized(columns, coarseColumns, weight = 0.5f)
    accumulateNormalized(columns, satColumns, weight = 0.35f)
    val skinCoverage = skinCount.toFloat() / (w * h)
    val skinReliability = max(0f, 1f - skinCoverage / SkinCoverageCutoff)
    accumulateNormalized(columns, skinColumns, weight = SkinWeight * skinCoverage * skinReliability * w)

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

private fun sobel(values: FloatArray, w: Int, i: Int): Float {
    val gx = (values[i - w + 1] + 2 * values[i + 1] + values[i + w + 1]) -
        (values[i - w - 1] + 2 * values[i - 1] + values[i + w - 1])
    val gy = (values[i + w - 1] + 2 * values[i + w] + values[i + w + 1]) -
        (values[i - w - 1] + 2 * values[i - w] + values[i - w + 1])
    return min(1f, sqrt(gx * gx + gy * gy))
}

private fun boxBlur(values: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
    val out = FloatArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            var acc = 0f
            var n = 0
            for (yy in max(0, y - radius)..min(h - 1, y + radius)) {
                for (xx in max(0, x - radius)..min(w - 1, x + radius)) {
                    acc += values[yy * w + xx]
                    n++
                }
            }
            out[y * w + x] = acc / n
        }
    }
    return out
}

/** Copies border columns inward, normalizes [source] to sum 1 and adds it to [target] with [weight]. */
private fun accumulateNormalized(target: FloatArray, source: FloatArray, weight: Float) {
    val w = source.size
    source[0] = source[1]
    source[w - 1] = source[w - 2]
    val total = source.sum()
    if (total <= 0f) return
    for (x in 0 until w) target[x] += source[x] / total * weight
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
private data class FocusedBackdropAlignment(
    private val profile: BackdropFocusProfile,
    private val fallback: Alignment,
) : Alignment {
    override fun align(size: IntSize, space: IntSize, layoutDirection: LayoutDirection): IntOffset {
        val base = fallback.align(size, space, layoutDirection)
        if (size.width <= space.width || size.width <= 0) return base
        val bias = profile.horizontalBias(space.width.toFloat() / size.width)
        val overflow = (space.width - size.width).toFloat()
        val x = (overflow / 2f * (1f + bias)).roundToInt()
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

    /**
     * False only while a freshly loaded image is being analyzed. The image should
     * stay hidden during that short window so it appears directly in its final framing.
     */
    var isResolved by mutableStateOf(initial != null || url == null)
        private set

    private var pending: Image? by mutableStateOf(null)

    /** Call from AsyncImage onSuccess. */
    fun onImageLoaded(image: Image) {
        if (isResolved) return
        pending = image
    }

    /** Call from AsyncImage onError so the placeholder/fallback is never hidden. */
    fun onImageFailed() {
        isResolved = true
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
        isResolved = true
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

/** Alignment to use for a cropped backdrop: [fallback] until analyzed, then the focused framing. */
@Composable
internal fun BackdropFocusState.focusedAlignment(fallback: Alignment): Alignment {
    val currentProfile = profile ?: return fallback
    return remember(currentProfile, fallback) { FocusedBackdropAlignment(currentProfile, fallback) }
}

/** Alpha multiplier for the backdrop: 0 while the first analysis is running, 1 otherwise. */
internal val BackdropFocusState.revealAlpha: Float
    get() = if (isResolved) 1f else 0f
