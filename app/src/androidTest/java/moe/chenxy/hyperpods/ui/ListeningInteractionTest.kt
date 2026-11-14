package moe.chenxy.hyperpods.ui

import android.os.Bundle
import android.content.ContextWrapper
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.pods.NoiseControlMode
import moe.chenxy.hyperpods.ui.components.FidelityMicrophoneRow
import moe.chenxy.hyperpods.utils.AACPManager
import moe.chenxy.hyperpods.utils.CustomEqState
import moe.chenxy.hyperpods.utils.PodsSettings
import moe.chenxy.hyperpods.utils.HyperPodsBroadcasts
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.EarDetectionParams
import moe.chenxy.hyperpods.utils.data.HyperPodsPrefsKey as Key
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Regression coverage for listening gestures, missing reports and recovery-time menus. */
class ListeningInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<DashboardPreviewActivity>()
    private val mode = mutableStateOf(NoiseControlMode.NOISE_CANCELLATION)
    private val reportedLevel = mutableStateOf(.5f)
    private val session = mutableStateOf(1L)
    private val model = mutableStateOf("A3049")
    private val connection = mutableStateOf("connected")
    private val active = mutableStateOf(true)
    private val recovery = mutableStateOf("")
    private val pending = mutableStateOf(emptySet<String>())
    private val feedback = mutableStateOf(emptyMap<String, String>())
    private val settings = mutableStateOf(mapOf(PodsSettings.NOISE_MODE to 2, Key.ADAPTIVE_AUDIO_LEVEL to 50))
    private val modes = mutableListOf<NoiseControlMode>()
    private val levels = mutableListOf<Float>()
    private val broadcasts = mutableListOf<Intent>()
    private fun slider() = compose.onNodeWithContentDescription(compose.activity.getString(R.string.redesign_slider))
    private fun position(value: Float) {
        slider().performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
        compose.waitForIdle()
    }
    private fun render() {
        val outbound = object : ContextWrapper(compose.activity) {
            override fun sendBroadcast(intent: Intent, receiverPermission: String?, options: Bundle?) {
                broadcasts.add(Intent(intent))
            }
        }
        compose.runOnUiThread { compose.activity.setContent { AppTheme(1) {
            PodDetailPage(PaddingValues(), BatteryParams(), EarDetectionParams(), true, {}, false, {}, false, {}, false, {},
                false, {}, reportedLevel.value, { levels.add(it); sendAdaptiveAudioLevel(outbound, it) }, {}, mode.value, { modes.add(it) },
                AACPManager.Companion.AirPodsInformation("Demo", model.value, "", "", "", "", "", "", "", "", ""),
                {}, 0, {}, false, {}, settings = settings.value, pendingSettings = pending.value, settingFeedback = feedback.value,
                customEqState = CustomEqState(session = session.value), isActive = active.value,
                diagnostics = Bundle().apply { putString("connection", connection.value); putString("recovery", recovery.value) })
        } } }
        compose.waitForIdle()
    }

    @Test fun missingStrengthReportStillAllowsAnExplicitAdaptiveWrite() {
        mode.value = NoiseControlMode.ADAPTIVE
        settings.value = mapOf(PodsSettings.NOISE_MODE to 4)
        render()
        slider().assertIsEnabled()
        val inset = 41 * compose.activity.resources.displayMetrics.density
        slider().performTouchInput { swipe(center, Offset(inset + (width - 2 * inset) * .65f, center.y), 300) }
        compose.waitForIdle()
        assertTrue("Enabled adaptive drag silently dropped all writes; displayed progress=" +
            slider().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current,
            levels.isNotEmpty())
        assertTrue(levels.all { it in 0f..1f })
        assertTrue(modes.isEmpty())
    }

    @Test fun reportedAncAfterAdaptiveMustSendModeBeforeStrengthOnRelease() {
        mode.value = NoiseControlMode.ADAPTIVE
        settings.value = mapOf(PodsSettings.NOISE_MODE to 4, Key.ADAPTIVE_AUDIO_LEVEL to 50)
        render()
        compose.runOnIdle {
            mode.value = NoiseControlMode.NOISE_CANCELLATION
            settings.value = settings.value + (PodsSettings.NOISE_MODE to 2)
        }
        val inset = 41 * compose.activity.resources.displayMetrics.density
        slider().performTouchInput {
            swipe(Offset(width - inset, center.y), Offset(inset + (width - 2 * inset) * .65f, center.y), 400)
        }
        compose.waitForIdle()
        assertEquals(listOf(NoiseControlMode.ADAPTIVE), modes)
        assertTrue("Strength must not replace the missing Adaptive mode command", levels.isEmpty())
    }

    @Test fun reportedAdaptiveAfterAncMustNotSendDuplicateModeOnRelease() {
        render()
        compose.runOnIdle {
            mode.value = NoiseControlMode.ADAPTIVE
            settings.value = settings.value + (PodsSettings.NOISE_MODE to 4)
        }
        val inset = 41 * compose.activity.resources.displayMetrics.density
        slider().performTouchInput {
            swipe(center, Offset(inset + (width - 2 * inset) * .65f, center.y), 400)
        }
        compose.waitForIdle()
        assertTrue("Already Adaptive; only write strength", modes.isEmpty())
        assertTrue(levels.isNotEmpty())
    }

    @Test fun microphoneMenuMustNotCommitAfterRecoveryDisablesIt() {
        val enabled = mutableStateOf(true)
        val calls = mutableListOf<Int>()
        compose.runOnUiThread { compose.activity.setContent { AppTheme(1) {
            FidelityMicrophoneRow("Review microphone", listOf("Review auto", "Review left", "Review right"), 0,
                enabled.value) { calls.add(it) }
        } } }
        compose.onNodeWithText("Review microphone").performClick()
        compose.runOnIdle { enabled.value = false }
        compose.waitForIdle()
        compose.onNodeWithText("Review left").assertDoesNotExist()
        compose.onNodeWithText("Review microphone").assertIsNotEnabled()
        assertTrue(calls.isEmpty())
        compose.runOnIdle { enabled.value = true }
        compose.waitForIdle()
        compose.onNodeWithText("Review left").assertDoesNotExist()
        compose.onNodeWithText("Review microphone").performClick()
        compose.onNodeWithText("Review left").performClick()
        assertEquals(listOf(1), calls)
    }

    @Test fun draggingToTheVisibleLeftThumbLimitSelectsTransparency() {
        render()
        val density = compose.activity.resources.displayMetrics.density
        slider().performTouchInput {
            // The rendered endpoint thumb center is 7 dp + half its 68 dp width.
            swipe(Offset(width - 41 * density, center.y), Offset(41 * density, center.y), 400)
        }
        compose.waitForIdle()
        assertEquals("The visible endpoint center should select transparency", listOf(NoiseControlMode.TRANSPARENCY), modes)
        assertTrue(levels.isEmpty())
    }

    @Test fun draggingToTheVisibleRightThumbLimitSelectsNoiseCancellation() {
        mode.value = NoiseControlMode.TRANSPARENCY
        render()
        val inset = 41 * compose.activity.resources.displayMetrics.density
        slider().performTouchInput { swipe(Offset(inset, center.y), Offset(width - inset, center.y), 400) }
        compose.waitForIdle()
        assertEquals(listOf(NoiseControlMode.NOISE_CANCELLATION), modes)
        assertTrue(levels.isEmpty())
    }

    @Test fun interiorGestureUsesTheSameThumbCenterTravelAsRendering() {
        mode.value = NoiseControlMode.ADAPTIVE
        render()
        val inset = 41 * compose.activity.resources.displayMetrics.density
        slider().performTouchInput {
            swipe(center, Offset(inset + (width - 2 * inset) * .7f, center.y), 400)
        }
        compose.waitForIdle()
        assertTrue(modes.isEmpty())
        assertTrue(levels.isNotEmpty())
        assertEquals(1f - (.7f - .2f) / .6f, levels.last(), .02f)
    }

    @Test fun theVisibleAdaptiveRangeReachesBothNativeWireEndpoints() {
        mode.value = NoiseControlMode.ADAPTIVE
        render()
        compose.onAllNodesWithText(compose.activity.getString(R.string.adaptive_title)).onLast().assertIsDisplayed()
        compose.onAllNodes(hasText("0–100", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("50%", substring = true)).assertCountEquals(0)
        for (value in listOf(.2f, .5f, .8f)) position(value)
        assertTrue(modes.isEmpty())
        assertEquals(listOf(0, 50, 100), broadcasts.map { it.getIntExtra("value", -1) })
        broadcasts.forEach {
            assertEquals(HyperPodsAction.ACTION_PODS_SETTINGS_CHANGED, it.action)
            assertEquals(HyperPodsBroadcasts.BLUETOOTH, it.`package`)
            assertEquals(Key.ADAPTIVE_AUDIO_LEVEL, it.getStringExtra("key"))
        }
    }

    @Test fun holdingADragWritesChangedStrengthBeforeReleaseAndFlushesTheFinalValue() {
        mode.value = NoiseControlMode.ADAPTIVE
        render()
        compose.mainClock.autoAdvance = false
        val inset = 41 * compose.activity.resources.displayMetrics.density
        slider().performTouchInput { down(center); moveTo(Offset(inset + (width - 2 * inset) * .35f, center.y)) }
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        assertEquals(25, broadcasts.last().getIntExtra("value", -1))
        slider().performTouchInput { moveTo(Offset(inset + (width - 2 * inset) * .65f, center.y)) }
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        assertEquals(75, broadcasts.last().getIntExtra("value", -1))
        slider().performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(75, broadcasts.last().getIntExtra("value", -1))
        assertTrue(modes.isEmpty())
    }

    @Test fun aSessionChangeCancelsAnUnsentLiveStrengthWrite() {
        mode.value = NoiseControlMode.ADAPTIVE
        render()
        compose.mainClock.autoAdvance = false
        val inset = 41 * compose.activity.resources.displayMetrics.density
        slider().performTouchInput { down(center); moveTo(Offset(inset + (width - 2 * inset) * .35f, center.y)) }
        compose.mainClock.advanceTimeBy(30)
        compose.runOnIdle { session.value = 2L }
        compose.mainClock.advanceTimeBy(300)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue(broadcasts.isEmpty())
    }

    @Test fun recoveryStopsFurtherLiveStrengthWrites() {
        mode.value = NoiseControlMode.ADAPTIVE
        render()
        compose.mainClock.autoAdvance = false
        val inset = 41 * compose.activity.resources.displayMetrics.density
        slider().performTouchInput { down(center); moveTo(Offset(inset + (width - 2 * inset) * .35f, center.y)) }
        compose.mainClock.advanceTimeBy(30)
        compose.runOnIdle { recovery.value = "recovering" }
        compose.mainClock.advanceTimeBy(300)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue(broadcasts.isEmpty())
    }

    @Test fun pausingTheActivityCancelsLiveStrengthWithoutReplayingItOnResume() {
        mode.value = NoiseControlMode.ADAPTIVE
        render()
        compose.mainClock.autoAdvance = false
        val inset = 41 * compose.activity.resources.displayMetrics.density
        slider().performTouchInput { down(center); moveTo(Offset(inset + (width - 2 * inset) * .35f, center.y)) }
        compose.mainClock.advanceTimeBy(30)
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.mainClock.advanceTimeBy(300)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue(broadcasts.isEmpty())
    }

    @Test fun missingStrengthReportStillWaitsForAdaptiveModeConfirmation() {
        settings.value = mapOf(PodsSettings.NOISE_MODE to 2)
        render(); position(.7f)
        assertEquals(listOf(NoiseControlMode.ADAPTIVE), modes)
        assertTrue(levels.isEmpty())
        compose.runOnIdle { mode.value = NoiseControlMode.ADAPTIVE }
        compose.waitForIdle()
        assertEquals(1, levels.size)
    }

    @Test fun successfulModeWriteReleasesStrengthWithoutWaitingForFirmwareEcho() {
        render(); position(.7f)
        assertTrue(broadcasts.isEmpty())
        compose.runOnIdle {
            feedback.value = mapOf(PodsSettings.NOISE_MODE to "sent")
            pending.value = setOf(PodsSettings.NOISE_MODE)
        }
        compose.waitForIdle()
        assertEquals(1, broadcasts.size)
        assertEquals(83, broadcasts.single().getIntExtra("value", -1))
        assertEquals(NoiseControlMode.NOISE_CANCELLATION, mode.value) // Never invent a firmware confirmation.
        compose.runOnIdle { settings.value = settings.value + (Key.ADAPTIVE_AUDIO_LEVEL to 83) }
        compose.waitForIdle()
        assertEquals(1, broadcasts.size)
    }

    @Test fun failedModeWriteCancelsDeferredStrength() {
        render(); position(.7f)
        compose.runOnIdle { feedback.value = mapOf(PodsSettings.NOISE_MODE to "write_failed") }
        compose.waitForIdle()
        assertTrue(broadcasts.isEmpty())
        slider().assertIsEnabled()
    }

    @Test fun nearEndpointCommitsSnapWithoutWritingAdaptiveStrength() {
        render(); position(.04f)
        assertEquals(0f, slider().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .001f)
        position(.96f)
        assertEquals(1f, slider().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .001f)
        assertEquals(listOf(NoiseControlMode.TRANSPARENCY, NoiseControlMode.NOISE_CANCELLATION), modes)
        assertTrue(levels.isEmpty())
    }

    @Test fun aModelWithoutAdaptiveAudioOnlySelectsSupportedModes() {
        model.value = "A2084"
        render(); position(.25f); position(.75f)
        assertEquals(listOf(NoiseControlMode.TRANSPARENCY, NoiseControlMode.NOISE_CANCELLATION), modes)
        assertTrue(levels.isEmpty())
    }

    @Test fun aNewControlSessionCancelsDeferredAdaptiveStrength() {
        render(); position(.7f)
        compose.runOnIdle { session.value = 2L; mode.value = NoiseControlMode.ADAPTIVE }
        compose.waitForIdle()
        assertTrue(levels.isEmpty())
    }

    @Test fun deferredStrengthExpiresAndDoesNotStayDisabledForever() {
        render()
        compose.mainClock.autoAdvance = false
        position(.7f)
        compose.mainClock.advanceTimeBy(6600)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        slider().assertIsEnabled()
        compose.runOnIdle { mode.value = NoiseControlMode.ADAPTIVE }
        compose.waitForIdle()
        assertTrue(levels.isEmpty())
    }

    @Test fun aGestureInterruptedByRecoverySendsNoModeOrStrength() {
        render()
        slider().performTouchInput { down(center); moveTo(centerLeft) }
        compose.runOnIdle { recovery.value = "recovering" }
        compose.waitForIdle()
        slider().performTouchInput { up() }
        compose.waitForIdle()
        assertTrue(modes.isEmpty()); assertTrue(levels.isEmpty())
    }

    @Test fun transparencyReleaseEntersAdaptiveBeforeSendingStrength() {
        mode.value = NoiseControlMode.TRANSPARENCY
        render()
        val inset = 41 * compose.activity.resources.displayMetrics.density
        slider().performTouchInput { swipe(Offset(inset, center.y), center, 350) }
        compose.waitForIdle()
        assertEquals(listOf(NoiseControlMode.ADAPTIVE), modes)
        assertTrue(levels.isEmpty())
        compose.runOnIdle { mode.value = NoiseControlMode.ADAPTIVE }
        compose.waitForIdle()
        assertEquals(.5f, levels.single(), .02f)
    }

    @Test fun waitingForAModeWriteRejectsFurtherRapidSliderChanges() {
        render(); position(.7f)
        slider().assertIsNotEnabled()
        slider().performSemanticsAction(SemanticsActions.SetProgress) { assertFalse(it(0f)) }
        assertEquals(listOf(NoiseControlMode.ADAPTIVE), modes)
        compose.runOnIdle { pending.value = setOf(PodsSettings.NOISE_MODE); feedback.value = mapOf(PodsSettings.NOISE_MODE to "sent") }
        compose.waitForIdle()
        slider().assertIsNotEnabled()
        assertEquals(1, levels.size)
        compose.runOnIdle { pending.value = emptySet(); mode.value = NoiseControlMode.ADAPTIVE }
        compose.waitForIdle()
        position(0f); position(1f)
        assertEquals(listOf(NoiseControlMode.ADAPTIVE, NoiseControlMode.TRANSPARENCY, NoiseControlMode.NOISE_CANCELLATION), modes)
    }

    @Test fun backgroundCancelsDeferredModeStrengthBeforeItsReportArrives() {
        render(); position(.7f)
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.runOnUiThread { mode.value = NoiseControlMode.ADAPTIVE }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertTrue("Do not replay a deferred strength after leaving the foreground", levels.isEmpty())
    }

    @Test fun disconnectCancelsDeferredModeStrengthAcrossReconnect() {
        render(); position(.7f)
        compose.runOnIdle { connection.value = "disconnected" }
        compose.waitForIdle()
        compose.runOnIdle { connection.value = "connected"; mode.value = NoiseControlMode.ADAPTIVE }
        compose.waitForIdle()
        assertTrue("A disconnected session must not retain deferred writes", levels.isEmpty())
    }

    @Test fun switchingAwayFromHeadphonesCancelsDeferredStrength() {
        render(); position(.7f)
        compose.runOnIdle { active.value = false }
        compose.waitForIdle()
        compose.runOnIdle { active.value = true; mode.value = NoiseControlMode.ADAPTIVE }
        compose.waitForIdle()
        assertTrue(levels.isEmpty())
    }

    @Test fun releasedAdaptiveTargetStaysInPlaceUntilBothReportsArrive() {
        render(); position(.7f)
        fun remainsAtTarget() = assertEquals(.7f,
            slider().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .005f)
        remainsAtTarget()
        compose.runOnIdle {
            pending.value = setOf(PodsSettings.NOISE_MODE)
            feedback.value = mapOf(PodsSettings.NOISE_MODE to "sent")
        }
        compose.waitForIdle(); remainsAtTarget()
        compose.runOnIdle { mode.value = NoiseControlMode.ADAPTIVE; pending.value = emptySet() }
        compose.waitForIdle(); remainsAtTarget() // The old strength is not the release point.
        compose.runOnIdle { reportedLevel.value = 1f / 6f }
        compose.waitForIdle(); remainsAtTarget()
        assertEquals(listOf(NoiseControlMode.ADAPTIVE), modes)
        assertEquals(1, levels.size)
    }

    @Test fun endpointDoesNotRewindWhenPendingFeedbackDisablesTheSlider() {
        render(); position(0f)
        compose.runOnIdle {
            pending.value = setOf(PodsSettings.NOISE_MODE)
            feedback.value = mapOf(PodsSettings.NOISE_MODE to "sent")
        }
        compose.waitForIdle()
        slider().assertIsNotEnabled()
        assertEquals(0f, slider().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .001f)
        compose.runOnIdle { mode.value = NoiseControlMode.TRANSPARENCY; pending.value = emptySet() }
        compose.waitForIdle()
        assertEquals(0f, slider().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .001f)
    }

    @Test fun failedOrInterruptedRequestsReturnToTheReportAndAllowRetry() {
        render()
        for (status in listOf("write_failed", "write_failed", "timeout", "interrupted", "invalid")) {
            position(0f)
            compose.runOnIdle {
                feedback.value = mapOf(PodsSettings.NOISE_MODE to "sent")
                pending.value = setOf(PodsSettings.NOISE_MODE)
            }
            compose.waitForIdle()
            compose.runOnIdle { feedback.value = mapOf(PodsSettings.NOISE_MODE to status); pending.value = emptySet() }
            compose.waitForIdle()
            assertEquals(1f, slider().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .001f)
            slider().assertIsEnabled()
        }
        assertEquals(5, modes.size)
        assertTrue(levels.isEmpty())
    }

    @Test fun pendingVisualPositionIsDiscardedOnRecoveryDisconnectSessionAndPageExit() {
        render()
        val interruptions = listOf<() -> Unit>({ recovery.value = "recovering" }, { connection.value = "disconnected" },
            { session.value += 1 }, { active.value = false })
        for (interrupt in interruptions) {
            position(0f)
            assertEquals(0f, slider().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .001f)
            compose.runOnIdle(interrupt)
            compose.waitForIdle()
            compose.runOnIdle { recovery.value = ""; connection.value = "connected"; active.value = true }
            compose.waitForIdle()
            assertEquals(1f, slider().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .001f)
        }
        assertEquals(4, modes.size)
    }

    @Test fun unansweredEndpointRequestExpiresWithoutSendingAnotherCommand() {
        render()
        compose.mainClock.autoAdvance = false
        position(0f)
        compose.mainClock.advanceTimeBy(6400)
        assertEquals(0f, slider().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .001f)
        compose.mainClock.advanceTimeBy(400)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(1f, slider().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .001f)
        assertEquals(listOf(NoiseControlMode.TRANSPARENCY), modes)
    }

    @Test fun backgroundClearsTheVisualRequestBeforeResume() {
        render(); position(0f)
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertEquals(1f, slider().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .001f)
        assertEquals(listOf(NoiseControlMode.TRANSPARENCY), modes)
        assertTrue(levels.isEmpty())
    }

    @Test fun aStrengthWriteFailureDiscardsOnlyTheUnconfirmedVisualStrength() {
        mode.value = NoiseControlMode.ADAPTIVE
        render(); position(.65f)
        compose.runOnIdle { feedback.value = mapOf(Key.ADAPTIVE_AUDIO_LEVEL to "write_failed") }
        compose.waitForIdle()
        assertEquals(.5f, slider().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .001f)
        assertTrue(modes.isEmpty())
        assertEquals(.25f, levels.single(), .01f)
    }
}
