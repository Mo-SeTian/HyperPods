package moe.chenxy.hyperpods.utils

import android.view.View

object PodsIslandMiniBar {
    fun hide(view: View?, isHyperPods: Boolean) {
        if (isHyperPods && view?.visibility == View.VISIBLE) {
            // Preserve the native space and freeform gesture state; hide only its hint.
            view.visibility = View.INVISIBLE
        }
    }
}
