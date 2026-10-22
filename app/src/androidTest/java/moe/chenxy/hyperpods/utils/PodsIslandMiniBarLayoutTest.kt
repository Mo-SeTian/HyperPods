package moe.chenxy.hyperpods.utils

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Framework visibility contract, not execution of the Xiaomi island plugin. */
class PodsIslandMiniBarLayoutTest {
    @Test fun hidingTheOverlayPreservesItsBoundsAndAllowsNativeReuse() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val root = FrameLayout(instrumentation.targetContext)
            val bar = View(root.context)
            root.addView(bar, FrameLayout.LayoutParams(60, 4, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = 8
            })
            fun measure() {
                root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            }
            fun bounds() = listOf(root.measuredWidth, root.measuredHeight,
                bar.left, bar.top, bar.right, bar.bottom, bar.measuredWidth, bar.measuredHeight)
            measure()
            val original = bounds()
            PodsIslandMiniBar.hide(bar, isHyperPods = true)
            measure()
            assertEquals(View.INVISIBLE, bar.visibility)
            assertEquals(original, bounds())

            // Match updateMiniBar's native assignment before a different card is shown.
            bar.visibility = View.VISIBLE
            PodsIslandMiniBar.hide(bar, isHyperPods = false)
            measure()
            assertEquals(View.VISIBLE, bar.visibility)
            assertEquals(original, bounds())

            bar.visibility = View.GONE
            PodsIslandMiniBar.hide(bar, isHyperPods = true)
            assertEquals(View.GONE, bar.visibility)
        }
    }
}
