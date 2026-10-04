package moe.chenxy.hyperpods.ui

import android.content.ClipboardManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.pods.BatteryStatus
import moe.chenxy.hyperpods.utils.AirPodsPro2USBC
import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.EarDetectionParams
import moe.chenxy.hyperpods.utils.data.PodBatteryParams
import moe.chenxy.hyperpods.ui.components.ConnectionDiagnostics
import moe.chenxy.hyperpods.ui.components.DashboardBattery
import moe.chenxy.hyperpods.ui.components.ModuleSelfCheck
import moe.chenxy.hyperpods.ui.components.dashboardConnectionStatus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Synthetic status only: never connects to hardware or writes headset settings. */
class ConnectionDiagnosticsTest {
    @get:Rule val compose = createAndroidComposeRule<DashboardPreviewActivity>()

    @Test fun controlFailureIsNotMistakenForBluetoothAudioDisconnection() {
        val status = Bundle().apply { putString("connection", "failed") }
        assertEquals(R.string.diagnostics_audio_control_failed, dashboardConnectionStatus(status))
        status.putString("connection", "connected")
        assertEquals(R.string.diagnostics_audio_syncing, dashboardConnectionStatus(status))
        status.putBoolean("ready", true)
        assertEquals(R.string.dashboard_connected, dashboardConnectionStatus(status))
        assertEquals(R.string.diagnostics_audio_no_control_report, dashboardConnectionStatus(null))
    }

    @Test fun pendingEarHasACacheLabelAndConnectedCaseIsVisible() {
        val at = 1000L
        val status = Bundle().apply {
            putString("connection", "connected")
            putBoolean("wear", true)
            putLong("wear_report_at", at)
            putLong("left_battery_at", at)
            putLong("right_battery_at", at)
            putLong("case_battery_at", at)
        }
        compose.runOnUiThread {
            compose.activity.setContent {
                AppTheme(1) {
                    DashboardBattery(BatteryParams(
                        PodBatteryParams(100, false, true, BatteryStatus.NOT_CHARGING),
                        PodBatteryParams(85, false, true, BatteryStatus.NEED_AGAIN),
                        PodBatteryParams(91, true, true, BatteryStatus.CHARGING)),
                        EarDetectionParams(0, 1), AirPodsPro2USBC(), status)
                }
            }
        }
        compose.onNodeWithText("100%").assertIsDisplayed()
        compose.onNodeWithText("85%").assertIsDisplayed()
        compose.onNodeWithText("91%").assertIsDisplayed()
        val formatted = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))
        compose.onNodeWithText(compose.activity.getString(R.string.freshness_retained, formatted)).assertIsDisplayed()
    }

    @Test fun absentProcessResponseIsInformationalAndNotAnInactiveModuleVerdict() {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            compose.activity.setContent {
                AppTheme(1) { Column { ModuleSelfCheck {} } }
            }
        }
        compose.mainClock.advanceTimeBy(3500)
        compose.waitForIdle()
        compose.onAllNodesWithText(compose.activity.getString(R.string.module_no_report)).assertCountEquals(3)
    }

    @Test fun copyingADiagnosticReportIgnoresSensitiveExtraFields() {
        val status = Bundle().apply {
            putString("connection", "connected")
            putString("device_name", "PRIVATE_SENTINEL")
            putString("mac", "PRIVATE_SENTINEL")
            putString("serial", "PRIVATE_SENTINEL")
            putString("packet", "PRIVATE_SENTINEL")
        }
        compose.runOnUiThread {
            compose.activity.setContent {
                AppTheme(1) { Column(Modifier.verticalScroll(rememberScrollState())) { ConnectionDiagnostics(status) } }
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.diagnostics_copy)).performScrollTo().performClick()
        compose.runOnIdle {
            val report = compose.activity.getSystemService(ClipboardManager::class.java).primaryClip
                ?.getItemAt(0)?.text?.toString()
            assertNotNull(report)
            assertTrue(report!!.contains("HyperPods"))
            assertFalse(report.contains("PRIVATE_SENTINEL"))
        }
    }
}
