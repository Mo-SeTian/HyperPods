package moe.chenxy.hyperpods.utils

import android.view.View
import android.view.ViewGroup
import java.util.WeakHashMap

object PodsFocusCardFooter {
    private val visibility = WeakHashMap<View, Int>()
    private val topMargins = WeakHashMap<View, Int>()

    fun apply(divider: View?, name: View?, button: View?, shift: Int) {
        divider?.takeIf { it.visibility != View.GONE }?.let {
            visibility.putIfAbsent(it, it.visibility)
            it.visibility = View.GONE
        }
        for (view in listOfNotNull(name, button)) {
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
            val original = topMargins[view] ?: params.topMargin
            val compact = maxOf(0, original - shift)
            if (params.topMargin != compact) {
                topMargins.putIfAbsent(view, original)
                params.topMargin = compact
                view.layoutParams = params
            }
        }
    }

    fun restore(divider: View?, name: View?, button: View?) {
        divider?.let { view -> visibility.remove(view)?.let { view.visibility = it } }
        for (view in listOfNotNull(name, button)) {
            val original = topMargins.remove(view) ?: continue
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
            params.topMargin = original
            view.layoutParams = params
        }
    }
}
