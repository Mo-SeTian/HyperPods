package moe.chenxy.hyperpods.utils

import android.view.View
import android.widget.LinearLayout
import java.util.WeakHashMap

object PodsFocusCardSpacing {
    private val originalMargins = WeakHashMap<View, Int>()

    fun apply(view: View, isHyperPods: Boolean, content: String?, gap: Int) {
        if (!isHyperPods || !content.isNullOrEmpty()) {
            restore(view)
            return
        }
        val params = view.layoutParams as? LinearLayout.LayoutParams ?: return
        val original = originalMargins[view] ?: params.bottomMargin
        // OS4 places the divider below the text, not the fixed-size image. Its
        // built-in margin flag also fails to reset when the content row returns.
        val margin = maxOf(original, gap)
        if (params.bottomMargin != margin) {
            originalMargins.putIfAbsent(view, original)
            params.bottomMargin = margin
            view.layoutParams = params
        }
    }

    fun restore(view: View) {
        val original = originalMargins.remove(view) ?: return
        val params = view.layoutParams as? LinearLayout.LayoutParams ?: return
        if (params.bottomMargin != original) {
            params.bottomMargin = original
            view.layoutParams = params
        }
    }
}
