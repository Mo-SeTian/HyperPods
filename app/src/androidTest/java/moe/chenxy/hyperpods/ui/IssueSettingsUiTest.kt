package moe.chenxy.hyperpods.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
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
import top.yukonga.miuix.kmp.basic.Scaffold

/** Synthetic protocol state: never writes to a physical Bluetooth device. */
class IssueSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<DashboardPreviewActivity>()
    private val settings = mutableStateOf<Map<String, Int>>(emptyMap())
    private val feedback = mutableStateOf<Map<String, String>>(emptyMap())
    private val writes = mutableListOf<Pair<String, Int>>()
    private val modes = mutableListOf<NoiseControlMode>()
    private var failWrite = false
    private fun text(id: Int) = compose.activity.getString(id)

    private fun render(connection: String = "connected") {
        fun write(key: String, value: Int) {
            writes.add(key to value)
            if (!failWrite) settings.value = settings.value + (key to value)
            feedback.value = feedback.value + (key to if (failWrite) "write_failed" else "sent")
        }
        compose.runOnUiThread {
            compose.activity.setContent {
                AppTheme(1) {
                    Scaffold { _ ->
                    PodDetailPage(padding = PaddingValues(top = 32.dp, bottom = 32.dp),
                        batteryParams = BatteryParams(PodBatteryParams(), PodBatteryParams(), PodBatteryParams()),
                        earDetectionParams = EarDetectionParams(), earDetectionEnable = true, onEarDetectionChanged = {},
                        autoSwitchToSpeaker = false, onAutoSwitchToSpeakerChange = {},
                        personlizedVolume = false, onPersonlizedVolumeChange = {},
                        conversationAwareness = false, onConversationAwarenessChange = {},
                        adjustVolumeBySwiper = settings.value[Key.ADJUST_VOLUME_BY_SWIPER] == 1,
                        onAdjustVolumeBySwiperChange = { write(Key.ADJUST_VOLUME_BY_SWIPER, if (it) 1 else 2) },
                        adaptiveAudioLevel = 0.5f, onAdaptiveAudioLevelChange = {},
                        onListeningModeChange = { write(Key.LISTENING_MODE_BYTE, it.toInt()) },
                        ancMode = settings.value[PodsSettings.NOISE_MODE]?.let { NoiseControlMode.entries[it - 1] },
                        onAncModeChange = { modes.add(it) },
                        podsInfo = AACPManager.Companion.AirPodsInformation("Demo", "A3439", "", "", "", "", "", "", "", "", ""),
                        onNameChange = {}, microphoneMode = PodsSettings.microphoneIndex(settings.value[Key.MICROPHONE_MODE]) ?: 0,
                        onMicrophoneModeChange = { write(Key.MICROPHONE_MODE, PodsSettings.microphoneValue(it)!!) },
                        noiseCancellationSingleAirPod = settings.value[Key.SINGLE_POD_ANC] == 1,
                        onNoiseCancellationSingleAirPodChange = { write(Key.SINGLE_POD_ANC, if (it) 1 else 2) },
                        settings = settings.value,
                        settingFeedback = feedback.value, onSettingChange = ::write,
                        diagnostics = Bundle().apply { putString("connection", connection) })
                    }
                }
            }
        }
        compose.revealSetting(text(R.string.dashboard_more)).performClick()
    }

    @Test fun missingMicrophoneReportAllowsExplicitSelectionWithoutInventingAuto() {
        render()
        compose.revealSetting(text(R.string.microphone)).performScrollTo().performClick()
        // Miuix also subcomposes unplaced rows to measure the popup width.
        compose.onAllNodesWithText(text(R.string.microphone_left))
            .filter(SemanticsMatcher("Placed popup row") { it.layoutInfo.isPlaced }).onFirst().performClick()
        compose.waitForIdle()
        assertEquals(listOf(Key.MICROPHONE_MODE to 2), writes)
        assertEquals("sent", feedback.value[Key.MICROPHONE_MODE])
        compose.onNodeWithText(text(R.string.microphone_left)).assertExists()
    }

    @Test fun missingLongPressReportAllowsSelectingTwoModesButNeverSendsAnEmptyMask() {
        render()
        compose.revealSetting(text(R.string.set_noise_mode)).performScrollTo().performClick()
        compose.onNodeWithContentDescription(text(android.R.string.ok)).assertIsNotEnabled()
        assertEquals(emptyList<Pair<String, Int>>(), writes)
        compose.onNodeWithText(text(R.string.noise_cancellation_title)).performClick()
        compose.onNodeWithText(text(R.string.transparency_title)).performClick()
        compose.onNodeWithContentDescription(text(android.R.string.ok)).assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(listOf(Key.LISTENING_MODE_BYTE to 6), writes)
    }

    @Test fun disconnectedUnknownControlsRemainDisabled() {
        render("disconnected")
        // Miuix 0.6.1 disables these rows by removing their click modifier.
        val disabled = !(hasClickAction() or hasAnyAncestor(hasClickAction()))
        compose.revealSetting(text(R.string.set_noise_mode)).assert(disabled)
        compose.revealSetting(text(R.string.microphone)).assert(disabled)
        assertEquals(emptyList<Pair<String, Int>>(), writes)
    }

    @Test fun chimeStaysAtTheSentValueAndResetsOnARealWriteFailure() {
        settings.value = mapOf(Key.CHIME_VOLUME to 50)
        render()
        compose.revealSetting(text(R.string.control_chime_volume))
        val slider = compose.onNode(SemanticsMatcher("Chime volume slider") {
            it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.range?.endInclusive == 100f
        })
        slider.performScrollTo().performTouchInput { swipe(center, centerRight, 300) }
        compose.waitForIdle()
        val sent = settings.value.getValue(Key.CHIME_VOLUME)
        assertEquals("sent", feedback.value[Key.CHIME_VOLUME])
        assertEquals(sent.toFloat(), slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0.01f)
        compose.runOnIdle { failWrite = true }
        slider.performTouchInput { swipe(center, centerLeft, 300) }
        compose.waitForIdle()
        assertEquals("write_failed", feedback.value[Key.CHIME_VOLUME])
        assertEquals(sent.toFloat(), slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0.01f)
    }

    @Test fun swipeIntervalLongestReportMapsToThirdOptionAndDefaultWritesOne() {
        settings.value = mapOf(Key.SWIPE_SPEED to 3)
        render()
        compose.revealSetting(text(R.string.control_swipe_speed)).performScrollTo()
            .assert(hasText(text(R.string.control_longest))).performClick()
        compose.onAllNodesWithText(text(R.string.control_default))
            .filter(SemanticsMatcher("Placed popup row") { it.layoutInfo.isPlaced }).onFirst().performClick()
        compose.waitForIdle()
        assertEquals(listOf(Key.SWIPE_SPEED to 1), writes)
    }

    @Test fun sentOnlyControlsDoNotShowAnEndlessWaitingMessage() {
        settings.value = mapOf(Key.CHIME_VOLUME to 50, Key.ADJUST_VOLUME_BY_SWIPER to 1, Key.SINGLE_POD_ANC to 1)
        feedback.value = settings.value.keys.associateWith { "sent" }
        render()
        for (title in listOf(R.string.control_chime_volume, R.string.adjust_volume_by_swiper_title,
            R.string.noise_cancellation_single_airpod)) {
            compose.revealSetting(text(title))
            compose.onAllNodesWithText(text(R.string.setting_sent), substring = true).assertCountEquals(0)
            compose.onAllNodesWithText(text(R.string.setting_pending), substring = true).assertCountEquals(0)
        }
    }

    @Test fun enablingSingleAncDoesNotForceTransparencyIntoAnc() {
        settings.value = mapOf(Key.SINGLE_POD_ANC to 2, PodsSettings.NOISE_MODE to 3)
        render()
        compose.revealSetting(text(R.string.noise_cancellation_single_airpod)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(Key.SINGLE_POD_ANC to 1), writes)
        assertEquals(emptyList<NoiseControlMode>(), modes)
        assertEquals(3, settings.value[PodsSettings.NOISE_MODE])
    }

    @Test fun aRealWriteFailureStillShowsAnErrorAndPreservesThePreviousSwitchValue() {
        settings.value = mapOf(Key.ADJUST_VOLUME_BY_SWIPER to 2)
        failWrite = true
        render()
        compose.revealSetting(text(R.string.adjust_volume_by_swiper_title)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(Key.ADJUST_VOLUME_BY_SWIPER to 1), writes)
        assertEquals(2, settings.value[Key.ADJUST_VOLUME_BY_SWIPER])
        compose.onNodeWithText(text(R.string.setting_write_failed), substring = true).assertExists()
    }
}
