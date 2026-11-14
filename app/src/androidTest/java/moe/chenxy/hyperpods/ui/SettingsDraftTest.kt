package moe.chenxy.hyperpods.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.pods.NoiseControlMode
import moe.chenxy.hyperpods.utils.AACPManager
import moe.chenxy.hyperpods.utils.CustomEqState
import moe.chenxy.hyperpods.utils.PodsSettings
import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.EarDetectionParams
import moe.chenxy.hyperpods.utils.data.HyperPodsPrefsKey as Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Check mode/strength ordering and cancellations using the real home component. */
class SettingsDraftTest {
    @get:Rule val compose = createAndroidComposeRule<DashboardPreviewActivity>()
    private val mode = mutableStateOf(NoiseControlMode.NOISE_CANCELLATION)
    private val recovery = mutableStateOf("")
    private val feedback = mutableStateOf(emptyMap<String, String>())
    private val level = mutableStateOf(.5f)
    private val modes = mutableListOf<NoiseControlMode>()
    private val levels = mutableListOf<Float>()
    private fun slider() = compose.onNodeWithContentDescription(compose.activity.getString(R.string.redesign_slider))
    private fun position(value: Float) { slider().performSemanticsAction(SemanticsActions.SetProgress) { it(value) }; compose.waitForIdle() }
    private fun render() { compose.runOnUiThread { compose.activity.setContent { AppTheme(1) {
        PodDetailPage(PaddingValues(), BatteryParams(), EarDetectionParams(), true, {}, false, {}, false, {}, false, {},
            false, {}, level.value, { levels.add(it) }, {}, mode.value, { modes.add(it) },
            AACPManager.Companion.AirPodsInformation("Demo", "A3049", "", "", "", "", "", "", "", "", ""),
            {}, 0, {}, false, {}, settings = mapOf(Key.ADAPTIVE_AUDIO_LEVEL to 50, PodsSettings.NOISE_MODE to 2),
            settingFeedback = feedback.value, customEqState = CustomEqState(session = 1),
            diagnostics = Bundle().apply { putString("connection", "connected"); putString("recovery", recovery.value) })
    } } }; compose.waitForIdle() }
    @Test fun strengthWaitsForModeConfirmationAndSurvivesNavigation() {
        render(); assertTrue(modes.isEmpty()); assertTrue(levels.isEmpty())
        position(.7f)
        assertEquals(listOf(NoiseControlMode.ADAPTIVE), modes); assertTrue(levels.isEmpty())
        compose.revealSetting(compose.activity.getString(R.string.fidelity_more)).performClick()
        compose.runOnIdle { mode.value = NoiseControlMode.ADAPTIVE }
        compose.waitForIdle()
        assertEquals(1, levels.size); assertEquals(1f - (.7f - .2f) / .6f, levels.single(), .001f)
    }
    @Test fun recoveryCancelsADeferredStrengthWrite() {
        render(); position(.7f)
        compose.runOnIdle { recovery.value = "recovering" }
        compose.waitForIdle()
        compose.runOnIdle { mode.value = NoiseControlMode.ADAPTIVE; recovery.value = "" }
        compose.waitForIdle()
        assertTrue(levels.isEmpty())
    }
    @Test fun endpointsSelectTransparencyAndNoiseCancellationWithoutChangingStrength() {
        render(); position(0f); position(1f)
        assertEquals(listOf(NoiseControlMode.TRANSPARENCY, NoiseControlMode.NOISE_CANCELLATION), modes)
        assertTrue(levels.isEmpty())
    }
    @Test fun failedStrengthReturnsToTheReportedPosition() {
        mode.value = NoiseControlMode.ADAPTIVE
        render(); position(.75f)
        compose.runOnIdle { feedback.value = mapOf(Key.ADAPTIVE_AUDIO_LEVEL to "write_failed") }
        compose.waitForIdle()
        assertEquals(.5f, slider().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .01f)
    }
    @Test fun cancelledGestureDoesNotSendAnyCommands() {
        render()
        slider().performTouchInput { down(center); moveTo(centerLeft); cancel() }
        compose.waitForIdle(); assertTrue(modes.isEmpty()); assertTrue(levels.isEmpty())
    }
}
