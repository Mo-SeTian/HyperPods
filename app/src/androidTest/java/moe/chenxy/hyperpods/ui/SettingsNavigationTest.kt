package moe.chenxy.hyperpods.ui

import androidx.activity.BackEventCompat
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import moe.chenxy.hyperpods.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Uses the real settings page with synthetic earbuds; never writes Bluetooth settings. */
@OptIn(ExperimentalTestApi::class)
@RunWith(Parameterized::class)
class SettingsNavigationTest(private val durationScale: Float) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "motionScale={0}")
        fun scales() = listOf(arrayOf(1f), arrayOf(0f))
    }

    @get:Rule
    val compose = createAndroidComposeRule<DashboardPreviewActivity>(effectContext = object : MotionDurationScale {
        override val scaleFactor = durationScale
    })

    private fun text(id: Int) = compose.activity.getString(id)

    private val verticalList = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    private fun scrollPosition(): Float = compose.onNode(verticalList).fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun reveal(id: Int) {
        compose.onNodeWithText(text(id)).performScrollTo()
        compose.waitForIdle()
    }

    private fun open(id: Int) {
        reveal(id)
        compose.onNodeWithText(text(id)).performClick()
        compose.waitForIdle()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, text(id))).assertExists()
    }

    private fun back() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun returningFromEveryDetailRestoresHomeScroll() {
        listOf(R.string.dashboard_more, R.string.dashboard_device_info, R.string.diagnostics_title).forEach { id ->
            reveal(id)
            val before = scrollPosition()
            compose.onNodeWithText(text(id)).performClick()
            compose.waitForIdle()
            compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, text(id))).assertExists()
            back()
            assertEquals("Home scroll changed after returning", before, scrollPosition(), 0.000001f)
            compose.onNodeWithText(text(id)).assertIsDisplayed()
        }
    }

    @Test
    fun detailScrollIsIndependentAndSurvivesRecreation() {
        open(R.string.dashboard_more)
        // Use a non-boundary offset: on recreation, Android delivers window
        // insets after the first measure and may clamp a bottom-most offset.
        compose.onNodeWithText(text(R.string.audio_title)).performScrollTo()
        val before = scrollPosition()
        assertTrue(before > 0f)
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertEquals(before, scrollPosition(), 0.000001f)
        back()
        open(R.string.dashboard_device_info)
        assertEquals("Different detail pages must not share an offset", 0f, scrollPosition(), 0.000001f)
        back()
        open(R.string.dashboard_more)
        assertEquals("More controls lost its scroll", before, scrollPosition(), 0.000001f)
    }

    @Test
    fun backInterruptsTheEntranceWithoutLeavingAnOutgoingPage() {
        reveal(R.string.dashboard_more)
        val before = scrollPosition()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(text(R.string.dashboard_more)).performClick()
        compose.mainClock.advanceTimeBy(64)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.dashboard_more)).assertIsDisplayed()
        assertEquals(before, scrollPosition(), 0.000001f)
        compose.onNodeWithText(text(R.string.battery_reminder_title)).assertDoesNotExist()
    }

    @Test
    fun visibleBackButtonRestoresScrollOnRepeatedVisits() {
        repeat(3) {
            reveal(R.string.dashboard_device_info)
            val before = scrollPosition()
            compose.onNodeWithText(text(R.string.dashboard_device_info)).performClick()
            compose.onAllNodesWithContentDescription(text(R.string.dashboard_back)).onLast().performClick()
            assertEquals(before, scrollPosition(), 0.000001f)
        }
    }

    @Test
    fun rapidTabReversalsSettleOnTheLastTapAndKeepScroll() {
        reveal(R.string.dashboard_more)
        val before = scrollPosition()
        compose.mainClock.autoAdvance = false
        repeat(3) {
            compose.onNodeWithText(text(R.string.dashboard_about)).performClick()
            compose.mainClock.advanceTimeBy(64)
            compose.onNodeWithText(text(R.string.dashboard_headphones)).performClick()
            compose.mainClock.advanceTimeBy(64)
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.dashboard_headphones)).assertIsSelected()
        assertEquals(before, scrollPosition(), 0.000001f)
    }

    @Test
    fun cancelledSystemBackKeepsTheCurrentPageAndScroll() {
        open(R.string.dashboard_more)
        compose.onNodeWithText(text(R.string.audio_title)).performScrollTo()
        val before = scrollPosition()
        compose.runOnIdle {
            val dispatcher = compose.activity.onBackPressedDispatcher
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(80f, 100f, 0.3f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackCancelled()
        }
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, text(R.string.dashboard_more))).assertExists()
        assertEquals(before, scrollPosition(), 0.000001f)
        back()
        compose.onNodeWithText(text(R.string.dashboard_more)).assertIsDisplayed()
    }

    @Test
    fun entranceMovesBrieflyOrSettlesImmediatelyWhenMotionIsDisabled() {
        reveal(R.string.dashboard_more)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(text(R.string.dashboard_more)).performClick()
        compose.mainClock.advanceTimeBy(64)
        val page = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, text(R.string.dashboard_more)))
        val left = page.fetchSemanticsNode().boundsInRoot.left
        if (durationScale == 0f) assertEquals(0f, left, 0.01f)
        else assertTrue("The entrance should be moving at 64 ms", left > 0f)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals("The settled page must have no residual translation", 0f, page.fetchSemanticsNode().boundsInRoot.left, 0.01f)
    }
}
