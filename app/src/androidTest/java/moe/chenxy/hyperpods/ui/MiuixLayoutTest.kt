package moe.chenxy.hyperpods.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import moe.chenxy.hyperpods.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Checks the production layout with synthetic data only, never a Bluetooth device. */
class MiuixLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<DashboardPreviewActivity>()
    private fun text(id: Int) = compose.activity.getString(id)

    @Test fun fourModesStayOnOneRowWithIndependentEarAndCaseReadings() {
        compose.runOnUiThread { compose.activity.intent.putExtra("case_connected", true) }
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        for (value in listOf("92%", "87%", "73%")) compose.onNodeWithText(value).assertIsDisplayed()
        val bounds = listOf(R.string.off, R.string.noise_cancellation_title,
            R.string.dashboard_transparency, R.string.adaptive_title).map {
            compose.onNodeWithText(text(it)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        bounds.forEach { assertEquals(bounds.first().top, it.top, 1f) }
        for (index in 1..3) org.junit.Assert.assertTrue(bounds[index].left >= bounds[index - 1].right - .5f)
        compose.onNodeWithText(text(R.string.dashboard_more_summary)).assertIsDisplayed()
    }

    @Test fun advancedControlsRemainAvailableWithoutCrowdingTheHomePage() {
        compose.onNodeWithText(text(R.string.head_gestures_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.sleep_detection_title)).assertDoesNotExist()
        compose.revealSetting(text(R.string.dashboard_more)).performClick()
        compose.waitForIdle()
        compose.revealSetting(hasContentDescription(text(R.string.head_gestures_title))).assertIsEnabled()
        compose.revealSetting(hasContentDescription(text(R.string.sleep_detection_title))).assertIsEnabled()
        compose.onNodeWithText(text(R.string.head_test_title)).assertExists()
    }

    @Test fun theWholePreferenceRowIsOneAccessibleSwitch() {
        val row = compose.revealSetting(hasContentDescription(text(R.string.personlized_volume_title)))
        row.assertHasClickAction().assertIsOff().performClick().assertIsOn()
        row.performClick().assertIsOff()
    }

    @Test fun distantMoreControlsAreLazyAndStillReachable() {
        compose.revealSetting(text(R.string.dashboard_more)).performClick()
        compose.onNodeWithText(text(R.string.microphone)).assertDoesNotExist()
        compose.revealSetting(text(R.string.microphone)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.head_gestures_title)).assertDoesNotExist()
        compose.revealSetting(hasContentDescription(text(R.string.head_gestures_title))).assertIsEnabled()
    }

    @Test fun renameCanBeCancelledWithoutChangingTheDeviceName() {
        compose.onNodeWithContentDescription(text(R.string.rename_title)).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Not saved")
        compose.onNodeWithContentDescription(compose.activity.getString(android.R.string.cancel)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("AirPods Pro").assertIsDisplayed()
        compose.onNodeWithText("Not saved").assertDoesNotExist()
    }
}
