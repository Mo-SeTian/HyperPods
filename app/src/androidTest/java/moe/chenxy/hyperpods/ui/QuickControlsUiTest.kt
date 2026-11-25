package moe.chenxy.hyperpods.ui

import android.animation.ValueAnimator
import android.app.PendingIntent
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import moe.chenxy.hyperpods.MainActivity
import moe.chenxy.hyperpods.QuickControlsActivity
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.pods.NoiseControlMode
import moe.chenxy.hyperpods.ui.components.ListeningSlider
import moe.chenxy.hyperpods.ui.components.rememberListeningCommit
import moe.chenxy.hyperpods.utils.AirPodsPro2USBC
import moe.chenxy.hyperpods.utils.ListeningPosition
import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.PodBatteryParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class QuickControlsUiTest {
    @get:Rule val compose = createAndroidComposeRule<DashboardPreviewActivity>()

    @Test fun pendingIntentOpensFloatingActivityAndBackFinishesAfterExit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(QuickControlsActivity::class.java.name, null, false)
        val launch = QuickControlsActivity.notificationIntent("02:00:00:00:00:01")
        val other = QuickControlsActivity.notificationIntent("02:00:00:00:00:02")
        assertNotEquals(launch.identifier, other.identifier)
        assertTrue(launch.flags and Intent.FLAG_ACTIVITY_NO_ANIMATION != 0)
        compose.runOnIdle {
            PendingIntent.getActivity(compose.activity, 0, launch, PendingIntent.FLAG_IMMUTABLE).send()
        }
        try {
            val activity = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
            assertNotNull(activity)
            compose.onNodeWithText(activity.getString(R.string.fidelity_more)).assertExists()
            compose.waitUntil(5000) { activity.window.attributes.alpha == 1f }
            val popup = activity as QuickControlsActivity
            instrumentation.runOnMainSync {
                popup.onBackPressedDispatcher.onBackPressed()
                if (ValueAnimator.areAnimatorsEnabled()) assertFalse(popup.isFinishing)
            }
            compose.waitUntil(5000) { popup.isFinishing }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun compactSliderWaitsForTheFirstModeReportBeforeHighlighting() {
        val mode = mutableStateOf<NoiseControlMode?>(null)
        compose.runOnUiThread { compose.activity.setContent { AppTheme(colorMode = 1) {
            ListeningSlider(mode.value, .5f, true, mode.value != null, null, {},
                available = true, compact = true)
        } } }
        val context = compose.activity
        val slider = compose.onNodeWithContentDescription(context.getString(R.string.redesign_slider))
        val thumb = compose.onNodeWithTag("listening-thumb", useUnmergedTree = true)
        val waitingText = compose.onNodeWithText(context.getString(R.string.settings_reading))

        slider.assertIsNotEnabled().assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetProgress))
        thumb.assertDoesNotExist()
        waitingText.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { read ->
            val results = mutableListOf<TextLayoutResult>()
            assertTrue(read(results))
            assertEquals(Color(0xFF657185), results.single().layoutInput.style.color)
        }
        val pixels = slider.captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2]
        assertEquals(center.red, center.blue, .03f)

        compose.runOnIdle { mode.value = NoiseControlMode.ADAPTIVE }
        slider.assertIsEnabled()
        thumb.assertIsDisplayed()
        waitingText.assertDoesNotExist()

        compose.runOnIdle { mode.value = null }
        slider.assertIsNotEnabled()
        thumb.assertDoesNotExist()
        waitingText.assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun blueCapsuleMovesAndChangesItsWhiteIconWithTheMode() {
        val mode = mutableStateOf(NoiseControlMode.TRANSPARENCY)
        val level = mutableStateOf(.25f)
        compose.runOnUiThread { compose.activity.setContent { AppTheme(colorMode = 1) {
            ListeningSlider(mode.value, level.value, true, true, null, {}, compact = true)
        } } }
        val thumb = compose.onNodeWithTag("listening-thumb", useUnmergedTree = true)

        val positions = mutableListOf<Float>()
        val icons = mutableSetOf<List<Boolean>>()
        for (selected in listOf(NoiseControlMode.TRANSPARENCY, NoiseControlMode.ADAPTIVE,
            NoiseControlMode.NOISE_CANCELLATION)) {
            compose.runOnIdle { mode.value = selected }
            positions += thumb.fetchSemanticsNode().boundsInRoot.center.x
            val pixels = thumb.captureToImage().toPixelMap()
            val bluePixels = (0 until pixels.width).count { x ->
                val color = pixels[x, pixels.height / 2]
                color.red < .1f && color.green in .3f.. .65f && color.blue > .9f
            }
            assertTrue("Expected a blue capsule behind the $selected icon", bluePixels > pixels.width / 2)
            val whiteShape = (pixels.height / 4 until pixels.height * 3 / 4).flatMap { y ->
                (pixels.width / 4 until pixels.width * 3 / 4).map { x ->
                    val color = pixels[x, y]
                    color.red > .98f && color.green > .98f && color.blue > .98f
                }
            }
            assertTrue("Expected a white $selected icon", whiteShape.any { it })
            assertTrue("Each mode should have its own icon", icons.add(whiteShape))
        }
        assertTrue(positions[0] < positions[1] && positions[1] < positions[2])

        compose.runOnIdle { mode.value = NoiseControlMode.ADAPTIVE }
        assertEquals(positions[1], thumb.fetchSemanticsNode().boundsInRoot.center.x, 1f)
        compose.runOnIdle { level.value = .75f }
        val strongerPosition = thumb.fetchSemanticsNode().boundsInRoot.center.x
        assertTrue("Higher strength moves toward transparency inside the adaptive range",
            strongerPosition < positions[1] && strongerPosition > positions[0])
    }

    @Test fun adaptiveWaitsForModeWriteAndUsesTheSelectedStrength() {
        val modeWritten = mutableStateOf(false)
        val pendingWrites = mutableListOf<NoiseControlMode>()
        val strengths = mutableListOf<Float>()
        compose.runOnUiThread { compose.activity.setContent { AppTheme {
            val commit = rememberListeningCommit(1, NoiseControlMode.NOISE_CANCELLATION, true, true,
                true, true, modeWritten.value, null, pendingWrites::add, strengths::add)
            ListeningSlider(NoiseControlMode.NOISE_CANCELLATION, .5f, true, !commit.waitingForAdaptive,
                null, commit.apply, available = true, compact = true)
        } } }
        val slider = compose.onNodeWithContentDescription(compose.activity.getString(R.string.redesign_slider))
        val position = .59f
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(position) }
        compose.runOnIdle {
            assertEquals(listOf(NoiseControlMode.ADAPTIVE), pendingWrites)
            assertEquals(emptyList<Float>(), strengths)
            modeWritten.value = true
        }
        compose.runOnIdle { assertEquals(listOf(ListeningPosition.adaptiveLevel(position)), strengths) }
    }

    @Test fun homepageTogglesDispatchIndependentlyFromMoreSettings() {
        val personalized = mutableStateOf(true)
        val conversation = mutableStateOf(false)
        val enabled = mutableStateOf(false)
        var opened = 0
        compose.runOnUiThread { compose.activity.setContent { AppTheme {
            QuickControlsPanel("AirPods Pro", AirPodsPro2USBC(), true, true,
                BatteryParams(PodBatteryParams(92, isConnected = true), PodBatteryParams(87, isConnected = true)),
                maxHeight = 640.dp, onMoreSettings = { opened++ }, toggles = {
                    QuickControlsToggle(QuickToggle.PERSONALIZED_VOLUME, personalized.value, true) { personalized.value = it }
                    QuickControlsToggle(QuickToggle.CONVERSATION_AWARENESS, conversation.value, enabled.value) { conversation.value = it }
                }) {
                ListeningSlider(NoiseControlMode.ADAPTIVE, .5f, true, true, null, {},
                    containerColor = Color.Transparent, contentPadding = PaddingValues(vertical = 16.dp), compact = true)
            }
        } } }
        val activity = compose.activity
        val first = compose.onNodeWithContentDescription(activity.getString(R.string.personlized_volume_title))
        val second = compose.onNodeWithContentDescription(activity.getString(R.string.dashboard_conversation_title))
        compose.onNodeWithContentDescription(activity.getString(R.string.redesign_slider)).assertIsEnabled()
        val firstBounds = first.fetchSemanticsNode().boundsInRoot
        val secondBounds = second.fetchSemanticsNode().boundsInRoot
        assertTrue(firstBounds.right < secondBounds.left)
        assertEquals(firstBounds.top, secondBounds.top, 1f)
        first.assertIsOn().performClick().assertIsOff()
        second.assertIsNotEnabled().assertIsOff()
        compose.runOnIdle { enabled.value = true }
        second.assertIsEnabled().performClick().assertIsOn()
        first.assertIsOff()
        compose.runOnIdle { assertEquals(0, opened) }
        compose.onNodeWithText(activity.getString(R.string.fidelity_more)).performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun caseBatteryIsCenteredAndOnlyShownForValidReports() {
        val battery = mutableStateOf(BatteryParams(
            PodBatteryParams(92, isConnected = true), PodBatteryParams(87, isConnected = true),
            PodBatteryParams(73, isConnected = true)))
        compose.runOnUiThread { compose.activity.setContent { AppTheme(colorMode = 1) {
            QuickControlsPanel("AirPods Pro", AirPodsPro2USBC(), true, true, battery.value, 640.dp,
                onMoreSettings = {}, toggles = {
                    QuickControlsToggle(QuickToggle.PERSONALIZED_VOLUME, true, true) {}
                }) {
                ListeningSlider(NoiseControlMode.ADAPTIVE, .5f, true, true, null, {},
                    containerColor = Color.Transparent, contentPadding = PaddingValues(vertical = 16.dp), compact = true)
            }
        } } }
        val context = compose.activity
        fun percent(value: Int) = context.getString(R.string.quick_battery_percent, value)
        val left = percent(92)
        val case = percent(73)
        val right = percent(87)
        val caseLabel = context.getString(R.string.quick_case)
        val centers = listOf(left, case, right).map { text ->
            compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.center
        }
        assertTrue(centers[0].x < centers[1].x && centers[1].x < centers[2].x)
        assertEquals((centers[0].x + centers[2].x) / 2, centers[1].x, 2f)
        assertEquals(centers[0].y, centers[1].y, 2f)
        val labelBounds = compose.onNodeWithText(caseLabel, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(centers[1].x, labelBounds.center.x, 2f)
        assertTrue(labelBounds.top > centers[1].y)
        mapOf(case to Color(0xFF192332), caseLabel to Color(0xFF4D596A),
            context.getString(R.string.dashboard_transparency) to Color(0xFF4D596A),
            context.getString(R.string.personlized_volume_title) to Color(0xFF4D596A),
            context.getString(R.string.fidelity_more) to Color(0xFF0068D6)).forEach { (text, color) ->
            compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { read ->
                val results = mutableListOf<TextLayoutResult>()
                assertTrue(read(results))
                assertEquals(color, results.single().layoutInput.style.color)
            }
        }
        compose.runOnIdle { battery.value = battery.value.copy(case = PodBatteryParams(101, isConnected = true)) }
        compose.onNodeWithText(caseLabel).assertDoesNotExist()
        compose.onNodeWithText(left, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(right, useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { battery.value = battery.value.copy(case = PodBatteryParams(0, isConnected = true)) }
        compose.onNodeWithText(percent(0), useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { battery.value = battery.value.copy(case = PodBatteryParams(73, isConnected = false)) }
        compose.onNodeWithText(caseLabel).assertDoesNotExist()
        compose.onNodeWithText(left, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(right, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun compactHeaderAndSwitchesStaySideBySideAtLargeTextSize() {
        val activity = compose.activity
        compose.runOnUiThread { activity.setContent { AppTheme(colorMode = 1) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.6f)) {
                Box(Modifier.width(272.dp)) {
                    QuickControlsPanel("AirPods Pro", AirPodsPro2USBC(), true, true,
                        BatteryParams(PodBatteryParams(92, isConnected = true), PodBatteryParams(87, isConnected = true),
                            PodBatteryParams(73, isConnected = true)), 640.dp, onMoreSettings = {}, toggles = {
                            QuickControlsToggle(QuickToggle.PERSONALIZED_VOLUME, true, true) {}
                            QuickControlsToggle(QuickToggle.CONVERSATION_AWARENESS, true, true) {}
                        }) {
                        ListeningSlider(NoiseControlMode.ADAPTIVE, .5f, true, true, null, {},
                            containerColor = Color.Transparent, contentPadding = PaddingValues(vertical = 6.dp), compact = true)
                        }
                }
            }
        } } }
        val name = compose.onNodeWithText("AirPods Pro").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val state = compose.onNodeWithText(activity.getString(R.string.dashboard_connected)).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue(name.right < state.left)
        assertEquals(name.center.y, state.center.y, 2f)
        val switches = listOf(R.string.personlized_volume_title, R.string.dashboard_conversation_title).map { title ->
            compose.onNodeWithContentDescription(activity.getString(title)).assertIsDisplayed().assertIsEnabled()
                .fetchSemanticsNode().boundsInRoot
        }
        assertTrue(switches[0].right < switches[1].left)
        assertEquals(switches[0].top, switches[1].top, 1f)
        compose.onNodeWithText(activity.getString(R.string.fidelity_more)).assertIsDisplayed()
    }

    @Test fun homeRequestLeavesTheOpenSettingsPage() {
        val activity = compose.activity
        val more = activity.getString(R.string.fidelity_more)
        val wear = activity.getString(R.string.minimal_wear_controls)
        val listening = activity.getString(R.string.redesign_slider)
        compose.revealSetting(more).performClick()
        compose.revealSetting(wear).assertIsDisplayed()
        compose.runOnIdle { activity.homeRequested = true }
        compose.revealSetting(hasContentDescription(listening)).assertIsDisplayed()
        compose.onNodeWithText(wear).assertDoesNotExist()
    }

    @Test fun moreSettingsButtonOpensTheAppHome() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val popupMonitor = instrumentation.addMonitor(QuickControlsActivity::class.java.name, null, false)
        val appMonitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            compose.runOnIdle {
                PendingIntent.getActivity(compose.activity, 0,
                    QuickControlsActivity.notificationIntent("02:00:00:00:00:01"), PendingIntent.FLAG_IMMUTABLE).send()
            }
            val popup = instrumentation.waitForMonitorWithTimeout(popupMonitor, 5000)
            assertNotNull(popup)
            compose.onNodeWithText(popup.getString(R.string.fidelity_more)).performClick()
            val activity = instrumentation.waitForMonitorWithTimeout(appMonitor, 5000)
            assertNotNull(activity)
            val home = compose.onNodeWithText(activity.getString(R.string.dashboard_headphones))
            compose.waitUntil(5000) { home.isDisplayed() }
            home.assertIsSelected()
            compose.onNodeWithText(activity.getString(R.string.minimal_wear_controls)).assertDoesNotExist()
            instrumentation.runOnMainSync { activity.finish() }
        } finally {
            instrumentation.removeMonitor(popupMonitor)
            instrumentation.removeMonitor(appMonitor)
        }
    }
}
