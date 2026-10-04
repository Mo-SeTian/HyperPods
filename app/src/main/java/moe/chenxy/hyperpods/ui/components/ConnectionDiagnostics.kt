package moe.chenxy.hyperpods.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.utils.HyperPodsBroadcasts
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import top.yukonga.miuix.kmp.basic.BasicComponent

/** Only fixed diagnostic fields are displayed/copied; no addresses, names, serials or keys. */
@Composable
fun ConnectionDiagnostics(snapshot: Bundle?) {
    val context = LocalContext.current
    val received = snapshot?.getInt("received") ?: 0
    val stage = if (snapshot?.getBoolean("ready") == true) R.string.diagnostics_ready else when (snapshot?.getString("stage")) {
        "HANDSHAKE" -> R.string.diagnostics_handshake
        "FEATURES" -> R.string.diagnostics_features
        "STATUS" -> R.string.diagnostics_status
        else -> R.string.diagnostics_no_session
    }
    val connection = when (snapshot?.getString("connection")) {
        "connected" -> R.string.dashboard_connected
        "connecting" -> R.string.diagnostics_connecting
        "failed" -> R.string.diagnostics_failed
        else -> R.string.diagnostics_no_session
    }
    val fields = listOf(
        stringResource(R.string.diagnostics_connection) to stringResource(connection),
        stringResource(R.string.diagnostics_stage) to stringResource(stage),
        stringResource(R.string.diagnostics_packets) to received.toString(),
        stringResource(R.string.diagnostics_retries) to (snapshot?.getInt("retries") ?: 0).toString(),
        stringResource(R.string.diagnostics_battery) to stringResource(if (snapshot?.getBoolean("battery") == true) R.string.diagnostics_received else R.string.settings_reading),
        stringResource(R.string.diagnostics_wear) to stringResource(if (snapshot?.getBoolean("wear") == true) R.string.diagnostics_received else R.string.settings_reading),
        stringResource(R.string.diagnostics_information) to stringResource(if (snapshot?.getBoolean("information") == true) R.string.diagnostics_received else R.string.settings_reading),
    )
    val explanation = when {
        snapshot == null -> R.string.diagnostics_no_report
        snapshot.getBoolean("ready") -> R.string.diagnostics_ready
        snapshot.getString("failure").orEmpty().isNotEmpty() -> R.string.diagnostics_failed_summary
        received == 0 -> R.string.diagnostics_no_packets
        else -> R.string.diagnostics_incomplete
    }
    BasicComponent(title = stringResource(R.string.diagnostics_title), summary = stringResource(explanation), enabled = false)
    fields.forEach { (label, value) -> BasicComponent(title = label, summary = value, enabled = false) }
    BasicComponent(title = stringResource(R.string.diagnostics_refresh), onClick = {
        HyperPodsBroadcasts.send(context, Intent(HyperPodsAction.ACTION_PODS_DIAGNOSTICS_REQUEST), HyperPodsBroadcasts.BLUETOOTH)
    })
    BasicComponent(title = stringResource(R.string.diagnostics_retry), summary = stringResource(R.string.diagnostics_retry_summary),
        enabled = snapshot?.getString("connection") == "connected", onClick = {
            HyperPodsBroadcasts.send(context, Intent(HyperPodsAction.ACTION_PODS_STATUS_RETRY), HyperPodsBroadcasts.BLUETOOTH)
        })
    BasicComponent(title = stringResource(R.string.diagnostics_copy), onClick = {
        val report = "HyperPods ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            fields.joinToString("\n") { (label, value) -> "$label: $value" } +
            "\nresult: ${snapshot?.getString("failure").orEmpty()}"
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("HyperPods", report))
        Toast.makeText(context, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
    })
}
