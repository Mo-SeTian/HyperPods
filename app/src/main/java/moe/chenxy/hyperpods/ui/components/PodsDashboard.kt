package moe.chenxy.hyperpods.ui.components

import android.os.Bundle
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.pods.EarDetectionStatus
import moe.chenxy.hyperpods.pods.NoiseControlMode
import moe.chenxy.hyperpods.utils.AirPodsBase
import moe.chenxy.hyperpods.utils.StatusFreshness
import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.EarDetectionParams
import moe.chenxy.hyperpods.utils.data.PodBatteryParams
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.icons.useful.Back
import top.yukonga.miuix.kmp.icon.icons.useful.Info
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MiuixTheme.colorScheme.dividerLine))
}

@Composable
fun DashboardSection(title: String) {
    Text(title, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
fun DashboardBattery(params: BatteryParams, ears: EarDetectionParams, model: AirPodsBase, diagnostics: Bundle? = null) {
    val showCase = params.case?.let { it.isConnected && it.battery in 0..100 } == true
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BatteryColumn(params.left, ears.left, model.leftBudsRes,
            stringResource(R.string.dashboard_left), Modifier.weight(1f), diagnostics, "left_battery_at")
        BatteryColumn(params.right, ears.right, model.rightBudsRes,
            stringResource(R.string.dashboard_right), Modifier.weight(1f), diagnostics, "right_battery_at")
        if (showCase) BatteryColumn(params.case, null, model.caseRes,
            stringResource(R.string.dashboard_case), Modifier.weight(1f), diagnostics, "case_battery_at")
    }
}

@Composable
private fun BatteryColumn(params: PodBatteryParams?, ear: Byte?, image: Int, title: String, modifier: Modifier,
    diagnostics: Bundle?, timeKey: String) {
    val available = params?.isConnected == true && params.battery in 0..100
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(image), title, modifier = Modifier.size(112.dp))
        Text(title, fontSize = 14.sp, lineHeight = 18.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconBattery(available, params?.isCharging == true)
            Text(if (available) "${params!!.battery}%" else "—",
                fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        val wear = StatusFreshness.wear(diagnostics?.getBoolean("wear") == true,
            diagnostics?.getLong("wear_report_at") ?: 0, diagnostics?.getString("connection"))
        val status = when {
            params?.isConnected != true -> R.string.dashboard_unavailable
            params.isCharging -> R.string.dashboard_charging
            ear == null -> R.string.dashboard_connected
            wear == StatusFreshness.State.UNKNOWN -> R.string.settings_reading
            ear == EarDetectionStatus.IN_EAR -> R.string.dashboard_wearing
            ear == EarDetectionStatus.IN_CASE || params.isInCase -> R.string.dashboard_in_case
            ear.toInt() !in 0..3 -> R.string.settings_reading
            else -> R.string.dashboard_out_of_ear
        }
        Text(if (ear != null && wear == StatusFreshness.State.RETAINED && params?.isConnected == true)
            stringResource(R.string.freshness_last_wear, stringResource(status)) else stringResource(status), fontSize = 12.sp, lineHeight = 16.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 5.dp))
        val at = diagnostics?.getLong(timeKey) ?: 0
        val freshness = StatusFreshness.battery(params, at, diagnostics?.getString("connection"))
        val label = if (freshness == StatusFreshness.State.UNKNOWN) stringResource(R.string.freshness_unknown)
        else stringResource(if (freshness == StatusFreshness.State.RETAINED) R.string.freshness_retained else R.string.freshness_reported,
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at)))
        Text(label, fontSize = 10.sp, lineHeight = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 3.dp))
    }
}

/** A2DP can be connected while the independent control channel is still unavailable. */
fun dashboardConnectionStatus(diagnostics: Bundle?): Int = when {
    diagnostics == null -> R.string.diagnostics_audio_no_control_report
    diagnostics.getString("connection") == "failed" -> R.string.diagnostics_audio_control_failed
    diagnostics.getBoolean("ready") -> R.string.dashboard_connected
    diagnostics.getString("connection") == "disconnected" -> R.string.diagnostics_no_session
    else -> R.string.diagnostics_audio_syncing
}

