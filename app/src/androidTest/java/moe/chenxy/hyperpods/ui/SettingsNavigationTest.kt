package moe.chenxy.hyperpods.ui

import androidx.activity.BackEventCompat
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import moe.chenxy.hyperpods.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Verify the redesigned hierarchy with and without system animation. */
@OptIn(ExperimentalTestApi::class)
@RunWith(Parameterized::class)
class SettingsNavigationTest(private val durationScale: Float) {
    companion object { @JvmStatic @Parameterized.Parameters(name = "motionScale={0}") fun scales() = listOf(arrayOf(1f), arrayOf(0f)) }
    @get:Rule val compose = createAndroidComposeRule<DashboardPreviewActivity>(effectContext = object : MotionDurationScale {
        override val scaleFactor = durationScale
    })
    private fun text(id: Int) = compose.activity.getString(id)
    private fun scrollPosition() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun open(id: Int) { compose.revealSetting(text(id)).performClick(); compose.waitForIdle() }
    private fun back() { compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle() }
    @Test fun returningFromMoreAndEqualizerPreservesHomeScroll() {
        for (id in listOf(R.string.fidelity_more, R.string.eq_title)) {
            compose.revealSetting(text(id)); val before = scrollPosition()
            open(id); back(); assertEquals(before, scrollPosition(), .001f)
            compose.onNodeWithText(text(id)).assertIsDisplayed()
        }
    }
    @Test fun audioEqualizerReturnsToAudioAndMoreReturnsHome() {
        open(R.string.fidelity_more); open(R.string.redesign_audio); open(R.string.eq_title)
        back(); compose.onNodeWithText(text(R.string.conversation_pause_media_title)).assertExists()
        back(); compose.revealSetting(text(R.string.redesign_audio)).assertIsDisplayed()
        back(); compose.revealSetting(text(R.string.eq_title)).assertIsDisplayed()
    }
    @Test fun gestureTestReturnsToGesturesAndThenMore() {
        open(R.string.fidelity_more); open(R.string.head_gestures_title); open(R.string.head_test_title)
        back(); compose.revealSetting(text(R.string.head_test_title)).assertIsDisplayed()
        back(); compose.revealSetting(text(R.string.redesign_press)).assertIsDisplayed()
        back(); compose.revealSetting(text(R.string.eq_title)).assertIsDisplayed()
    }
    @Test fun moreScrollSurvivesRecreation() {
        open(R.string.fidelity_more); compose.revealSetting(text(R.string.microphone))
        val before = scrollPosition()
        compose.activityRule.scenario.recreate(); compose.waitForIdle()
        assertEquals(before, scrollPosition(), .001f)
        back(); open(R.string.fidelity_more); assertEquals(before, scrollPosition(), .001f)
    }
    @Test fun diagnosticLogsReturnToDiagnosticsInAbout() {
        compose.onNodeWithText(text(R.string.dashboard_about)).performClick(); compose.waitForIdle()
        open(R.string.diagnostics_title); open(R.string.diagnostics_logs)
        compose.activityRule.scenario.recreate(); compose.waitForIdle()
        back(); compose.revealSetting(text(R.string.diagnostics_logs)).assertIsDisplayed()
        back(); compose.revealSetting(text(R.string.dashboard_device_info)).assertIsDisplayed()
    }
    @Test fun rapidBackInterruptsEntranceWithoutLeavingOutgoingActions() {
        compose.revealSetting(text(R.string.fidelity_more)); val before = scrollPosition()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(text(R.string.fidelity_more)).performClick(); compose.mainClock.advanceTimeBy(64)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.autoAdvance = true; compose.waitForIdle()
        assertEquals(before, scrollPosition(), .001f)
        compose.onNodeWithText(text(R.string.battery_reminder_title)).assertDoesNotExist()
    }
    @Test fun cancelledSystemBackKeepsTheCurrentPage() {
        open(R.string.fidelity_more)
        compose.runOnIdle {
            val dispatcher = compose.activity.onBackPressedDispatcher
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(80f, 100f, .3f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackCancelled()
        }
        compose.revealSetting(text(R.string.redesign_press)).assertIsDisplayed()
    }
    @Test fun entranceSettlesAndRespectsSystemMotionScale() {
        compose.revealSetting(text(R.string.fidelity_more)); compose.mainClock.autoAdvance = false
        compose.onNodeWithText(text(R.string.fidelity_more)).performClick(); compose.mainClock.advanceTimeBy(64)
        val page = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, text(R.string.fidelity_more)))
        if (durationScale == 0f) assertEquals(0f, page.fetchSemanticsNode().boundsInRoot.left, .01f)
        else assertTrue(page.fetchSemanticsNode().boundsInRoot.left > 0f)
        compose.mainClock.autoAdvance = true; compose.waitForIdle()
        assertEquals(0f, page.fetchSemanticsNode().boundsInRoot.left, .01f)
    }

    @Test fun deeperRoutesUseTheSameForwardAndBackDirection() {
        fun animate(title: Int, forward: Boolean, action: () -> Unit) {
            compose.mainClock.autoAdvance = false
            action()
            compose.mainClock.advanceTimeBy(64)
            val bounds = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, text(title)))
                .fetchSemanticsNode().boundsInRoot
            val width = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
            if (durationScale == 0f) assertEquals(0f, bounds.left, .01f)
            else assertTrue("Incorrect transition direction for ${text(title)}: $bounds",
                if (forward) bounds.left > 0f else bounds.right < width)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
        }
        open(R.string.fidelity_more); open(R.string.head_gestures_title)
        compose.revealSetting(text(R.string.head_test_title))
        animate(R.string.head_test_title, true) { compose.onNodeWithText(text(R.string.head_test_title)).performClick() }
        animate(R.string.head_gestures_title, false) { compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() } }
        back(); open(R.string.redesign_audio)
        compose.revealSetting(text(R.string.eq_title))
        animate(R.string.eq_title, true) { compose.onNodeWithText(text(R.string.eq_title)).performClick() }
        animate(R.string.redesign_audio, false) { compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() } }
    }
}
