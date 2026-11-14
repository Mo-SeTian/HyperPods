package moe.chenxy.hyperpods.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.utils.AACPManager
import moe.chenxy.hyperpods.utils.AirPodsModels
import moe.chenxy.hyperpods.utils.HyperPodsBroadcasts
import moe.chenxy.hyperpods.utils.ModuleStatus

/** Exercise About with explicit reports, independent of the debug preview screen. */
fun AndroidComposeTestRule<ActivityScenarioRule<DashboardPreviewActivity>, DashboardPreviewActivity>.renderAboutFixture(
    modelNumber: String = "A3049"
) {
    val info = AACPManager.Companion.AirPodsInformation(
        AirPodsModels.getModelByModelNumber(modelNumber)!!.name, modelNumber, "Apple Inc.",
        "DEMO-NOT-REAL", "0", "0", "1.0.0", "DEMO", "DEMO-LEFT", "DEMO-RIGHT", "9441861")
    val diagnostics = Bundle().apply { putString("connection", "connected"); putBoolean("ready", true) }
    val status = ModuleStatus.Check(ModuleStatus.packages.associateWith {
        ModuleStatus.Report(it, BuildConfig.VERSION_CODE, BuildConfig.BUILD_REVISION,
            if (it == HyperPodsBroadcasts.BLUETOOTH) "native_ok"
            else if (it == HyperPodsBroadcasts.SYSTEM_UI) "plugin_observed" else "receiver_ready")
    }, checking = false)
    runOnUiThread { activity.setContent { AppTheme(1) {
        AboutPage(PaddingValues(), podsInfo = info, diagnostics = diagnostics, moduleStatus = status)
    } } }
    waitForIdle()
}
