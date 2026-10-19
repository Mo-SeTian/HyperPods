package moe.chenxy.hyperpods.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import moe.chenxy.hyperpods.R
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Synthetic production settings only. Never places, answers or rejects a real call. */
@RunWith(Parameterized::class)
class GestureSleepUiTest(private val number: String, private val supported: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "model={0}")
        fun models() = listOf(arrayOf("A3049", true), arrayOf("A3531", true),
            arrayOf("A3439", true), arrayOf("A2084", false))
    }
    @get:Rule val compose = createAndroidComposeRule<DashboardPreviewActivity>()
    private fun text(id: Int) = compose.activity.getString(id)

    @Before fun model() {
        compose.runOnUiThread { compose.activity.intent.putExtra("model_number", number) }
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.revealSetting(text(if (supported) R.string.dashboard_more_summary
            else R.string.dashboard_more_basic_summary)).assertExists()
        compose.revealSetting(text(R.string.dashboard_more)).performClick()
        compose.waitForIdle()
    }

    @Test fun onlyCapableModelsOfferTheTwoControls() {
        for (label in listOf(R.string.head_gestures_title, R.string.sleep_detection_title)) {
            if (supported) {
                compose.revealSetting(hasContentDescription(text(label))).assertIsEnabled().performClick().assertIsOn()
            } else compose.onNodeWithText(text(label)).assertDoesNotExist()
        }
    }

    @Test fun choicesSurviveNavigatingToSecondaryPageAndBack() {
        if (!supported) return
        for (label in listOf(R.string.head_gestures_title, R.string.sleep_detection_title)) {
            compose.revealSetting(hasContentDescription(text(label))).performClick()
        }
        compose.revealSetting(text(R.string.head_test_title)).performClick()
        compose.waitForIdle()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        for (label in listOf(R.string.head_gestures_title, R.string.sleep_detection_title)) {
            compose.revealSetting(hasContentDescription(text(label))).assertIsOn()
        }
    }
}
