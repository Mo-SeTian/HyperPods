package moe.chenxy.hyperpods.ui

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.pods.NoiseControlMode
import moe.chenxy.hyperpods.utils.AACPManager
import moe.chenxy.hyperpods.utils.CustomEq
import moe.chenxy.hyperpods.utils.CustomEqState
import moe.chenxy.hyperpods.utils.PodsSettings
import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.EarDetectionParams
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import top.yukonga.miuix.kmp.basic.Scaffold
import java.io.File

/** Real Miuix page/menu/slider interactions; synthetic settings never reach Bluetooth. */
@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class EqualizerUiTest(private val motionScale: Float) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "motionScale={0}")
        fun scales() = listOf(arrayOf(1f), arrayOf(0f))
    }
    @get:Rule val compose = createAndroidComposeRule<DashboardPreviewActivity>(effectContext = object : MotionDurationScale {
        override val scaleFactor = motionScale
    })
    private val state = mutableStateOf(CustomEqState(CustomEq(1, 65, 50, 60), session = 7))
    private val feedback = mutableStateOf<String?>(null)
    private val active = mutableStateOf(true)
    private val theme = mutableStateOf(1)
    private val writes = mutableListOf<Pair<CustomEq, Long>>()
    private var fail = false
    private fun text(id: Int) = compose.activity.getString(id)

    private fun render(firmware: String = "9A348") {
        compose.runOnUiThread {
            compose.activity.setContent {
                AppTheme(theme.value) {
                    Scaffold { padding ->
                        PodDetailPage(padding = padding, batteryParams = BatteryParams(), earDetectionParams = EarDetectionParams(),
                            earDetectionEnable = true, onEarDetectionChanged = {}, autoSwitchToSpeaker = false,
                            onAutoSwitchToSpeakerChange = {}, personlizedVolume = false, onPersonlizedVolumeChange = {},
                            conversationAwareness = false, onConversationAwarenessChange = {}, adjustVolumeBySwiper = false,
                            onAdjustVolumeBySwiperChange = {}, adaptiveAudioLevel = .5f, onAdaptiveAudioLevelChange = {},
                            onListeningModeChange = {}, ancMode = NoiseControlMode.ADAPTIVE, onAncModeChange = {},
                            podsInfo = AACPManager.Companion.AirPodsInformation("AirPods Pro", "A3049", "", "", "", "", "", "", "", "", firmware),
                            onNameChange = {}, microphoneMode = 0, onMicrophoneModeChange = {},
                            noiseCancellationSingleAirPod = false, onNoiseCancellationSingleAirPodChange = {},
                            isActive = active.value, customEqState = state.value,
                            settingFeedback = feedback.value?.let { mapOf(PodsSettings.CUSTOM_EQ to it) }.orEmpty(),
                            diagnostics = Bundle().apply { putString("connection", "connected") },
                            onCustomEqChange = { value, session ->
                                writes.add(value to session)
                                state.value = state.value.copy(value = if (fail) state.value.value else value,
                                    revision = state.value.revision + 1, unconfirmed = !fail)
                                feedback.value = if (fail) "write_failed" else "sent"
                            })
                    }
                }
            }
        }
        compose.revealSetting(text(R.string.dashboard_more)).performClick()
        compose.revealSetting(text(R.string.eq_title)).performClick()
        compose.waitForIdle()
    }

    private fun select(id: Int) {
        compose.onNodeWithText(text(R.string.eq_mode)).performClick()
        compose.mainClock.advanceTimeBy(500)
        menuItem(id).performClick()
        compose.waitForIdle()
    }

    // Miuix 0.6.1 subcomposes unplaced measurement copies of menu items.
    // Inject touch only into the real item that was placed on screen.
    private fun menuItem(id: Int) = compose.onAllNodesWithText(text(id))
        .filter(SemanticsMatcher("Placed menu item") { it.layoutInfo.isPlaced }).onLast()

    private fun value(index: Int): Float = compose.onNodeWithTag("eq_band_$index").fetchSemanticsNode()
        .config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun capture(name: String) {
        val dir = File(compose.activity.getExternalFilesDir(null), "eq-ui").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun dropdownShowsControlsOnlyForCustomAndPreservesValuesWhenSwitching() {
        render()
        assertTrue(writes.isEmpty())
        compose.onNodeWithTag("eq_band_0").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.eq_reset)).assertDoesNotExist()
        capture("recommended")
        compose.onNodeWithText(text(R.string.eq_mode)).performClick()
        compose.mainClock.advanceTimeBy(500)
        capture("dropdown")
        menuItem(R.string.eq_custom).performClick()
        compose.waitForIdle()
        assertEquals(CustomEq(2, 65, 50, 60) to 7L, writes.single())
        assertEquals(65f, value(0))
        capture("custom-light")
        select(R.string.eq_recommended)
        compose.onNodeWithTag("eq_band_0").assertDoesNotExist()
        assertEquals(CustomEq(1, 65, 50, 60), writes.last().first)
        select(R.string.eq_custom)
        assertEquals(65f, value(0))
        compose.onNodeWithText(text(R.string.eq_reset)).performClick()
        compose.waitForIdle()
        assertEquals(CustomEq(2, 50, 50, 50), writes.last().first)
        assertEquals(50f, value(0))
        compose.runOnIdle { theme.value = 2 }
        capture("custom-dark")
    }

    @Test fun changingOneBandKeepsOtherBandsAndFlushesTheReleasedValue() {
        state.value = state.value.copy(value = CustomEq(2, 65, 45, 60))
        render()
        assertTrue(writes.isEmpty())
        compose.onNodeWithTag("eq_band_0").performTouchInput { swipe(center, centerRight, 300) }
        compose.waitForIdle()
        assertTrue(writes.isNotEmpty())
        assertEquals(value(0).toInt(), writes.last().first.low)
        assertEquals(45, writes.last().first.mid)
        assertEquals(60, writes.last().first.high)
        assertTrue("Slider must coalesce drag samples", writes.size <= 6)
    }

    @Test fun repeatedWriteFailuresRestoreTheLastValueAndReportsDoNotEchoWrites() {
        state.value = state.value.copy(value = CustomEq(2, 65, 45, 60))
        fail = true
        render()
        repeat(2) {
            compose.onNodeWithTag("eq_band_0").performTouchInput { swipe(center, centerRight, 180) }
            compose.waitForIdle()
            assertEquals(65f, value(0))
        }
        val count = writes.size
        compose.runOnIdle {
            state.value = state.value.copy(value = CustomEq(2, 30, 40, 70), revision = state.value.revision + 1)
            feedback.value = "reported"
        }
        compose.waitForIdle()
        assertEquals(30f, value(0))
        assertEquals(count, writes.size)
    }

    @Test fun numericFirmwareAllowsAnExplicitSelectionWithoutWritingOnEntry() {
        state.value = state.value.copy(value = null)
        render(firmware = "9441861")
        compose.onNodeWithText(text(R.string.eq_unknown)).assertExists()
        compose.onNodeWithTag("eq_band_0").assertDoesNotExist()
        assertTrue(writes.isEmpty())
        select(R.string.eq_custom)
        assertEquals(CustomEq(2, 50, 50, 50), writes.single().first)
        compose.onNodeWithTag("eq_band_0").performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(70f)) }
        compose.waitForIdle()
        assertEquals(CustomEq(2, 70, 50, 50), writes.last().first)
    }

    @Test fun generationEightNumericFirmwareStaysUnavailableUntilARealEqReport() {
        state.value = state.value.copy(value = null)
        render(firmware = "8454592")
        compose.onNodeWithText(text(R.string.eq_firmware_required)).assertExists()
        compose.onNodeWithText(text(R.string.eq_mode)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.eq_current_firmware, "8454592")).assertExists()
        compose.onNodeWithText(text(R.string.eq_firmware_help)).assertHasClickAction()
        capture("firmware-update")
        compose.onNodeWithTag("eq_band_0").assertDoesNotExist()
        assertTrue(writes.isEmpty())
        compose.runOnIdle { state.value = state.value.copy(value = CustomEq(2, 30, 40, 60), revision = state.value.revision + 1) }
        compose.waitForIdle()
        compose.onNodeWithTag("eq_band_0").assertIsEnabled()
        assertEquals(30f, value(0))
        assertTrue(writes.isEmpty())
    }

    @Test fun missingFirmwareShowsReadingStatusWithoutAnUpgradeDiagnosis() {
        state.value = state.value.copy(value = null)
        render(firmware = "")
        compose.onNodeWithText(text(R.string.eq_firmware_reading)).assertExists()
        compose.onNodeWithText(text(R.string.eq_firmware_unknown)).assertExists()
        compose.onNodeWithText(text(R.string.eq_firmware_help)).assertDoesNotExist()
        assertTrue(writes.isEmpty())
    }

    @Test fun returningToMoreControlsKeepsItsScrollPosition() {
        render()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.eq_title)).assertIsDisplayed()
        assertTrue(writes.isEmpty())
    }

    @Test fun unknownCompatibleStateRequiresAnExplicitSelectionBeforeWritingDefaults() {
        state.value = state.value.copy(value = null)
        render()
        assertTrue(writes.isEmpty())
        compose.onNodeWithText(text(R.string.eq_unknown)).assertExists()
        compose.onNodeWithTag("eq_band_0").assertDoesNotExist()
        select(R.string.eq_custom)
        assertEquals(CustomEq(2, 50, 50, 50), writes.single().first)
        compose.onNodeWithTag("eq_band_0").performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(75f)) }
        compose.waitForIdle()
        assertEquals(CustomEq(2, 75, 50, 50), writes.last().first)
    }

    @Test fun connectionChangeCancelsTheQueuedDrag() {
        state.value = state.value.copy(value = CustomEq(2))
        render()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("eq_band_0").performTouchInput {
            down(center)
            moveTo(centerRight, delayMillis = 16)
        }
        compose.runOnUiThread {
            active.value = false
            state.value = CustomEqState(session = 8)
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue(writes.isEmpty())
    }

    @Test fun dropdownAcceptsTouchSelectionAfterExpansion() {
        render()
        compose.onNodeWithText(text(R.string.eq_mode)).performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        menuItem(R.string.eq_custom).performClick()
        compose.waitForIdle()
        assertEquals(CustomEq(2, 65, 50, 60), writes.single().first)
    }

    @Test fun cancelledTouchSettlesAndDoesNotBlockLaterReports() {
        state.value = state.value.copy(value = CustomEq(2))
        render()
        compose.onNodeWithTag("eq_band_0").performTouchInput {
            down(center)
            moveTo(centerRight, delayMillis = 16)
            cancel()
        }
        compose.waitForIdle()
        val count = writes.size
        compose.runOnIdle { state.value = state.value.copy(value = CustomEq(2, 25, 40, 60), revision = state.value.revision + 1) }
        compose.waitForIdle()
        assertEquals(25f, value(0))
        assertEquals(count, writes.size)
    }

    @Test fun reportedModeChangeCancelsTheQueuedCustomWrite() {
        state.value = state.value.copy(value = CustomEq(2))
        render()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("eq_band_0").performTouchInput {
            down(center)
            moveTo(centerRight, delayMillis = 16)
        }
        compose.runOnUiThread { state.value = state.value.copy(value = CustomEq(1), revision = state.value.revision + 1) }
        compose.mainClock.advanceTimeBy(500)
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag("eq_band_0").assertDoesNotExist()
        assertTrue(writes.isEmpty())
    }
}
