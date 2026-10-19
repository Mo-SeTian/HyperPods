package moe.chenxy.hyperpods.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import android.os.SystemClock
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.ui.components.HeadTrackingContent
import moe.chenxy.hyperpods.utils.HeadPose
import moe.chenxy.hyperpods.utils.HeadTrackingPreview.Snapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Synthetic rendering only: never fabricates a trusted Bluetooth response or call. */
@OptIn(ExperimentalTestApi::class)
@RunWith(Parameterized::class)
class HeadTrackingUiTest(private val mode: Int, private val motionScale: Float) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "theme={0},motion={1}")
        fun modes() = listOf(arrayOf<Any>(1, 1f), arrayOf<Any>(2, 0f))
    }
    @get:Rule val compose = createAndroidComposeRule<DashboardPreviewActivity>(effectContext = object : MotionDurationScale {
        override val scaleFactor = motionScale
    })
    private fun text(id: Int) = compose.activity.getString(id)
    private val pose = HeadPose.Sample(12f, -8f, true, 1000, -500)

    private fun render(initial: Snapshot, stale: Boolean = false) {
        compose.runOnUiThread {
            compose.activity.setContent {
                var snapshot by remember { mutableStateOf(initial) }
                AppTheme(mode) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        HeadTrackingContent(snapshot, listOf(pose, pose), stale, true,
                            onStart = { snapshot = snapshot.copy(state = "tracking", sample = pose, gesture = "NOD", nods = 1) },
                            onStop = { snapshot = snapshot.copy(state = "stopped", sample = null) },
                            onCenter = { snapshot = snapshot.copy(state = "calibrating", sample = null, gesture = "") })
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun independentTestShowsResultAndCanStopAndRestart() {
        render(Snapshot("fixture", 0, "idle"))
        compose.onNodeWithText(text(R.string.head_test_center)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.head_test_start)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.head_test_nod)).assertExists()
        compose.onNodeWithText(text(R.string.head_test_stop)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.head_test_stopped)).assertExists()
        compose.onNodeWithText(text(R.string.head_test_pose_waiting)).assertExists()
        compose.onNodeWithText(text(R.string.head_test_start)).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText(text(R.string.head_test_nod)).assertExists()
    }

    @Test fun recenterClearsOldPoseAndKeepsAccessibleCharts() {
        render(Snapshot("fixture", 1, "tracking", sample = pose, gesture = "SHAKE"))
        compose.onNodeWithText(text(R.string.head_test_shake)).assertExists()
        compose.onNodeWithText(text(R.string.head_test_center)).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText(text(R.string.head_test_calibrating)).assertExists()
        compose.onNodeWithText(text(R.string.head_test_pose_waiting)).assertExists()
        compose.onNodeWithContentDescription(text(R.string.head_test_graph)).assertExists()
        compose.onNodeWithContentDescription(text(R.string.head_test_pose)).assertExists()
    }

    @Test fun staleSamplesNeverAppearAsLivePose() {
        render(Snapshot("fixture", 1, "tracking", sample = pose), stale = true)
        compose.onNodeWithText(text(R.string.head_test_stale)).assertExists()
        compose.onNodeWithText(text(R.string.head_test_pose_waiting)).assertExists()
        compose.onNodeWithText(text(R.string.head_test_center)).performScrollTo().assertIsNotEnabled()
    }

    @Test fun callInterruptionExplainsWhyAndOffersExplicitRetry() {
        render(Snapshot("fixture", 1, "interrupted", nods = 2, shakes = 1, gesture = "NOD"))
        compose.onNodeWithText(text(R.string.head_test_interrupted)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.head_test_counts, 2, 1)).assertExists()
        compose.onNodeWithText(text(R.string.head_test_start)).performScrollTo().assertIsEnabled()
    }

    @Test fun realSettingsPageOffersEntryWithoutEnablingCallGestures() {
        compose.revealSetting(text(R.string.dashboard_more)).performClick()
        compose.revealSetting(text(R.string.head_test_title)).performClick()
        compose.onNodeWithText(text(R.string.head_test_safety)).assertExists()
        compose.onNodeWithText(text(R.string.head_test_idle)).assertExists()
        compose.onNodeWithText(text(R.string.head_test_start)).performScrollTo().assertIsEnabled()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.head_test_title)).assertExists()
    }

    @Test fun backgroundingStopsTheUiSessionAndDoesNotAutoRestartOnResume() {
        compose.revealSetting(text(R.string.dashboard_more)).performClick()
        compose.revealSetting(text(R.string.head_test_title)).performClick()
        compose.onNodeWithText(text(R.string.head_test_start)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.head_test_starting)).assertExists()
        val started = SystemClock.elapsedRealtime()
        compose.waitUntil(timeoutMillis = 4000) { SystemClock.elapsedRealtime() - started > 2200 }
        // A stream that has never delivered a packet is still waiting, not "paused".
        compose.onNodeWithText(text(R.string.head_test_starting)).assertExists()
        compose.onNodeWithText(text(R.string.head_test_stale)).assertDoesNotExist()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.head_test_background)).assertExists()
        compose.onNodeWithText(text(R.string.head_test_start)).performScrollTo().assertIsEnabled()
        compose.onNodeWithText(text(R.string.head_test_stop)).assertDoesNotExist()
    }
}
