package moe.chenxy.hyperpods.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.pods.NoiseControlMode
import moe.chenxy.hyperpods.ui.components.Fidelity
import moe.chenxy.hyperpods.ui.components.FidelitySwitch
import moe.chenxy.hyperpods.ui.components.FidelityText
import moe.chenxy.hyperpods.ui.components.ListeningSlider
import moe.chenxy.hyperpods.ui.components.FidelityTextPalette
import moe.chenxy.hyperpods.ui.components.LocalFidelityTextPalette
import moe.chenxy.hyperpods.ui.components.rememberListeningCommit
import moe.chenxy.hyperpods.ui.components.rememberUiForeground
import moe.chenxy.hyperpods.utils.AACPManager.Companion.AirPodsInformation
import moe.chenxy.hyperpods.utils.AirPodsBase
import moe.chenxy.hyperpods.utils.AirPodsModels
import moe.chenxy.hyperpods.utils.CustomEqState
import moe.chenxy.hyperpods.utils.HyperPodsBroadcasts
import moe.chenxy.hyperpods.utils.ListeningPosition
import moe.chenxy.hyperpods.utils.PodsSettings
import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import moe.chenxy.hyperpods.utils.data.HyperPodsPrefsKey
import moe.chenxy.hyperpods.utils.data.PodBatteryParams

internal enum class QuickToggle(val key: String, val title: Int) {
    PERSONALIZED_VOLUME(HyperPodsPrefsKey.PERSONLIZED_VOLUME, R.string.personlized_volume_title),
    CONVERSATION_AWARENESS(HyperPodsPrefsKey.CONVERSATION_AWARENESS, R.string.dashboard_conversation_title),
}

private val quickTextPalette = FidelityTextPalette(
    ink = Color(0xFF192332), muted = Color(0xFF4D596A), accent = Color(0xFF0068D6),
)

private val quickSettingKeys = listOf(PodsSettings.NOISE_MODE, HyperPodsPrefsKey.ADAPTIVE_AUDIO_LEVEL) + QuickToggle.entries.map { it.key }

private data class QuickPodsState(
    val name: String? = null,
    val model: AirPodsBase? = null,
    val connected: Boolean = false,
    val battery: BatteryParams = BatteryParams(),
    val mode: NoiseControlMode? = null,
    val settings: Bundle = Bundle.EMPTY,
    val pending: Set<String> = emptySet(),
    val feedback: Map<String, String> = emptyMap(),
    val session: Long = 0,
    val diagnostics: Bundle? = null,
)

