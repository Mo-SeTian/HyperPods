package moe.chenxy.hyperpods.utils

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import android.view.View
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

/** Native measurement of the supplied plugin's footer hierarchy, not a Xiaomi runtime test. */
@RunWith(Parameterized::class)
class PodsFocusCardLayoutTest(private val fontScale: Float) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "fontScale={0}")
        fun scales() = listOf(arrayOf(1f), arrayOf(1.3f), arrayOf(1.6f))
    }

    private class Footer(private val context: Context) {
        fun dp(value: Float) = (value * context.resources.displayMetrics.density).roundToInt()
        val root = RelativeLayout(context)
        val line = View(context)
        val name = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val button = FrameLayout(context).apply { id = View.generateViewId() }
        val row = LinearLayout(context)
        val title = TextView(context).apply {
            text = "断开连接"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            includeFontPadding = false
            isFallbackLineSpacing = false
        }

        init {
            root.addView(line, RelativeLayout.LayoutParams(MATCH_PARENT, dp(1.6f)).apply { topMargin = dp(2f) })
            for ((text, size) in listOf("HyperPods" to 13f, "AirPods Pro" to 16f)) {
                name.addView(TextView(context).apply {
                    this.text = text
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
                })
            }
            root.addView(name, RelativeLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = dp(4f)
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
            root.addView(button, RelativeLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = dp(19f)
                addRule(RelativeLayout.ALIGN_PARENT_END)
            })
        }

        fun measure() {
            root.measure(View.MeasureSpec.makeMeasureSpec(dp(360f), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        }

        fun compact() {
            PodsFocusCardFooter.apply(line, name, button, dp(8f))
            PodsFocusButtonText.apply(title)
            measure()
        }
    }

    private fun check(block: (Footer) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val config = Configuration(context.resources.configuration).apply { fontScale = this@PodsFocusCardLayoutTest.fontScale }
            block(Footer(context.createConfigurationContext(config)))
        }
    }

    @Test fun footerMovesUpAndTheDividerNoLongerOccupiesSpace() = check { footer ->
        footer.measure()
        val oldTop = footer.button.top
        footer.compact()
        assertEquals(View.GONE, footer.line.visibility)
        assertTrue(footer.button.top < oldTop)
        assertEquals(0f, footer.button.translationY, 0f)
        assertEquals(0f, footer.name.translationY, 0f)
    }

    @Test fun fullFontHeightFitsInsideTheButtonAtEveryScale() = check { footer ->
        footer.compact()
        val metrics = footer.title.paint.fontMetricsInt
        assertEquals(WRAP_CONTENT, footer.row.layoutParams.height)
        assertTrue(footer.title.measuredHeight >= metrics.bottom - metrics.top)
        val params = footer.title.layoutParams as LinearLayout.LayoutParams
        assertTrue(footer.button.measuredHeight >= footer.title.measuredHeight + params.topMargin + params.bottomMargin)
        assertEquals(1, footer.title.layout.lineCount)
    }

    @Test fun recyclingRestoresTheOriginalSystemTemplate() = check { footer ->
        repeat(3) { footer.compact() }
        PodsFocusCardFooter.restore(footer.line, footer.name, footer.button)
        PodsFocusButtonText.restore(footer.title)
        assertEquals(View.VISIBLE, footer.line.visibility)
        assertEquals(footer.dp(4f), (footer.name.layoutParams as RelativeLayout.LayoutParams).topMargin)
        assertEquals(footer.dp(19f), (footer.button.layoutParams as RelativeLayout.LayoutParams).topMargin)
        assertEquals(MATCH_PARENT, footer.row.layoutParams.height)
        assertFalse(footer.title.includeFontPadding)
        assertFalse(footer.title.isFallbackLineSpacing)
    }
}
