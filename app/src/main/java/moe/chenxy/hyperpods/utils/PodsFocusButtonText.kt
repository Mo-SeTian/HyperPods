package moe.chenxy.hyperpods.utils

import android.widget.TextView
import java.util.WeakHashMap

object PodsFocusButtonText {
    private data class Original(val fontPadding: Boolean, val fallbackSpacing: Boolean)
    private val originals = WeakHashMap<TextView, Original>()

    fun apply(view: TextView) {
        originals.putIfAbsent(view, Original(view.includeFontPadding, view.isFallbackLineSpacing))
        // System templates use a single line. Reserve the actual font's ascent
        // and descent instead of shrinking text or fixing the whole card height.
        view.includeFontPadding = true
        view.isFallbackLineSpacing = true
    }

    fun restore(view: TextView) {
        val original = originals.remove(view) ?: return
        view.includeFontPadding = original.fontPadding
        view.isFallbackLineSpacing = original.fallbackSpacing
    }
}