@Composable
internal fun QuickControls(address: String, maxHeight: Dp, canSend: () -> Boolean, onMoreSettings: () -> Unit) {
    val context = LocalContext.current
    var pods by remember(address) { mutableStateOf(QuickPodsState()) }
    var initialRead by remember(address) { mutableStateOf(false) }
    val foreground = rememberUiForeground {}

    fun sendControl(intent: Intent) {
        if (!canSend()) return
        HyperPodsBroadcasts.send(context, intent.putExtra(HyperPodsAction.EXTRA_CONTROL_ADDRESS, address)
            .putExtra(HyperPodsAction.EXTRA_CONTROL_SESSION, pods.session), HyperPodsBroadcasts.BLUETOOTH)
    }
    DisposableEffect(context, address) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (!HyperPodsBroadcasts.isTrusted(receiverContext, this, HyperPodsBroadcasts.BLUETOOTH)) return
                when (intent.action) {
                    HyperPodsAction.ACTION_PODS_CONNECTED -> {
                        if (intent.getStringExtra(HyperPodsAction.EXTRA_CONTROL_ADDRESS) != address) {
                            pods = QuickPodsState()
                            return
                        }
                        val info = intent.getParcelableExtra("device_info", AirPodsInformation::class.java)
                        pods = pods.copy(name = info?.name ?: intent.getStringExtra("device_name"),
                            model = info?.modelNumber?.let(AirPodsModels::getModelByModelNumber), connected = true)
                    }
                    HyperPodsAction.ACTION_PODS_DISCONNECTED -> pods = QuickPodsState()
                    HyperPodsAction.ACTION_PODS_BATTERY_CHANGED ->
                        intent.getParcelableExtra("status", BatteryParams::class.java)?.let { pods = pods.copy(battery = it) }
                    HyperPodsAction.ACTION_PODS_ANC_CHANGED ->
                        pods = pods.copy(mode = NoiseControlMode.entries.getOrNull(intent.getIntExtra("status", 0) - 1))
                    HyperPodsAction.ACTION_PODS_DIAGNOSTICS -> pods = pods.copy(diagnostics = intent.getBundleExtra("diagnostics"))
                    HyperPodsAction.ACTION_PODS_SETTINGS_STATE -> {
                        val settings = intent.getBundleExtra("settings") ?: return
                        val session = intent.getParcelableExtra("custom_eq_state", CustomEqState::class.java)?.session ?: 0
                        val pending = intent.getStringArrayListExtra("pending")?.toSet().orEmpty()
                        val unconfirmed = intent.getStringArrayListExtra("unconfirmed")?.toSet().orEmpty()
                        val feedback = if (session == pods.session) pods.feedback.toMutableMap() else mutableMapOf()
                        for (key in quickSettingKeys) {
                            if (settings.containsKey(key) && feedback[key] !in listOf("write_failed", "timeout", "invalid")) {
                                feedback[key] = if (key in pending || key in unconfirmed) "sent" else "reported"
                            }
                        }
                        pods = pods.copy(settings = settings, pending = pending, feedback = feedback, session = session)
                    }
                    HyperPodsAction.ACTION_PODS_SETTING_RESULT -> {
                        val key = intent.getStringExtra("key") ?: return
                        val status = intent.getStringExtra("status") ?: return
                        if (key in quickSettingKeys) {
                            pods = pods.copy(feedback = pods.feedback + (key to status))
                        }
                    }
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(HyperPodsAction.ACTION_PODS_CONNECTED)
            addAction(HyperPodsAction.ACTION_PODS_DISCONNECTED)
            addAction(HyperPodsAction.ACTION_PODS_BATTERY_CHANGED)
            addAction(HyperPodsAction.ACTION_PODS_ANC_CHANGED)
            addAction(HyperPodsAction.ACTION_PODS_DIAGNOSTICS)
            addAction(HyperPodsAction.ACTION_PODS_SETTINGS_STATE)
            addAction(HyperPodsAction.ACTION_PODS_SETTING_RESULT)
        }, Context.RECEIVER_EXPORTED)
        HyperPodsBroadcasts.send(context, Intent(HyperPodsAction.ACTION_PODS_UI_INIT), HyperPodsBroadcasts.BLUETOOTH)
        onDispose { context.unregisterReceiver(receiver) }
    }
    LaunchedEffect(address) {
        delay(2200)
        initialRead = true
    }
    val adaptiveKey = HyperPodsPrefsKey.ADAPTIVE_AUDIO_LEVEL
    val modeKey = PodsSettings.NOISE_MODE
    val adaptive = PodsSettings.supports(adaptiveKey, pods.model)
    val supportsMode = PodsSettings.supports(modeKey, pods.model)
    val controlsAvailable = foreground && pods.connected && pods.session > 0 &&
        pods.diagnostics?.getString("connection") == "connected" && pods.diagnostics?.getString("recovery").isNullOrEmpty()
    val listeningReady = controlsAvailable && pods.mode != null && supportsMode && modeKey !in pods.pending
    val listening = rememberListeningCommit(pods.session, pods.mode, adaptive, controlsAvailable, listeningReady,
        controlsAvailable && adaptive && adaptiveKey !in pods.pending,
        modeKey in pods.pending && pods.feedback[modeKey] == "sent", pods.feedback[modeKey],
        onMode = { sendControl(Intent(HyperPodsAction.ACTION_ANC_SELECT).putExtra("status", it.ordinal + 1)) },
        onStrength = { sendControl(Intent(HyperPodsAction.ACTION_PODS_SETTINGS_CHANGED).putExtra("key", adaptiveKey)
            .putExtra("value", ListeningPosition.wireStrength(it))) })
    QuickControlsPanel(pods.name, pods.model, pods.connected, initialRead, pods.battery, maxHeight, onMoreSettings,
        toggles = {
            QuickToggle.entries.filter { pods.model == null || PodsSettings.supports(it.key, pods.model) }.forEach { toggle ->
                val reported = pods.settings.containsKey(toggle.key)
                val status = if (!pods.connected) null else when (pods.feedback[toggle.key]) {
                    "write_failed" -> R.string.setting_write_failed
                    "timeout" -> R.string.setting_failed
                    "invalid" -> R.string.setting_invalid
                    "interrupted" -> R.string.control_recovery_interrupted
                    else -> when {
                        !reported -> R.string.settings_reading
                        toggle.key in pods.pending -> R.string.setting_pending
                        else -> null
                    }
                }
                QuickControlsToggle(toggle, pods.connected && pods.settings.getInt(toggle.key) == 1,
                    controlsAvailable && PodsSettings.supports(toggle.key, pods.model) && reported && toggle.key !in pods.pending, status) { checked ->
                    sendControl(Intent(HyperPodsAction.ACTION_PODS_SETTINGS_CHANGED)
                        .putExtra("key", toggle.key).putExtra("value", if (checked) 1 else 2))
                }
            }
        }) {
        if (supportsMode || pods.model == null) key(pods.session) {
            ListeningSlider(pods.mode.takeIf { pods.connected }, ListeningPosition.levelFromWire(pods.settings.getInt(adaptiveKey, 50)),
                adaptive, listeningReady && !listening.waitingForAdaptive && adaptiveKey !in pods.pending,
                pods.feedback[modeKey], listening.apply, available = controlsAvailable,
                strengthFeedback = pods.feedback[adaptiveKey], containerColor = Color.Transparent,
                contentPadding = PaddingValues(top = 17.dp, bottom = 5.dp), compact = true)
            val failure = when (pods.feedback[adaptiveKey].takeIf { pods.mode == NoiseControlMode.ADAPTIVE }
                ?: pods.feedback[modeKey]) {
                "write_failed" -> R.string.setting_write_failed
                "timeout" -> R.string.setting_failed
                "invalid" -> R.string.setting_invalid
                "interrupted" -> R.string.control_recovery_interrupted
                else -> null
            }
            if (failure != null && pods.connected) {
                FidelityText(stringResource(failure), Modifier.padding(bottom = 10.dp), size = 12, color = Fidelity.muted())
            }
        }
    }
}

