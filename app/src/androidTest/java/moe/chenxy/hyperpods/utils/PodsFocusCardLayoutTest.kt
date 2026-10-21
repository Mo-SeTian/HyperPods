package moe.chenxy.hyperpods.utils

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.roundToInt

/** Nested framework fixture for layout invariance, not a Xiaomi runtime or pixel-identical template. */
@RunWith(Parameterized::class)
class PodsFocusCardLayoutTest(private val fontScale: Float, private val widthDp: Int, private val showCase: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "fontScale={0}, width={1}, case={2}")
        fun cases() = listOf(1f, 1.3f, 1.6f).flatMap { scale ->
            listOf(320, 360).flatMap { width -> listOf(false, true).map { arrayOf<Any>(scale, width, it) } }
        }
    }

    private class Card(private val context: Context, private val widthDp: Int, showCase: Boolean) {
        fun dp(value: Float) = (value * context.resources.displayMetrics.density).roundToInt()
        private fun text(value: String, size: Float) = TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            maxLines = 1
        }
        val root = FrameLayout(context)
        private val body = RelativeLayout(context)
        private val battery = LinearLayout(context).apply { id = View.generateViewId(); orientation = LinearLayout.VERTICAL }
        val caseBattery = text("充电盒：90%", 12f).apply { visibility = if (showCase) View.VISIBLE else View.GONE }
        private val icon = View(context).apply { id = View.generateViewId() }
        private val footerContainer = FrameLayout(context).apply { setPadding(0, dp(8f), 0, 0) }
        private val footer = RelativeLayout(context).apply { gravity = Gravity.CENTER }
        val line = View(context)
        private val name = LinearLayout(context)
        private val nameColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private val nameFrame = FrameLayout(context)
        val button = FrameLayout(context).apply { id = View.generateViewId() }
        val row = LinearLayout(context)
        val title = text("断开连接", 13f).apply {
            includeFontPadding = false
            isFallbackLineSpacing = false
        }

        init {
            // The full card includes a MATCH_PARENT body, top content and a padded footer area.
            root.addView(body, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { setMargins(dp(14f), dp(14f), dp(14f), dp(14f)) })
            body.addView(icon, RelativeLayout.LayoutParams(dp(48f), dp(48f)).apply { addRule(RelativeLayout.ALIGN_PARENT_END) })
            battery.addView(caseBattery)
            battery.addView(text("左：100% | 右：100%", 20f))
            body.addView(battery, RelativeLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                addRule(RelativeLayout.ALIGN_PARENT_TOP)
                addRule(RelativeLayout.CENTER_VERTICAL)
                addRule(RelativeLayout.START_OF, icon.id)
                marginEnd = dp(8f)
            })
            body.addView(footerContainer, RelativeLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                addRule(RelativeLayout.BELOW, battery.id)
            })
            footerContainer.addView(footer, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            footer.addView(line, RelativeLayout.LayoutParams(MATCH_PARENT, dp(1.6f)).apply { topMargin = dp(2f) })
            nameFrame.addView(text("AirPods Pro", 16f), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            nameColumn.addView(text("HyperPods", 12f))
            nameColumn.addView(nameFrame)
            name.addView(nameColumn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(4f) })
            footer.addView(name, RelativeLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = dp(4f)
                marginEnd = dp(8f)
                addRule(RelativeLayout.ALIGN_PARENT_START)
                addRule(RelativeLayout.START_OF, button.id)
            })
            row.addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = dp(5.5f)
                bottomMargin = dp(5.5f)
                marginStart = dp(12f)
                marginEnd = dp(12f)
            })
            // This MATCH_PARENT inside a WRAP_CONTENT button is present in mark_4.
            button.addView(row, FrameLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
            footer.addView(button, RelativeLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = dp(19f)
                addRule(RelativeLayout.ALIGN_PARENT_END)
            })
        }

        fun measure(fixedHeight: Int? = null) {
            root.requestLayout()
            root.measure(View.MeasureSpec.makeMeasureSpec(dp(widthDp.toFloat()), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(fixedHeight ?: dp(600f),
                    if (fixedHeight == null) View.MeasureSpec.AT_MOST else View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        }

        fun geometry(): List<List<Int>> {
            val result = mutableListOf<List<Int>>()
            fun visit(view: View) {
                val params = view.layoutParams
                val margin = params as? ViewGroup.MarginLayoutParams
                result.add(listOf(view.left, view.top, view.right, view.bottom, view.measuredWidth, view.measuredHeight,
                    view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom, view.visibility,
                    params?.width ?: 0, params?.height ?: 0, margin?.topMargin ?: 0, margin?.bottomMargin ?: 0,
                    margin?.marginStart ?: 0, margin?.marginEnd ?: 0))
                if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
            visit(root)
            return result
        }
    }

    private fun check(block: (Card) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val config = Configuration(context.resources.configuration).apply { fontScale = this@PodsFocusCardLayoutTest.fontScale }
            block(Card(context.createConfigurationContext(config), widthDp, showCase))
        }
    }

    @Test fun hidingTheDividerPreservesNaturalCardAndDescendantGeometry() = check { card ->
        card.measure()
        val original = card.geometry()
        PodsFocusCardFooter.apply(card.line)
        card.measure()
        assertEquals(0f, card.line.alpha, 0f)
        assertEquals(original, card.geometry())
        PodsFocusCardFooter.restore(card.line)
    }

    @Test fun hidingTheDividerPreservesFixedHeightAndNativeButtonMeasurement() = check { card ->
        card.measure()
        val allocatedHeight = card.root.measuredHeight
        card.measure(allocatedHeight)
        val original = card.geometry()
        repeat(3) { PodsFocusCardFooter.apply(card.line) }
        card.measure(allocatedHeight)
        assertEquals(original, card.geometry())
        assertEquals(MATCH_PARENT, card.row.layoutParams.height)
        assertFalse(card.title.includeFontPadding)
        assertFalse(card.title.isFallbackLineSpacing)
        assertEquals(0f, card.button.translationY, 0f)
        PodsFocusCardFooter.restore(card.line)
    }

    @Test fun recyclingAndCaseBatteryUpdatesPreserveEachNewSystemLayout() = check { card ->
        for (visible in listOf(!showCase, showCase, !showCase)) {
            card.caseBattery.visibility = if (visible) View.VISIBLE else View.GONE
            card.line.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            card.line.alpha = 0.6f
            card.measure()
            val original = card.geometry()
            PodsFocusCardFooter.apply(card.line)
            card.measure()
            assertEquals(original, card.geometry())
            PodsFocusCardFooter.restore(card.line)
            card.measure()
            assertEquals(original, card.geometry())
            assertEquals(0.6f, card.line.alpha, 0f)
        }
    }
}
