package moe.chenxy.hyperpods.utils

import android.widget.TextView
import android.view.ViewGroup.LayoutParams
import android.widget.LinearLayout
import java.lang.ref.WeakReference
import java.util.WeakHashMap

object PodsFocusButtonText {
    private data class Original(val fontPadding: Boolean, val fallbackSpacing: Boolean,
        val row: WeakReference<LinearLayout>?)
    private val originals = WeakHashMap<TextView, Original>()

    fun apply(view: TextView) {
        val row = (view.parent as? LinearLayout)?.takeIf { it.layoutParams?.height == LayoutParams.MATCH_PARENT }
        originals.putIfAbsent(view, Original(view.includeFontPadding, view.isFallbackLineSpacing, row?.let(::WeakReference)))
        // mark_4 places a MATCH_PARENT row inside a WRAP_CONTENT button. Let the
        // row measure the full text instead of inheriting a stale parent height.
        row?.let {
            val params = it.layoutParams
            params.height = LayoutParams.WRAP_CONTENT
            it.layoutParams = params
        }
        // System templates use a single line. Reserve the actual font's ascent
        // and descent instead of shrinking text or fixing the whole card height.
        view.includeFontPadding = true
        view.isFallbackLineSpacing = true
    }

    fun restore(view: TextView) {
        val original = originals.remove(view) ?: return
        view.includeFontPadding = original.fontPadding
        view.isFallbackLineSpacing = original.fallbackSpacing
        original.row?.get()?.let {
            val params = it.layoutParams ?: return@let
            if (params.height == LayoutParams.WRAP_CONTENT) {
                params.height = LayoutParams.MATCH_PARENT
                it.layoutParams = params
            }
        }
    }
}
