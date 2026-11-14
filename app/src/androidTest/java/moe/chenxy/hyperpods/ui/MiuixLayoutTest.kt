package moe.chenxy.hyperpods.ui

import androidx.compose.ui.test.*
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import moe.chenxy.hyperpods.ui.components.DeviceHero
import moe.chenxy.hyperpods.utils.AirPodsModels
import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.PodBatteryParams
import moe.chenxy.hyperpods.utils.data.EarDetectionParams
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import moe.chenxy.hyperpods.R
import org.junit.Rule
import org.junit.Test

/** Production pages with synthetic reports; no Bluetooth writes. */
class MiuixLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<DashboardPreviewActivity>()
    private fun text(id: Int) = compose.activity.getString(id)
    private fun open(id: Int) { compose.revealSetting(text(id)).performClick(); compose.waitForIdle() }

    @Test fun homeShowsIndependentReadingsAndThreePositionSliderWithoutOff() {
        compose.runOnUiThread { compose.activity.intent.putExtra("case_connected", true) }
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        for (value in listOf("92%", "87%")) compose.onNodeWithText(value).assertIsDisplayed()
        compose.onNodeWithText("73%").assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.redesign_slider)).assertIsEnabled()
        compose.onNodeWithText(text(R.string.off)).assertDoesNotExist()
        compose.revealSetting(hasContentDescription(text(R.string.fidelity_detection))).assertIsDisplayed()
        compose.revealSetting(text(R.string.eq_title)).assertIsDisplayed()
    }
    @Test fun deviceMenuIsAnOverlayAndRenameCanBeCancelled() {
        val before = compose.onNodeWithContentDescription(text(R.string.redesign_slider)).fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithContentDescription(text(R.string.fidelity_device_options)).performClick()
        compose.onNodeWithText(text(R.string.redesign_bluetooth)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.rename_title)).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Not saved")
        compose.onNodeWithContentDescription(text(android.R.string.cancel)).performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(before, compose.onNodeWithContentDescription(text(R.string.redesign_slider)).fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithText("Not saved").assertDoesNotExist()
    }
    @Test fun backClosesTheDeviceMenuBeforeLeavingTheActivity() {
        compose.onNodeWithContentDescription(text(R.string.fidelity_device_options)).performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.redesign_bluetooth)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.redesign_slider)).assertIsDisplayed()
    }
    @Test fun advancedFeaturesHaveSeparatePages() {
        open(R.string.fidelity_more)
        open(R.string.redesign_press)
        compose.revealSetting(text(R.string.control_press_speed)).assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        open(R.string.redesign_audio)
        compose.revealSetting(text(R.string.conversation_pause_media_title)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.dashboard_back)).performClick()
        compose.revealSetting(text(R.string.redesign_press)).assertIsDisplayed()
    }
    @Test fun theWholeTileIsOneAccessibleSwitch() {
        val row = compose.revealSetting(hasContentDescription(text(R.string.personlized_volume_title)))
        row.assertHasClickAction().assertIsOff().performClick().assertIsOn()
        row.performClick().assertIsOff()
    }
    @Test fun deviceInformationAndDiagnosticsLiveOnlyInAbout() {
        compose.onNodeWithText(text(R.string.diagnostics_title)).assertDoesNotExist()
        compose.renderAboutFixture()
        compose.waitForIdle()
        open(R.string.dashboard_device_info)
        compose.revealSetting("A3049").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        open(R.string.diagnostics_title)
        compose.revealSetting(text(R.string.diagnostics_logs)).assertIsDisplayed()
    }
    @Test fun horizontalSliderGestureDoesNotChangeTheBottomTab() {
        compose.onNodeWithContentDescription(text(R.string.redesign_slider)).performTouchInput { swipe(centerRight, centerLeft, 400) }
        compose.onNodeWithText(text(R.string.dashboard_headphones)).assertIsSelected()
    }
    @Test fun homeEqualizerShowsOnlyTheReportedMode() {
        compose.revealSetting(text(R.string.eq_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.eq_recommended)).assertDoesNotExist()
        compose.runOnUiThread { compose.activity.intent.putExtra("eq_state", 2) }
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.revealSetting(text(R.string.eq_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.eq_custom)).assertIsDisplayed()
    }

    @Test fun largeFontKeepsBatteryLabelsAndPercentagesTogether() {
        compose.runOnUiThread { compose.activity.setContent { AppTheme(1) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                Box(Modifier.width(360.dp)) {
                    DeviceHero("AirPods Pro", AirPodsModels.getModelByModelNumber("A3049"),
                        BatteryParams(PodBatteryParams(100, isConnected = true), PodBatteryParams(87, isConnected = true),
                            PodBatteryParams(73, isConnected = true)), EarDetectionParams(), null, false, {})
                }
            }
        } } }
        for (label in listOf(text(R.string.dashboard_left), text(R.string.dashboard_right), text(R.string.dashboard_case), "100%", "87%", "73%")) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            org.junit.Assert.assertEquals("Battery reading must stay together: $label", 1, layouts.single().lineCount)
            org.junit.Assert.assertFalse("Battery reading must not be ellipsized: $label", layouts.single().isLineEllipsized(0))
            org.junit.Assert.assertEquals(label.length, layouts.single().getLineEnd(0, visibleEnd = true))
        }
    }
}
