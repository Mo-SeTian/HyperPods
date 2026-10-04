package moe.chenxy.hyperpods.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.pods.NoiseControlMode
import moe.chenxy.hyperpods.utils.AACPManager
import moe.chenxy.hyperpods.utils.PodsSettings
import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.EarDetectionParams
import moe.chenxy.hyperpods.utils.data.PodBatteryParams
import moe.chenxy.hyperpods.utils.data.HyperPodsPrefsKey as Key
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** A failed write must restore the real headset value, even when it has not changed. */
class SettingsDraftTest {
    @get:Rule val compose = createAndroidComposeRule<DashboardPreviewActivity>()

    @Test fun adaptiveSliderResetsAfterImmediateFailureAndAfterTimeout() {
        val feedback = mutableStateOf(emptyMap<String, String>())
        val pending = mutableStateOf(emptySet<String>())
        var immediateFailure = true
        compose.runOnUiThread {
            compose.activity.setContent {
                AppTheme(1) {
                    PodDetailPage(
                        padding = PaddingValues(),
                        batteryParams = BatteryParams(PodBatteryParams(), PodBatteryParams(), PodBatteryParams()),
                        earDetectionParams = EarDetectionParams(),
                        earDetectionEnable = true, onEarDetectionChanged = {},
                        autoSwitchToSpeaker = false, onAutoSwitchToSpeakerChange = {},
                        personlizedVolume = false, onPersonlizedVolumeChange = {},
                        conversationAwareness = false, onConversationAwarenessChange = {},
                        adjustVolumeBySwiper = false, onAdjustVolumeBySwiperChange = {},
                        adaptiveAudioLevel = 0.5f, onAdaptiveAudioLevelChange = {
                            feedback.value = mapOf(Key.ADAPTIVE_AUDIO_LEVEL to if (immediateFailure) "write_failed" else "sent")
                            if (!immediateFailure) pending.value = setOf(Key.ADAPTIVE_AUDIO_LEVEL)
                        },
                        onListeningModeChange = {}, ancMode = NoiseControlMode.ADAPTIVE, onAncModeChange = {},
                        podsInfo = AACPManager.Companion.AirPodsInformation("Demo", "A3049", "", "", "", "", "", "", "", "", ""),
                        onNameChange = {}, microphoneMode = 0, onMicrophoneModeChange = {},
                        noiseCancellationSingleAirPod = false, onNoiseCancellationSingleAirPodChange = {},
                        settings = mapOf(Key.ADAPTIVE_AUDIO_LEVEL to 50, PodsSettings.NOISE_MODE to 4),
                        pendingSettings = pending.value, settingFeedback = feedback.value,
                    )
                }
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.dashboard_more)).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.adaptive_audio_title)).performScrollTo()
        val slider = compose.onNode(SemanticsMatcher("Adaptive slider") {
            it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.range?.endInclusive == 1f
        })
        slider.performScrollTo()
        slider.performTouchInput { swipe(center, centerRight, 300) }
        compose.waitForIdle()
        assertEquals("write_failed", feedback.value[Key.ADAPTIVE_AUDIO_LEVEL])
        assertEquals(0.5f, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0.01f)

        compose.runOnIdle { immediateFailure = false }
        slider.performTouchInput { swipe(center, centerRight, 300) }
        compose.waitForIdle()
        assertEquals("sent", feedback.value[Key.ADAPTIVE_AUDIO_LEVEL])
        compose.runOnIdle {
            pending.value = emptySet()
            feedback.value = mapOf(Key.ADAPTIVE_AUDIO_LEVEL to "timeout")
        }
        compose.waitForIdle()
        assertEquals(0.5f, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0.01f)
        compose.onNodeWithText(compose.activity.getString(R.string.adaptive_audio_title)).assertIsDisplayed()
    }
}
