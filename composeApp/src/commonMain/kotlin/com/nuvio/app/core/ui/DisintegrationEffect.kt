package com.nuvio.app.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun DisintegratingContainer(
    disintegrating: Boolean,
    onDisintegrated: () -> Unit,
    modifier: Modifier = Modifier,
    durationMillis: Int = 1500,
    onDisintegrationStarted: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val graphicsLayer = rememberGraphicsLayer()
    val progress = remember { Animatable(0f) }
    var field by remember { mutableStateOf<AshField?>(null) }
    val seed = remember { Random.nextLong() }
    val onDisintegratedState = rememberUpdatedState(onDisintegrated)
    val onDisintegrationStartedState = rememberUpdatedState(onDisintegrationStarted)

    LaunchedEffect(disintegrating) {
        if (!disintegrating) return@LaunchedEffect
        val bitmap = runCatching { graphicsLayer.toImageBitmap() }.getOrNull()
        if (bitmap == null) {
            onDisintegrationStartedState.value()
            onDisintegratedState.value()
            return@LaunchedEffect
        }
        field = withContext(Dispatchers.Default) { buildAshField(bitmap, seed) }
        onDisintegrationStartedState.value()
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = durationMillis, easing = FastOutSlowInEasing),
        )
        onDisintegratedState.value()
    }

    Box(
        modifier = modifier.drawWithContent {
            val activeField = field
            if (activeField == null) {
                graphicsLayer.record { this@drawWithContent.drawContent() }
                drawLayer(graphicsLayer)
            } else {
                drawAsh(activeField, progress.value)
            }
        },
    ) {
        content()
    }
}

private class AshField(
    val bitmap: ImageBitmap,
    val cols: Int,
    val rows: Int,
    val wavePhase: Float,
    val argb: IntArray,
    val startDelay: FloatArray,
    val dirX: FloatArray,
    val speed: FloatArray,
    val swirlPhase: FloatArray,
    val swirlFreq: FloatArray,
    val swirlAmp: FloatArray,
) {
    val count = argb.size

    val centersX = FloatArray(count)
    val centersY = FloatArray(count)
    val halfWidths = FloatArray(count)
    val halfHeights = FloatArray(count)
    val colors = IntArray(count)
    val renderer = AshSwarmRenderer(count)
    val clipOutline = Path()
}

private const val ASH_COLS = 130
private const val TILE_LIFESPAN = 0.5f
private const val TAU = 6.2831855f

private fun frontWave(ny: Float, phase: Float): Float =
    sin(ny * TAU * 1.15f + phase) * 0.045f + sin(ny * TAU * 2.4f + phase * 1.7f) * 0.02f

private fun frontValue(nx: Float, ny: Float, phase: Float): Float =
    0.52f * (1f - ny) + 0.48f * nx + frontWave(ny, phase)

private fun buildAshField(bitmap: ImageBitmap, seed: Long): AshField {
    val aspect = bitmap.height.toFloat() / bitmap.width.toFloat()
    val maxTiles = ashSwarmMaxGrainBudget
    var cols = ASH_COLS
    var rows = (cols * aspect).toInt().coerceAtLeast(8)
    if (cols * rows > maxTiles) {
        cols = (kotlin.math.sqrt(maxTiles / aspect)).toInt().coerceAtLeast(12)
        rows = (cols * aspect).toInt().coerceAtLeast(8)
    }
    val pixels = bitmap.toPixelMap()
    val rnd = Random(seed)
    val wavePhase = rnd.nextFloat() * TAU

    val count = cols * rows
    val argb = IntArray(count)
    val startDelay = FloatArray(count)
    val dirX = FloatArray(count)
    val speed = FloatArray(count)
    val swirlPhase = FloatArray(count)
    val swirlFreq = FloatArray(count)
    val swirlAmp = FloatArray(count)

    for (index in 0 until count) {
        val nx = (index % cols + 0.5f) / cols
        val ny = (index / cols + 0.5f) / rows

        val px = (nx * (bitmap.width - 1)).toInt().coerceIn(0, bitmap.width - 1)
        val py = (ny * (bitmap.height - 1)).toInt().coerceIn(0, bitmap.height - 1)
        val sampled = pixels[px, py]
        val shade = 0.80f + rnd.nextFloat() * 0.34f
        argb[index] = Color(
            red = (sampled.red * shade).coerceIn(0f, 1f),
            green = (sampled.green * shade).coerceIn(0f, 1f),
            blue = (sampled.blue * shade).coerceIn(0f, 1f),
            alpha = sampled.alpha,
        ).toArgb()

        val front = frontValue(nx, ny, wavePhase)
        startDelay[index] = ((1f - front) * (1f - TILE_LIFESPAN) - rnd.nextFloat() * 0.05f)
            .coerceIn(0f, 1f - TILE_LIFESPAN)

        dirX[index] = 0.55f + (rnd.nextFloat() - 0.4f) * 1.3f
        speed[index] = 0.7f + rnd.nextFloat() * 0.7f
        swirlPhase[index] = rnd.nextFloat() * TAU
        swirlFreq[index] = 1.1f + rnd.nextFloat() * 1.8f
        swirlAmp[index] = 0.6f + rnd.nextFloat() * 0.9f
    }
    return AshField(bitmap, cols, rows, wavePhase, argb, startDelay, dirX, speed, swirlPhase, swirlFreq, swirlAmp)
}

