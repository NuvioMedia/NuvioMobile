package com.nuvio.app.core.ui.jelly

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

internal class JellySpring(
    var value: Double,
    private val stiffness: Double,
    private val dampingRatio: Double,
) {
    var velocity = 0.0

    fun isAtRest(target: Double): Boolean = abs(value - target) < 0.0001 && abs(velocity) < 0.0001

    fun snapTo(target: Double) {
        value = target
        velocity = 0.0
    }

    fun advance(target: Double, seconds: Double) {
        if (isAtRest(target)) {
            snapTo(target)
            return
        }
        val displacement = value - target
        val frequency = sqrt(stiffness)
        if (dampingRatio == 1.0) {
            val decay = exp(-frequency * seconds)
            val coefficient = velocity + frequency * displacement
            value = target + (displacement + coefficient * seconds) * decay
            velocity = (velocity - frequency * coefficient * seconds) * decay
        } else {
            val damping = dampingRatio * frequency
            val damped = frequency * sqrt(1 - dampingRatio * dampingRatio)
            val decay = exp(-damping * seconds)
            val cosine = cos(damped * seconds)
            val sine = sin(damped * seconds)
            val positionCoefficient = (velocity + damping * displacement) / damped
            val velocityCoefficient = (damping * velocity + stiffness * displacement) / damped
            value = target + decay * (displacement * cosine + positionCoefficient * sine)
            velocity = decay * (velocity * cosine - velocityCoefficient * sine)
        }
    }
}

internal fun jellyRubberBand(distance: Double, dimension: Double): Double {
    if (distance == 0.0 || dimension <= 0.0) return 0.0
    val damped = (1 - 1 / (abs(distance) * 0.14 / dimension + 1)) * dimension
    return if (distance < 0) -damped else damped
}

internal fun jellyPanelOffset(rawOffset: Double, width: Double): Float {
    if (width <= 0 || rawOffset == 0.0) return 0f
    val fraction = (rawOffset / width).coerceIn(-1.0, 1.0)
    val x = abs(fraction)
    var low = 0.0
    var high = 1.0
    var parameter = x
    repeat(10) {
        val bezierX = parameter * parameter * (3 * (1 - parameter) * 0.58 + parameter)
        if (bezierX < x) low = parameter else high = parameter
        parameter = (low + high) / 2
    }
    val eased = parameter * parameter * (3 * (1 - parameter) + parameter)
    return ((if (fraction < 0) -4 else 4) * eased).toFloat()
}
