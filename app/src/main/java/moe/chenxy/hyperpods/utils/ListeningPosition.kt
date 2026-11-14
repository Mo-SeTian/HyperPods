package moe.chenxy.hyperpods.utils

import moe.chenxy.hyperpods.pods.NoiseControlMode
import kotlin.math.roundToInt

/** Separate listening-mode zones from the native 0..100 AUTO_ANC_STRENGTH range.
 * LibrePods android/rewrite NoiseControlSettings.kt expands the adaptive zone;
 * its Apple control writes raw strength increasing from left to right. */
object ListeningPosition {
    const val ADAPTIVE_START = .2f
    const val ADAPTIVE_END = .8f

    fun modeAt(position: Float, adaptive: Boolean): NoiseControlMode = when {
        !adaptive -> if (position < .5f) NoiseControlMode.TRANSPARENCY else NoiseControlMode.NOISE_CANCELLATION
        position < ADAPTIVE_START -> NoiseControlMode.TRANSPARENCY
        position > ADAPTIVE_END -> NoiseControlMode.NOISE_CANCELLATION
        else -> NoiseControlMode.ADAPTIVE
    }

    fun adaptiveLevel(position: Float): Float =
        1f - ((position - ADAPTIVE_START) / (ADAPTIVE_END - ADAPTIVE_START)).coerceIn(0f, 1f)

    fun fromReport(mode: NoiseControlMode?, level: Float): Float = when (mode) {
        NoiseControlMode.TRANSPARENCY -> 0f
        NoiseControlMode.NOISE_CANCELLATION -> 1f
        NoiseControlMode.ADAPTIVE -> ADAPTIVE_START + (1f - level.coerceIn(0f, 1f)) * (ADAPTIVE_END - ADAPTIVE_START)
        else -> .5f
    }

    fun levelFromWire(value: Int): Float = (100 - value.coerceIn(0, 100)) / 100f
    fun wireStrength(level: Float): Int = (100 - level.coerceIn(0f, 1f) * 100).roundToInt()
}
