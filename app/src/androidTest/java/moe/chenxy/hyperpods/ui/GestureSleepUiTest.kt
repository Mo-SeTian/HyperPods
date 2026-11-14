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
        compose.revealSetting(text(R.string.fidelity_more)).performClick()
        compose.waitForIdle()
    }

    @Test fun onlyCapableModelsOfferTheTwoControls() {
        if (!supported) {
            compose.onNodeWithText(text(R.string.head_gestures_title)).assertDoesNotExist()
            compose.onNodeWithText(text(R.string.fidelity_sleep)).assertDoesNotExist()
            return
        }
        compose.revealSetting(hasContentDescription(text(R.string.fidelity_sleep))).assertIsEnabled().performClick().assertIsOn()
        compose.revealSetting(text(R.string.head_gestures_title)).performClick()
        compose.revealSetting(hasContentDescription(text(R.string.head_gestures_title))).assertIsEnabled().performClick().assertIsOn()
    }
    @Test fun choicesSurviveNavigatingToSecondaryPageAndBack() {
        if (!supported) return
        compose.revealSetting(hasContentDescription(text(R.string.fidelity_sleep))).performClick()
        compose.revealSetting(text(R.string.head_gestures_title)).performClick()
        compose.revealSetting(hasContentDescription(text(R.string.head_gestures_title))).performClick()
        compose.revealSetting(text(R.string.head_test_title)).performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.revealSetting(hasContentDescription(text(R.string.head_gestures_title))).assertIsOn()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.revealSetting(hasContentDescription(text(R.string.fidelity_sleep))).assertIsOn()
    }
}
