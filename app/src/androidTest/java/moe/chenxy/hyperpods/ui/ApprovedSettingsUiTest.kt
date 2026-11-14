package moe.chenxy.hyperpods.ui

import android.os.Bundle
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.ui.components.ConnectionDiagnostics
import moe.chenxy.hyperpods.ui.components.DeviceHero
import moe.chenxy.hyperpods.utils.AirPodsPro2USBC
import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.EarDetectionParams
import moe.chenxy.hyperpods.utils.HyperPodsBroadcasts
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import moe.chenxy.hyperpods.utils.data.HyperPodsPrefsKey as Key
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Geometry and operation regressions for the approved four-screen proposal. */
class ApprovedSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<DashboardPreviewActivity>()
    private fun text(id: Int) = compose.activity.getString(id)
    private fun screenBounds(label: String, description: Boolean = false): Rect {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if ((if (description) node.contentDescription else node.text)?.toString() == label) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { find(it)?.let { result -> return result } }
            return null
        }
        var result: Rect? = null
        compose.waitUntil(5000) {
            automation.rootInActiveWindow?.let { root -> find(root)?.let { node ->
                var action = node
                while (!action.isClickable && action.parent != null) action = action.parent
                result = Rect().also(action::getBoundsInScreen)
            } }
            result != null
        }
        return result!!
    }

    @Test fun homeLinksUseTheSameRowHeightAndHaveNoExtraSectionGap() {
        compose.revealSetting(text(R.string.fidelity_more))
        val detection = compose.onNodeWithContentDescription(text(R.string.fidelity_detection)).fetchSemanticsNode().boundsInRoot
        val eq = compose.onNodeWithText(text(R.string.eq_title)).fetchSemanticsNode().boundsInRoot
        val more = compose.onNodeWithText(text(R.string.fidelity_more)).fetchSemanticsNode().boundsInRoot
        val tolerance = compose.activity.resources.displayMetrics.density * 2
        assertEquals(detection.height, eq.height, tolerance)
        assertEquals(eq.height, more.height, tolerance)
        assertTrue("No extra gap above Equalizer", eq.top - detection.bottom <= tolerance)
        assertEquals(detection.left, eq.left, 1f)
        assertEquals(eq.left, more.left, 1f)
    }

    @Test fun deviceMenuAnchorsBelowTheButtonAndAlignsBothActionRows() {
        val trigger = screenBounds(text(R.string.fidelity_device_options), description = true)
        compose.onNodeWithContentDescription(text(R.string.fidelity_device_options)).performClick()
        compose.waitForIdle()
        val bluetooth = screenBounds(text(R.string.redesign_bluetooth))
        val rename = screenBounds(text(R.string.rename_title))
        val density = compose.activity.resources.displayMetrics.density
        assertEquals("Both menu actions share one inset", rename.left.toFloat(), bluetooth.left.toFloat(), 2f)
        assertEquals("Popup content ends 16dp inside the trigger's right edge", trigger.right - 16 * density, bluetooth.right.toFloat(), 3f)
        assertTrue("Menu must appear below its trigger", rename.top > trigger.bottom)
    }

    @Test fun aboutHasOneRetryButtonBelowModuleStatusAndAboveDeviceInfo() {
        compose.renderAboutFixture()
        val status = compose.onNodeWithText(text(R.string.fidelity_module_active)).fetchSemanticsNode().boundsInRoot
        val retry = compose.onNodeWithText(text(R.string.diagnostics_retry)).assertIsEnabled().fetchSemanticsNode().boundsInRoot
        val info = compose.onNodeWithText(text(R.string.dashboard_device_info)).fetchSemanticsNode().boundsInRoot
        assertTrue(retry.top > status.bottom)
        assertTrue(retry.bottom < info.top)
        compose.onAllNodesWithText(text(R.string.diagnostics_retry)).assertCountEquals(1)
    }

    @Test fun shortDiagnosticValuesShareABaselineAndRightContentEdge() {
        compose.runOnUiThread { compose.activity.setContent { AppTheme(1) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ConnectionDiagnostics(Bundle().apply { putInt("received", 128) })
            }
        } } }
        compose.onNodeWithText(text(R.string.diagnostics_packets), useUnmergedTree = true).performScrollTo()
        val label = compose.onNodeWithText(text(R.string.diagnostics_packets), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val value = compose.onNodeWithText("128", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(label.center.y, value.center.y, 1f)
        val width = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        assertEquals(width - 24 * compose.activity.resources.displayMetrics.density, value.right, 2f)
    }

    @Test fun retryUsesTheExistingBroadcastAndRespectsBusyAndUnavailableStates() {
        val sent = mutableListOf<Intent>()
        val status = mutableStateOf(Bundle().apply { putString("connection", "failed") })
        val context = object : ContextWrapper(compose.activity) {
            override fun sendBroadcast(intent: Intent, receiverPermission: String?, options: Bundle?) { sent.add(Intent(intent)) }
        }
        compose.runOnUiThread { compose.activity.setContent { AppTheme(1) {
            CompositionLocalProvider(LocalContext provides context) { AboutPage(PaddingValues(), diagnostics = status.value) }
        } } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(text(R.string.diagnostics_retry)).assertIsEnabled().performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(text(R.string.minimal_syncing)).assertIsNotEnabled()
        assertEquals(listOf(HyperPodsAction.ACTION_PODS_STATUS_RETRY), sent.map { it.action })
        assertEquals(HyperPodsBroadcasts.BLUETOOTH, sent.single().`package`)
        compose.runOnIdle { status.value = Bundle().apply { putString("connection", "connected"); putString("recovery", "recovering") } }
        compose.mainClock.advanceTimeBy(1100)
        compose.onNodeWithText(text(R.string.minimal_syncing)).assertIsNotEnabled()
        compose.runOnIdle { status.value = Bundle().apply { putString("connection", "failed") } }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(text(R.string.diagnostics_retry)).assertIsEnabled()
        compose.runOnIdle { status.value = Bundle().apply { putString("connection", "disconnected") } }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(text(R.string.diagnostics_retry)).assertIsNotEnabled()
        assertEquals(1, sent.size)
        compose.mainClock.autoAdvance = true
    }

    @Test fun longDiagnosticListsStackWithinTheContentAtDoubleFontSize() {
        val status = Bundle().apply {
            putStringArrayList("unconfirmed_settings", arrayListOf(Key.ADAPTIVE_AUDIO_LEVEL, Key.CHIME_VOLUME, Key.SINGLE_POD_ANC))
            putString("connection", "connected")
            putInt("received", 128)
        }
        compose.runOnUiThread { compose.activity.setContent { AppTheme(1) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                Box(Modifier.width(320.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { ConnectionDiagnostics(status) }
                }
            }
        } } }
        val title = text(R.string.diagnostics_unconfirmed_settings)
        val value = listOf(R.string.adaptive_audio_title, R.string.control_chime_volume, R.string.noise_cancellation_single_airpod)
            .joinToString("、") { text(it) }
        compose.onNodeWithText(value, useUnmergedTree = true).performScrollTo()
        val label = compose.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val detail = compose.onNodeWithText(value, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(label.left, detail.left, 1f)
        assertTrue(detail.top >= label.bottom)
        assertTrue(detail.right <= 296 * compose.activity.resources.displayMetrics.density + 1)
        compose.onAllNodesWithText(text(R.string.diagnostics_retry)).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.diagnostics_share)).performScrollTo().assertIsEnabled()
        compose.onNodeWithText(text(R.string.module_check_again)).performScrollTo().assertIsEnabled()
    }

    @Test fun largeFontDeviceMenuStaysAnchoredWithALongDeviceName() {
        compose.runOnUiThread { compose.activity.setContent { AppTheme(1) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                Box(Modifier.width(320.dp)) {
                    DeviceHero("我的 AirPods Pro 耳机设备", AirPodsPro2USBC(), BatteryParams(), EarDetectionParams(), null, false, {})
                }
            }
        } } }
        compose.waitForIdle()
        val trigger = screenBounds(text(R.string.fidelity_device_options), description = true)
        compose.onNodeWithContentDescription(text(R.string.fidelity_device_options)).performClick()
        val item = screenBounds(text(R.string.redesign_bluetooth))
        assertEquals(trigger.right - 16 * compose.activity.resources.displayMetrics.density, item.right.toFloat(), 3f)
        assertTrue(item.top > trigger.bottom)
        assertTrue(item.left >= 0)
    }
}