@Composable
private fun IconBattery(available: Boolean, charging: Boolean) {
    top.yukonga.miuix.kmp.basic.Icon(painterResource(R.drawable.dashboard_battery),
        contentDescription = if (charging) stringResource(R.string.dashboard_charging) else null,
        tint = if (available) Color(0xFF147F66) else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.size(20.dp).rotate(90f))
}

@Composable
fun DashboardTopBar(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.background)
        .statusBarsPadding().height(48.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(MiuixIcons.Useful.Back, stringResource(R.string.dashboard_back), modifier = Modifier.size(22.dp))
        }
        Text("HyperPods", fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
fun DashboardNavigation(pagerState: PagerState) {
    val scope = rememberCoroutineScope()
    var animation by remember { mutableStateOf<Job?>(null) }
    DashboardNavigation(pagerState.targetPage) { index ->
        // Retarget from the currently rendered offset, not a delayed selected-tab
        // copy. A new tap or pager swipe can immediately take over the animation.
        animation?.cancel()
        animation = scope.launch {
            pagerState.animateScrollToPage(index, animationSpec = tween(240, easing = FastOutSlowInEasing))
        }
    }
}

@Composable
fun DashboardNavigation(selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.background(MiuixTheme.colorScheme.background).navigationBarsPadding()) {
        DashboardDivider()
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            listOf(R.string.dashboard_headphones, R.string.dashboard_about).forEachIndexed { index, label ->
                val tint by animateColorAsState(
                    if (selected == index) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    animationSpec = tween(180), label = "DashboardTabTint")
                Column(Modifier.weight(1f).selectable(selected == index, role = Role.Tab,
                    onClick = { onSelect(index) }).padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    if (index == 0) Icon(painterResource(R.drawable.dashboard_headphones), null,
                        tint = tint, modifier = Modifier.size(24.dp))
                    else Icon(MiuixIcons.Useful.Info, null, tint = tint, modifier = Modifier.size(24.dp))
                    Text(stringResource(label), color = tint, fontSize = 12.sp, lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
fun DashboardToggle(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(summary, fontSize = 12.sp, lineHeight = 16.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 5.dp))
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = title },
            colors = SwitchDefaults.switchColors(
                checkedTrackColor = MiuixTheme.colorScheme.primaryVariant,
                checkedThumbColor = Color.White))
    }
}

@Composable
fun DashboardLink(title: String, summary: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick)
        .heightIn(min = if (summary == null) 48.dp else 56.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
            summary?.let { Text(it, fontSize = 12.sp, lineHeight = 16.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 5.dp)) }
        }
        Icon(MiuixIcons.Basic.ArrowRight, null, modifier = Modifier.size(20.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
fun DashboardNoise(mode: NoiseControlMode?, onSelect: (NoiseControlMode) -> Unit, allowedModes: Set<Int> = setOf(1, 2, 3, 4)) {
    val modes = NoiseControlMode.entries
    val labels = listOf(R.string.off, R.string.noise_cancellation_title,
        R.string.dashboard_transparency, R.string.adaptive_title)
    val icons = listOf(R.drawable.dashboard_mode_off, R.drawable.dashboard_mode_anc,
        R.drawable.dashboard_mode_transparency, R.drawable.dashboard_mode_adaptive)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(MiuixTheme.colorScheme.surfaceVariant).padding(3.dp).selectableGroup()) {
        modes.forEachIndexed { index, value ->
            val selected = value == mode
            val enabled = index + 1 in allowedModes
            val foreground = if (selected) MiuixTheme.colorScheme.onPrimaryVariant
                else MiuixTheme.colorScheme.onSurfaceVariantSummary
            Column(Modifier.weight(1f).alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(10.dp))
                .background(if (selected) MiuixTheme.colorScheme.primaryVariant else Color.Transparent)
                .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(value) })
                .padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(icons[index]), null, modifier = Modifier.size(26.dp),
                    colorFilter = ColorFilter.tint(foreground))
                Text(stringResource(labels[index]), color = foreground, fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(top = 5.dp))
            }
        }
    }
}
