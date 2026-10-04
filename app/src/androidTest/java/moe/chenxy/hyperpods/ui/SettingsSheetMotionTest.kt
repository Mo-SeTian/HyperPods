package moe.chenxy.hyperpods.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import moe.chenxy.hyperpods.ui.components.CustomSuperBottomSheet
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Scaffold

/** Exercises the production sheet with real pointer events and both motion scales. */
@OptIn(ExperimentalTestApi::class)
@RunWith(Parameterized::class)
class SettingsSheetMotionTest(private val durationScale: Float) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "motionScale={0}")
        fun scales() = listOf(arrayOf(1f), arrayOf(0f))
    }

    @get:Rule val compose = createAndroidComposeRule<DashboardPreviewActivity>(effectContext = object : MotionDurationScale {
        override val scaleFactor = durationScale
    })
    private val shown = mutableStateOf(true)

    private fun open(allowDismiss: Boolean = true) {
        compose.runOnUiThread {
            compose.activity.setContent {
                AppTheme(1) {
                    // Miuix renders DialogLayout through Scaffold's popup host,
                    // just like the production settings activity.
                    Scaffold { _ ->
                        Text("Open", Modifier.clickable { shown.value = true })
                        CustomSuperBottomSheet(show = shown, modifier = Modifier.testTag("sheet"),
                            title = "Test sheet", allowDismiss = allowDismiss,
                            onDismissRequest = { shown.value = false }) {
                            Box(Modifier.height(240.dp)) { Text("Sheet content") }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun drag(delta: Float, duration: Long, cancelGesture: Boolean = false) {
        compose.onNodeWithTag("sheet").performTouchInput {
            val start = Offset(center.x, 12f)
            down(start)
            repeat(10) { step ->
                moveTo(start + Offset(0f, delta * (step + 1) / 10), delayMillis = duration / 10)
            }
            if (cancelGesture) cancel() else up()
        }
        compose.waitForIdle()
    }

    @Test fun shortFastDownwardFlickDismissesInsteadOfExpanding() {
        open()
        drag(120f, 100)
        compose.runOnIdle { assertFalse(shown.value) }
        compose.onNodeWithTag("sheet").assertDoesNotExist()
    }

    @Test fun shortSlowDragAndCancelledDragReturnToTheirOriginalPosition() {
        open()
        val top = compose.onNodeWithTag("sheet").fetchSemanticsNode().boundsInRoot.top
        drag(80f, 1000)
        compose.runOnIdle { assertTrue(shown.value) }
        assertEquals(top, compose.onNodeWithTag("sheet").fetchSemanticsNode().boundsInRoot.top, 1f)
        drag(120f, 100, cancelGesture = true)
        compose.runOnIdle { assertTrue(shown.value) }
        assertEquals(top, compose.onNodeWithTag("sheet").fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    @Test fun upwardFlickCannotDismissTheSheet() {
        open()
        drag(-120f, 100)
        compose.runOnIdle { assertTrue(shown.value) }
        compose.onNodeWithText("Sheet content").assertIsDisplayed()
    }

    @Test fun disabledDismissalReboundsEvenAfterALargeDownwardDrag() {
        open(allowDismiss = false)
        val top = compose.onNodeWithTag("sheet").fetchSemanticsNode().boundsInRoot.top
        drag(600f, 200)
        compose.runOnIdle { assertTrue(shown.value) }
        assertEquals(top, compose.onNodeWithTag("sheet").fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    @Test fun exitRetainsContentUntilAnimationFinishesAndReopenHasNoResidualDrag() {
        open()
        val top = compose.onNodeWithTag("sheet").fetchSemanticsNode().boundsInRoot.top
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { shown.value = false }
        compose.mainClock.advanceTimeBy(64)
        if (durationScale == 0f) compose.onNodeWithTag("sheet").assertDoesNotExist()
        else compose.onNodeWithTag("sheet").assertExists()
        // Reverse the exit before it finishes, then settle.
        compose.runOnUiThread { shown.value = true }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(top, compose.onNodeWithTag("sheet").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.runOnUiThread { shown.value = false }
        compose.waitForIdle()
        compose.onNodeWithTag("sheet").assertDoesNotExist()
    }
}
