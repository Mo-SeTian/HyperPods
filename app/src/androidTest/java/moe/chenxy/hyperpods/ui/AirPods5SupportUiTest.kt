package moe.chenxy.hyperpods.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import moe.chenxy.hyperpods.R
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Production settings with synthetic AirPods 5 reports, never real Bluetooth writes. */
@RunWith(Parameterized::class)
class AirPods5SupportUiTest(private val number: String, private val wireless: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "model={0}")
        fun models() = listOf(arrayOf("A3531", false), arrayOf("A3439", true))
    }

    @get:Rule val compose = createAndroidComposeRule<DashboardPreviewActivity>()
    private fun text(id: Int) = compose.activity.getString(id)

    @Before fun selectModel() {
        compose.runOnUiThread {
            compose.activity.intent.putExtra("model_number", number).putExtra("case_connected", true)
        }
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
    }

    @Test fun homeShowsSeparateBatteryReadingsAndAllFourNoiseModes() {
        for (level in listOf("92%", "87%", "73%")) compose.onNodeWithText(level).assertIsDisplayed()
        for (label in listOf(R.string.off, R.string.noise_cancellation_title,
            R.string.dashboard_transparency, R.string.adaptive_title)) {
            compose.onNodeWithText(text(label)).assertIsDisplayed().assertIsEnabled()
        }
    }

    @Test fun personalizedVolumeAndConversationSwitchesAreAvailable() {
        for (label in listOf(R.string.personlized_volume_title, R.string.dashboard_conversation_title)) {
            compose.revealSetting(text(label))
            compose.onNodeWithContentDescription(text(label)).assertIsEnabled().performClick().assertIsOn()
        }
    }

    @Test fun onlyWirelessVariantExposesSwipeControls() {
        compose.revealSetting(text(R.string.dashboard_more)).performClick()
        compose.waitForIdle()
        compose.revealSetting(text(R.string.control_press_speed)).assertExists()
        compose.revealSetting(text(R.string.control_hold_duration)).assertExists()
        for (label in listOf(R.string.adjust_volume_by_swiper_title, R.string.control_swipe_speed)) {
            if (wireless) compose.revealSetting(text(label)).assertExists()
            else compose.onNodeWithText(text(label)).assertDoesNotExist()
        }
    }

    @Test fun informationPageShowsTheCorrectVariantAndModelNumber() {
        compose.revealSetting(text(R.string.dashboard_device_info)).performClick()
        compose.waitForIdle()
        val name = if (wireless) "AirPods 5 with Wireless Charging Case" else "AirPods 5"
        compose.onNodeWithText(name).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(number).performScrollTo().assertIsDisplayed()
    }
}
