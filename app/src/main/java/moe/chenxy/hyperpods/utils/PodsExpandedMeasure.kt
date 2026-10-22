package moe.chenxy.hyperpods.utils

import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import java.util.WeakHashMap

object PodsExpandedMeasure {
    private val previousHeights = WeakHashMap<View, Int>()

    fun prepare(view: View?, isHyperPods: Boolean) {
        if (!isHyperPods || view == null) return
        val params = view.layoutParams ?: return
        if (params.height <= 0) return
        previousHeights.putIfAbsent(view, params.height)
        params.height = WRAP_CONTENT
        view.layoutParams = params
    }

    fun finish(view: View?) {
        val previous = previousHeights.remove(view) ?: return
        val params = view?.layoutParams ?: return
        // The native updateExpandedSize normally commits the measured height to
        // real and animation views. Restore only if that update did not happen.
        if (params.height == WRAP_CONTENT) {
            params.height = previous
            view.layoutParams = params
        }
    }
}
