package dev.manik.xraymirror.pose

import kotlin.math.PI
import kotlin.math.abs

/**
 * One Euro filter (Casiez et al. 2012): heavy smoothing when a point is still (kills jitter),
 * light smoothing when it moves fast (kills lag). Values here are in screen pixels.
 */
class OneEuro(private val minCutoff: Float = 1.2f, private val beta: Float = 0.004f, private val dCutoff: Float = 1f) {
    private var x = Float.NaN
    private var dx = 0f

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    fun filter(value: Float, dt: Float): Float {
        if (x.isNaN() || dt <= 0f) { x = value; dx = 0f; return value }
        val d = (value - x) / dt
        dx += alpha(dCutoff, dt) * (d - dx)
        val cutoff = minCutoff + beta * abs(dx)
        x += alpha(cutoff, dt) * (value - x)
        return x
    }

    fun reset() { x = Float.NaN; dx = 0f }
}
