package moe.chenxy.hyperpods.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.utils.HyperPodsBroadcasts
import moe.chenxy.hyperpods.utils.DiagnosticsHistory
import moe.chenxy.hyperpods.utils.DiagnosticEvents
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import moe.chenxy.hyperpods.utils.data.HyperPodsPrefsKey as Key
import moe.chenxy.hyperpods.utils.PodsSettings
import top.yukonga.miuix.kmp.basic.BasicComponent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Only fixed diagnostic fields are displayed/copied; no addresses, names, serials or keys. */
@Composable
fun ConnectionDiagnostics(snapshot: Bundle?) {
    val context = LocalContext.current
    val preferences = remember(context) { DiagnosticsHistory.preferences(context) }
    var history by remember(preferences) { mutableStateOf(DiagnosticsHistory.read(preferences)) }
    var events by remember(preferences) { mutableStateOf(DiagnosticsHistory.events(preferences)) }
    var moduleReport by remember { mutableStateOf("") }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            history = DiagnosticsHistory.read(preferences)
            events = DiagnosticsHistory.events(preferences)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        history = DiagnosticsHistory.read(preferences)
        events = DiagnosticsHistory.events(preferences)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val missingTime = stringResource(R.string.diagnostics_never)
    fun time(at: Long?): String = if (at != null && at > 0)
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(at)) else missingTime
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
        stringResource(R.string.diagnostics_audio) to stringResource(when {
            snapshot == null -> R.string.module_no_report
            snapshot.getString("connection") == "disconnected" -> R.string.diagnostics_no_session
            else -> R.string.diagnostics_audio_connected
        }),
        stringResource(R.string.diagnostics_connection) to stringResource(connection),
        stringResource(R.string.diagnostics_stage) to stringResource(stage),
        stringResource(R.string.diagnostics_packets) to received.toString(),
        stringResource(R.string.diagnostics_retries) to (snapshot?.getInt("retries") ?: 0).toString(),
        stringResource(R.string.diagnostics_last_packet) to time(snapshot?.getLong("last_packet_at")),
        stringResource(R.string.diagnostics_battery) to (stringResource(when {
            snapshot?.getBoolean("battery") != true -> R.string.settings_reading
            snapshot.getString("connection") == "failed" -> R.string.diagnostics_retained
            else -> R.string.diagnostics_received
        }) + "\n" + time(snapshot?.getLong("battery_at"))),
        stringResource(R.string.diagnostics_wear) to (stringResource(when {
            snapshot?.getBoolean("wear") != true -> R.string.settings_reading
            snapshot.getString("connection") == "failed" -> R.string.diagnostics_retained
            else -> R.string.diagnostics_received
        }) + "\n" + time(snapshot?.getLong("wear_at"))),
        stringResource(R.string.diagnostics_information) to (stringResource(if (snapshot?.getBoolean("information") == true) R.string.diagnostics_received else R.string.settings_reading) + "\n" + time(snapshot?.getLong("information_at"))),
        stringResource(R.string.diagnostics_settings_time) to time(snapshot?.getLong("settings_at")),
        stringResource(R.string.diagnostics_left_update) to time(snapshot?.getLong("left_battery_at")),
        stringResource(R.string.diagnostics_right_update) to time(snapshot?.getLong("right_battery_at")),
        stringResource(R.string.diagnostics_case_update) to time(snapshot?.getLong("case_battery_at")),
        stringResource(R.string.diagnostics_wear_update) to time(snapshot?.getLong("wear_report_at")),
        stringResource(R.string.diagnostics_missing_settings) to (snapshot?.getInt("missing_settings") ?: 0).toString(),
        stringResource(R.string.diagnostics_last_setting) to (stringResource(settingTitle(snapshot?.getString("setting_key"))) + "\n" + stringResource(settingStatus(snapshot?.getString("setting_status")))),
        stringResource(R.string.diagnostics_failure) to stringResource(failureReason(snapshot?.getString("failure"))),
    )
    val historyReport = history?.let {
        listOf(
            stringResource(R.string.diagnostics_history) to time(it.getLong("recorded_at")),
            stringResource(R.string.diagnostics_failure) to stringResource(failureReason(it.getString("failure"))),
            stringResource(R.string.diagnostics_stage) to stringResource(when (it.getString("stage")) {
                "HANDSHAKE" -> R.string.diagnostics_handshake
                "FEATURES" -> R.string.diagnostics_features
                "STATUS" -> R.string.diagnostics_status
                else -> R.string.diagnostics_no_session
            }),
            stringResource(R.string.diagnostics_packets) to it.getInt("received").toString(),
            stringResource(R.string.diagnostics_retries) to it.getInt("retries").toString(),
            stringResource(R.string.diagnostics_last_packet) to time(it.getLong("last_packet_at")),
            stringResource(R.string.diagnostics_last_setting) to stringResource(settingTitle(it.getString("setting_key"))),
        ).joinToString("\n") { (label, value) -> "$label: $value" }
    }
    val explanation = when {
        snapshot == null -> R.string.diagnostics_no_report
        snapshot.getBoolean("ready") -> R.string.diagnostics_ready
        snapshot.getString("failure").orEmpty().isNotEmpty() -> R.string.diagnostics_failed_summary
        received == 0 -> R.string.diagnostics_no_packets
        else -> R.string.diagnostics_incomplete
    }
    ModuleSelfCheck { moduleReport = it }
    BasicComponent(title = stringResource(R.string.diagnostics_title), summary = stringResource(explanation), enabled = false)
    fields.forEach { (label, value) -> BasicComponent(title = label, summary = value, enabled = false) }
    BasicComponent(title = stringResource(R.string.diagnostics_history),
        summary = historyReport ?: stringResource(R.string.diagnostics_no_history), enabled = false)
    val timeline = events.map { event -> time(event.at) + " · " + eventSummary(event) }
        .joinToString("\n").ifEmpty { stringResource(R.string.diagnostics_no_events) }
    BasicComponent(title = stringResource(R.string.diagnostics_timeline), summary = timeline, enabled = false)
    val report = "HyperPods ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_REVISION})\n" +
        moduleReport + "\n\n" + fields.joinToString("\n") { (label, value) -> "$label: $value" } +
        "\n\n" + (historyReport ?: context.getString(R.string.diagnostics_no_history)) + "\n\n" + timeline
    BasicComponent(title = stringResource(R.string.diagnostics_refresh), onClick = {
        HyperPodsBroadcasts.send(context, Intent(HyperPodsAction.ACTION_PODS_DIAGNOSTICS_REQUEST), HyperPodsBroadcasts.BLUETOOTH)
    })
    BasicComponent(title = stringResource(R.string.diagnostics_retry), summary = stringResource(R.string.diagnostics_retry_summary),
        enabled = snapshot?.getString("connection") in listOf("connected", "failed"), onClick = {
            HyperPodsBroadcasts.send(context, Intent(HyperPodsAction.ACTION_PODS_STATUS_RETRY), HyperPodsBroadcasts.BLUETOOTH)
        })
    BasicComponent(title = stringResource(R.string.diagnostics_copy), onClick = {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("HyperPods", report))
        Toast.makeText(context, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
    })
    BasicComponent(title = stringResource(R.string.diagnostics_share), summary = stringResource(R.string.diagnostics_share_privacy), onClick = {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "HyperPods diagnostics")
            putExtra(Intent.EXTRA_TEXT, report)
        }, context.getString(R.string.diagnostics_share)))
    })
}

