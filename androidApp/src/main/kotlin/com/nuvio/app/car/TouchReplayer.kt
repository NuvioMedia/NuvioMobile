package com.nuvio.app.car

import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.hypot

/**
 * Turns the Car App Library's gesture callbacks (click, scroll, fling) back into finger touch events.
 * The host never reports where a drag starts or when the finger lifts, so a drag starts at [anchor]
 * (the middle of the usable area) and ends shortly after the last scroll, or right away with momentum
 * when the host reports a fling.
 */
internal class TouchReplayer(
    private val sink: (MotionEvent) -> Unit,
    private val anchor: () -> PointF,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val endDrag = Runnable { finishDrag() }
    private var downTime = 0L
    private var x = 0f
    private var y = 0f
    private var lastMoveTime = 0L

    // Direction of the latest finger movement, for fling momentum.
    private var lastDx = 0f
    private var lastDy = 0f

    fun click(cx: Float, cy: Float) {
        if (downTime != 0L) finishDrag()
        val t = SystemClock.uptimeMillis()
        send(t, t, MotionEvent.ACTION_DOWN, cx, cy)
        send(t, t + TAP_MS, MotionEvent.ACTION_UP, cx, cy)
    }

    /** [distanceX]/[distanceY] follow GestureDetector: previous minus current, so the finger moved by minus that. */
    fun scroll(distanceX: Float, distanceY: Float) {
        val t = SystemClock.uptimeMillis()
        if (downTime == 0L) {
            val start = anchor()
            downTime = t
            x = start.x
            y = start.y
            send(t, t, MotionEvent.ACTION_DOWN, x, y)
        }
        x -= distanceX
        y -= distanceY
        if (distanceX != 0f || distanceY != 0f) {
            lastDx = -distanceX
            lastDy = -distanceY
        }
        lastMoveTime = maxOf(t, lastMoveTime + 1)
        send(downTime, lastMoveTime, MotionEvent.ACTION_MOVE, x, y)
        handler.removeCallbacks(endDrag)
        handler.postDelayed(endDrag, DRAG_END_MS)
    }

    /**
     * Ends the current drag with momentum. Only the speed comes from the host; the direction comes from the
     * drag itself, so a host with the opposite sign convention can't fling the list backwards.
     */
    fun fling(velocityX: Float, velocityY: Float) {
        if (downTime == 0L) return
        handler.removeCallbacks(endDrag)
        val speed = hypot(velocityX, velocityY).coerceAtMost(MAX_FLING_PX_S)
        val len = hypot(lastDx, lastDy)
        if (speed < 50f || len < 0.5f) {
            finishDrag()
            return
        }
        val ux = lastDx / len
        val uy = lastDy / len
        var t = maxOf(SystemClock.uptimeMillis(), lastMoveTime + 1)
        val step = speed * FLING_STEP_MS / 1000f
        repeat(FLING_STEPS) {
            t += FLING_STEP_MS
            x += ux * step
            y += uy * step
            send(downTime, t, MotionEvent.ACTION_MOVE, x, y)
        }
        send(downTime, t, MotionEvent.ACTION_UP, x, y)
        downTime = 0L
    }

    fun cancel() {
        handler.removeCallbacks(endDrag)
        if (downTime == 0L) return
        send(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, x, y)
        downTime = 0L
    }

    private fun finishDrag() {
        handler.removeCallbacks(endDrag)
        if (downTime == 0L) return
        send(downTime, maxOf(SystemClock.uptimeMillis(), lastMoveTime + 1), MotionEvent.ACTION_UP, x, y)
        downTime = 0L
    }

    private fun send(down: Long, time: Long, action: Int, px: Float, py: Float) {
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = px
            y = py
            pressure = 1f
            size = 1f
        })
        val event = MotionEvent.obtain(
            down, time, action, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            sink(event)
        } finally {
            event.recycle()
        }
    }

    private companion object {
        const val TAP_MS = 40L
        const val DRAG_END_MS = 120L
        const val FLING_STEPS = 3
        const val FLING_STEP_MS = 8L
        const val MAX_FLING_PX_S = 9000f
    }
}
