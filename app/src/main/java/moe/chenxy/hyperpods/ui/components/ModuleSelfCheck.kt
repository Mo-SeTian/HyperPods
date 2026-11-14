package moe.chenxy.hyperpods.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.utils.HyperPodsBroadcasts
import moe.chenxy.hyperpods.utils.ModuleStatus

@Composable
fun ModuleSelfCheck(onReport: (String) -> Unit) {
    val state = rememberModuleCheck()
    val reports = state.check.reports
    val waiting = state.check.checking
    FidelityText(stringResource(R.string.module_installed, BuildConfig.VERSION_CODE, BuildConfig.BUILD_REVISION),
        Modifier.padding(vertical = 12.dp), size = 12, color = Fidelity.muted())
    val fields = ModuleStatus.packages.map { host ->
        val report = reports[host]
        val title = stringResource(when (host) {
            HyperPodsBroadcasts.BLUETOOTH -> R.string.module_bluetooth
            HyperPodsBroadcasts.SYSTEM_UI -> R.string.module_system_ui
            else -> R.string.module_xiaomi_bluetooth
        })
        val summary = if (report == null) stringResource(if (waiting) R.string.module_checking else R.string.module_no_report)
        else stringResource(R.string.module_loaded, report.version, report.revision) + "\n" +
            stringResource(if (report.matchesInstalled) R.string.module_matches else R.string.module_restart) + "\n" +
            stringResource(when (report.detail) {
                "native_ok" -> R.string.module_native_ok
                "native_failed" -> R.string.module_native_failed
                "native_unknown" -> R.string.module_native_unknown
                "plugin_observed" -> R.string.module_plugin_observed
                "plugin_waiting" -> R.string.module_plugin_waiting
                "receiver_ready" -> R.string.module_receiver_ready
                else -> R.string.module_receiver_waiting
            })
        title to summary
    }
    fields.forEach { (title, summary) -> DiagnosticField(title, summary, stacked = true); FidelityDivider() }
    val reportText = fields.joinToString("\n") { (title, summary) -> "$title: $summary" }
    LaunchedEffect(reportText) { onReport(reportText) }
    MinimalLink(stringResource(R.string.module_check_again), titleColor = Fidelity.accent(), onClick = state::retry)
}
