package moe.chenxy.hyperpods.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.utils.HyperPodsBroadcasts
import moe.chenxy.hyperpods.utils.ModuleStatus
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import top.yukonga.miuix.kmp.basic.BasicComponent
import java.util.UUID

@Composable
fun ModuleSelfCheck(onReport: (String) -> Unit) {
    val context = LocalContext.current
    var request by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var reports by remember { mutableStateOf(emptyMap<String, ModuleStatus.Report>()) }
    var waiting by remember { mutableStateOf(true) }
    DisposableEffect(context, request) {
        reports = emptyMap()
        waiting = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                ModuleStatus.read(context, this, intent, request)?.let { reports = reports + (it.packageName to it) }
            }
        }
        context.registerReceiver(receiver, IntentFilter(HyperPodsAction.ACTION_MODULE_STATUS), Context.RECEIVER_EXPORTED)
        ModuleStatus.packages.forEach { host ->
            HyperPodsBroadcasts.send(context, Intent(HyperPodsAction.ACTION_MODULE_STATUS_REQUEST).putExtra("request", request), host)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }
    LaunchedEffect(request) { delay(2500); waiting = false }
    BasicComponent(title = stringResource(R.string.module_self_check),
        summary = stringResource(R.string.module_installed, BuildConfig.VERSION_CODE, BuildConfig.BUILD_REVISION), enabled = false)
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
    fields.forEach { (title, summary) -> BasicComponent(title = title, summary = summary, enabled = false) }
    val reportText = fields.joinToString("\n") { (title, summary) -> "$title: $summary" }
    LaunchedEffect(reportText) { onReport(reportText) }
    BasicComponent(title = stringResource(R.string.module_check_again), onClick = { request = UUID.randomUUID().toString() })
}
