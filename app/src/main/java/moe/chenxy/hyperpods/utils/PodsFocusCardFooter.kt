package moe.chenxy.hyperpods.utils

import android.view.View
import java.util.WeakHashMap

object PodsFocusCardFooter {
    private val originalAlpha = WeakHashMap<View, Float>()

    fun apply(divider: View?) {
        divider ?: return
        originalAlpha.putIfAbsent(divider, divider.alpha)
        // Alpha hides the separator without changing the stock template's
        // visibility, measurement, margins or touch-target positions.
        divider.alpha = 0f
    }

    fun restore(divider: View?) {
        divider ?: return
        originalAlpha.remove(divider)?.let { divider.alpha = it }
    }
}
