package moe.chenxy.hyperpods.ui

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
    }

    @Test fun onlyCapableModelsOfferTheTwoControls() {
        for (label in listOf(R.string.head_gestures_title, R.string.sleep_detection_title)) {
            if (supported) {
                compose.onNodeWithContentDescription(text(label)).performScrollTo().assertIsEnabled().performClick().assertIsOn()
            } else compose.onNodeWithText(text(label)).assertDoesNotExist()
        }
    }

    @Test fun choicesSurviveNavigatingToSecondaryPageAndBack() {
        if (!supported) return
        for (label in listOf(R.string.head_gestures_title, R.string.sleep_detection_title)) {
            compose.onNodeWithContentDescription(text(label)).performScrollTo().performClick()
        }
        compose.onNodeWithText(text(R.string.dashboard_more)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        for (label in listOf(R.string.head_gestures_title, R.string.sleep_detection_title)) {
            compose.onNodeWithContentDescription(text(label)).performScrollTo().assertIsOn()
        }
    }
}