private fun DrawScope.drawAsh(field: AshField, p: Float) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    val threshold = 1f - p / (1f - TILE_LIFESPAN)
    if (threshold > -0.1f) {
        val intact = field.clipOutline
        intact.rewind()
        val steps = 28
        for (i in 0..steps) {
            val ny = i / steps.toFloat()
            val boundary = (threshold - 0.52f * (1f - ny) - frontWave(ny, field.wavePhase)) / 0.48f
            val x = (boundary * w).coerceIn(0f, w)
            val y = ny * h
            if (i == 0) intact.moveTo(x, y) else intact.lineTo(x, y)
        }
        intact.lineTo(0f, h)
        intact.lineTo(0f, 0f)
        intact.close()
        clipPath(intact) {
            drawImage(
                image = field.bitmap,
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)),
            )
        }
    }

    val cols = field.cols
    val cellW = w / cols
    val cellH = h / field.rows
    val tileW = cellW + 0.6f
    val tileH = cellH + 0.6f
    val riseMax = h * 1.2f
    val driftMax = w * 0.6f
    val swirlMaxX = w * 0.14f
    val swirlMaxY = h * 0.05f
    val globalPhase = p * TAU
    val invCols = 1f / cols
    val invRows = 1f / field.rows
    var grainCount = 0

    for (index in 0 until field.count) {
        val local = (p - field.startDelay[index]) / TILE_LIFESPAN
        if (local <= 0f) continue
        if (local >= 1f) continue

        val alpha = 1f - smoothstep(0.45f, 1f, local)
        if (alpha < 0.02f) continue

        val nx = (index % cols + 0.5f) * invCols
        val ny = (index / cols + 0.5f) * invRows

        val ease = local * local
        val easeSoft = local * local * (3f - 2f * local)

        val rise = riseMax * field.speed[index] * (0.15f * easeSoft + 0.85f * ease * (1f + 0.4f * local))
        val t1 = field.swirlPhase[index] + local * TAU * field.swirlFreq[index]
        val flowPhase = (nx * 2.3f + ny * 3.7f) * TAU
        val t2 = flowPhase + globalPhase
        val swirlAmp = field.swirlAmp[index]
        val meanderX = (
            sin(t2) * 0.65f +
                sin(t1) * swirlAmp +
                cos(t2 * 0.5f + local * TAU * 1.3f) * 0.4f
            ) * swirlMaxX * easeSoft
        val meanderY = (
            cos(t2 * 0.8f) * 0.5f +
                sin(t1 * 0.7f + 1.3f) * swirlAmp * 0.6f
            ) * swirlMaxY * easeSoft
        val scale = 1f - 0.4f * easeSoft

        val i = grainCount++
        field.centersX[i] = nx * w + field.dirX[index] * ease * driftMax + meanderX
        field.centersY[i] = ny * h - rise + meanderY
        field.halfWidths[i] = tileW * scale * 0.5f
        field.halfHeights[i] = tileH * scale * 0.5f
        val base = field.argb[index]
        val alphaByte = ((base ushr 24) * alpha).toInt().coerceIn(0, 255)
        field.colors[i] = (alphaByte shl 24) or (base and 0x00FFFFFF)
    }

    field.renderer.draw(
        scope = this,
        count = grainCount,
        centersX = field.centersX,
        centersY = field.centersY,
        halfWidths = field.halfWidths,
        halfHeights = field.halfHeights,
        colors = field.colors,
    )
}

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