@Composable
private fun eventSummary(event: DiagnosticEvents.Event): String = when (event.kind) {
    "connection" -> stringResource(R.string.diagnostics_connection) + ": " + stringResource(when (event.detail) {
        "connecting" -> R.string.diagnostics_connecting
        "connected" -> R.string.dashboard_connected
        "ready" -> R.string.diagnostics_ready
        "failed" -> R.string.diagnostics_failed
        else -> R.string.diagnostics_no_session
    })
    "retry" -> stringResource(R.string.diagnostics_retries) + ": " + event.detail
    "sync" -> stringResource(R.string.diagnostics_stage) + ": " + stringResource(when (event.detail) {
        "HANDSHAKE" -> R.string.diagnostics_handshake
        "FEATURES" -> R.string.diagnostics_features
        "STATUS" -> R.string.diagnostics_status
        else -> R.string.diagnostics_sync_requested
    })
    "role" -> stringResource(R.string.diagnostics_primary) + ": " + stringResource(
        if (event.detail == "left") R.string.dashboard_left else R.string.dashboard_right)
    "wear" -> stringResource(R.string.diagnostics_wear) + ": " + event.detail + " " + stringResource(R.string.diagnostics_wear_codes)
    "setting" -> stringResource(settingTitle(event.setting)) + ": " + stringResource(settingStatus(event.detail))
    else -> stringResource(failureReason(event.detail))
}

private fun failureReason(code: String?): Int = when (code) {
    "socket_create_failed" -> R.string.diagnostics_socket_create_failed
    "socket_connect_failed" -> R.string.diagnostics_socket_connect_failed
    "write_failed", "setting_write_failed" -> R.string.setting_write_failed
    "status_timeout" -> R.string.diagnostics_status_timeout
    "status_session_ended" -> R.string.diagnostics_session_ended
    "setting_timeout" -> R.string.setting_failed
    else -> R.string.diagnostics_no_failure
}

private fun settingStatus(status: String?): Int = when (status) {
    "sent" -> R.string.setting_sent
    "confirmed" -> R.string.setting_confirmed
    "reported" -> R.string.setting_reported
    "write_failed" -> R.string.setting_write_failed
    "timeout" -> R.string.setting_failed
    "invalid" -> R.string.setting_invalid
    else -> R.string.diagnostics_none
}

private fun settingTitle(key: String?): Int = when (key) {
    Key.PERSONLIZED_VOLUME -> R.string.personlized_volume_title
    Key.CONVERSATION_AWARENESS -> R.string.dashboard_conversation_title
    Key.MICROPHONE_MODE -> R.string.microphone
    Key.PRESS_SPEED -> R.string.control_press_speed
    Key.HOLD_DURATION -> R.string.control_hold_duration
    Key.SWIPE_SPEED -> R.string.control_swipe_speed
    Key.CHIME_VOLUME -> R.string.control_chime_volume
    Key.ADAPTIVE_AUDIO_LEVEL -> R.string.adaptive_audio_title
    Key.ALLOW_OFF_OPTION -> R.string.allow_off_title
    Key.SINGLE_POD_ANC -> R.string.noise_cancellation_single_airpod
    Key.ADJUST_VOLUME_BY_SWIPER -> R.string.adjust_volume_by_swiper_title
    Key.LISTENING_MODE_BYTE -> R.string.long_press_shared
    PodsSettings.NOISE_MODE -> R.string.dashboard_noise
    PodsSettings.RENAME -> R.string.rename_title
    else -> R.string.diagnostics_none
}
