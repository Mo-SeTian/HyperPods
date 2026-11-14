package moe.chenxy.hyperpods.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.pods.NoiseControlMode
import moe.chenxy.hyperpods.ui.components.Fidelity
import moe.chenxy.hyperpods.ui.components.ListeningSlider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Exercise the production control; reports and callbacks remain synthetic. */
@OptIn(ExperimentalTestApi::class)
@RunWith(Parameterized::class)
class ListeningMotionUiTest(private val theme: Int, private val motionScale: Float) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "theme={0},motion={1}")
        fun configurations() = listOf(arrayOf(1, 1f), arrayOf(2, 1f), arrayOf(1, 0f), arrayOf(2, 0f))
    }
    @get:Rule val compose = createAndroidComposeRule<DashboardPreviewActivity>(effectContext = object : MotionDurationScale {
        override val scaleFactor = motionScale
    })
    private val mode = mutableStateOf(NoiseControlMode.NOISE_CANCELLATION)
    private val level = mutableStateOf(.5f)
    private val enabled = mutableStateOf(true)
    private val feedback = mutableStateOf<String?>(null)
    private val writes = mutableListOf<Float>()
    private fun slider() = compose.onNodeWithContentDescription(compose.activity.getString(R.string.redesign_slider))
    private fun thumb() = compose.onNodeWithTag("listening-thumb", useUnmergedTree = true)
    private fun render() {
        compose.runOnUiThread { compose.activity.setContent { AppTheme(theme) {
            Column(Modifier.fillMaxWidth().background(Fidelity.surface()).testTag("motion-preview")) {
                ListeningSlider(mode.value, level.value, true, enabled.value, feedback.value,
                    { writes.add(it) }, available = true)
            }
        } } }
        compose.waitForIdle()
    }
    private fun point(value: Float, width: Int, y: Float): Offset {
        val inset = 41 * compose.activity.resources.displayMetrics.density
        return Offset(inset + (width - 2 * inset) * value, y)
    }
    private fun blueWidth(): Int {
        val pixels = thumb().captureToImage().toPixelMap()
        val columns = (0 until pixels.width).filter { x -> (0 until pixels.height).any { y ->
            val color = pixels[x, y]
            color.red < .1f && color.green in .3f.. .65f && color.blue > .9f
        } }
        assertTrue("Thumb must render in blue", columns.isNotEmpty())
        return columns.last() - columns.first() + 1
    }

    @Test fun thumbContractsDuringDragThenExpandsAtTheSamePosition() {
        render()
        val restingWidth = blueWidth()
        compose.mainClock.autoAdvance = false
        slider().performTouchInput { down(point(1f, width, center.y)); moveTo(point(.65f, width, center.y)) }
        compose.mainClock.advanceTimeBy(160)
        assertTrue("Dragging should contract the thumb", blueWidth() < restingWidth * .9f)
        val before = thumb().fetchSemanticsNode().boundsInRoot.center.x
        slider().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(32)
        assertEquals("Release must not rewind", before, thumb().fetchSemanticsNode().boundsInRoot.center.x, 1f)
        compose.mainClock.advanceTimeBy(550)
        assertEquals(restingWidth.toFloat(), blueWidth().toFloat(), 2f)
        assertEquals(before, thumb().fetchSemanticsNode().boundsInRoot.center.x, 1f)
        assertEquals(.65f, writes.last(), .005f)
        compose.mainClock.autoAdvance = true
    }

    @Test fun lateReportsNeverMoveTheReleasedThumbBackThroughTheOldPosition() {
        render()
        slider().performTouchInput { swipe(point(1f, width, center.y), point(.65f, width, center.y), 300) }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(300)
        val target = thumb().fetchSemanticsNode().boundsInRoot.center.x
        compose.runOnIdle { enabled.value = false; feedback.value = "sent" }
        compose.mainClock.advanceTimeBy(32)
        assertEquals(target, thumb().fetchSemanticsNode().boundsInRoot.center.x, 1f)
        compose.runOnIdle { mode.value = NoiseControlMode.ADAPTIVE }
        compose.mainClock.advanceTimeBy(160)
        assertEquals(target, thumb().fetchSemanticsNode().boundsInRoot.center.x, 1f)
        compose.runOnIdle { level.value = .25f; enabled.value = true; feedback.value = "confirmed" }
        compose.mainClock.advanceTimeBy(300)
        assertEquals(target, thumb().fetchSemanticsNode().boundsInRoot.center.x, 1f)
        assertEquals(NoiseControlMode.ADAPTIVE, mode.value)
        compose.mainClock.autoAdvance = true
    }
}
