package moe.chenxy.hyperpods.utils

/** Phone coexistence geometry; the camera's physical position must not move. */
data class PodsIslandLayout(val left: Int, val right: Int, val width: Int, val x: Int) {
    fun symmetric(rtl: Boolean, contentWidth: Int = 0): PodsIslandLayout? {
        val half = minOf(left, right)
        val removed = maxOf(left, right) - half
        if (half <= 0 || half < contentWidth || removed == 0 || width <= left + right) return null
        return copy(left = half, right = half, width = width - removed,
            x = x + if (rtl) right - half else left - half)
    }
}