@Composable
internal fun QuickControlsPanel(
    name: String?, model: AirPodsBase?, connected: Boolean, initialRead: Boolean, battery: BatteryParams,
    maxHeight: Dp, onMoreSettings: () -> Unit,
    toggles: @Composable RowScope.() -> Unit, listening: @Composable () -> Unit,
) {
    val title = stringResource(R.string.quick_controls_title)
    val leftLevel = battery.left.validLevel().takeIf { connected }
    val caseLevel = battery.case.validLevel().takeIf { connected }
    val rightLevel = battery.right.validLevel().takeIf { connected }
    CompositionLocalProvider(LocalFidelityTextPalette provides quickTextPalette) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState())
            .semantics { paneTitle = title }.padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FidelityText(name?.takeIf { it.isNotBlank() } ?: title, Modifier.weight(1f),
                    size = 21, weight = FontWeight.Bold, maxLines = 1)
                Row(Modifier.widthIn(max = 130.dp), horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (connected) Box(Modifier.size(6.dp).background(Color(0xFF00B89F), CircleShape))
                    FidelityText(stringResource(when {
                        connected -> R.string.dashboard_connected
                        initialRead -> R.string.launcher_disconnected_title
                        else -> R.string.settings_reading
                    }), size = 12, color = Fidelity.muted(), align = TextAlign.End, maxLines = 2)
                }
            }
            if (connected) {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BatteryCell(R.string.dashboard_left, leftLevel, model?.leftBudsRes ?: R.drawable.dashboard_headphones,
                        42.dp, Modifier.weight(1f))
                    if (caseLevel != null) {
                        BatteryCell(R.string.quick_case, caseLevel, model?.caseRes ?: R.drawable.airpods_pro_case_notification,
                            56.dp, Modifier.weight(1f))
                    }
                    BatteryCell(R.string.dashboard_right, rightLevel, model?.rightBudsRes ?: R.drawable.dashboard_headphones,
                        42.dp, Modifier.weight(1f))
                }
            }
            listening()
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                content = toggles)
            Box(Modifier.padding(top = 4.dp).fillMaxWidth().clip(CircleShape)
                .clickable(role = Role.Button, onClick = onMoreSettings).heightIn(min = 48.dp).padding(8.dp),
                contentAlignment = Alignment.Center) {
                FidelityText(stringResource(R.string.fidelity_more),
                    size = 15, weight = FontWeight.SemiBold, color = Fidelity.accent(), align = TextAlign.Center)
            }
        }
    }
}

private fun PodBatteryParams?.validLevel(): Int? = this?.takeIf { it.isConnected && it.battery in 0..100 }?.battery

@Composable
private fun BatteryCell(label: Int, level: Int?, image: Int, imageWidth: Dp, modifier: Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(image), null, Modifier.size(imageWidth, 43.dp))
        FidelityText(if (level != null) stringResource(R.string.quick_battery_percent, level) else "—",
            Modifier.padding(top = 4.dp), size = 18, weight = FontWeight.SemiBold, align = TextAlign.Center)
        FidelityText(stringResource(label), size = 12, color = Fidelity.muted(), align = TextAlign.Center)
    }
}

@Composable
internal fun RowScope.QuickControlsToggle(toggle: QuickToggle, checked: Boolean, enabled: Boolean, status: Int? = null,
    onCheckedChange: (Boolean) -> Unit) {
    val title = stringResource(toggle.title)
    val description = status?.let { stringResource(it) }
    Column(Modifier.weight(1f)) {
        Row(Modifier.fillMaxWidth().toggleable(checked, enabled, Role.Switch, onValueChange = onCheckedChange)
            .semantics {
                contentDescription = title
                if (description != null) stateDescription = description
            }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FidelityText(title, Modifier.weight(1f), size = 13, weight = FontWeight.Medium, color = Fidelity.muted())
            FidelitySwitch(checked, enabled, Modifier.scale(.86f).alpha(if (enabled) 1f else .4f))
        }
        if (description != null && status != R.string.settings_reading && status != R.string.setting_pending) {
            FidelityText(description, size = 12)
        }
    }
}
