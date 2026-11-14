package moe.chenxy.hyperpods.ui.components

import android.os.Bundle
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription

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

import top.yukonga.miuix.kmp.basic.IconButton

import top.yukonga.miuix.kmp.icon.MiuixIcons

import top.yukonga.miuix.kmp.icon.icons.useful.Back
import top.yukonga.miuix.kmp.icon.icons.useful.Info
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(MiuixTheme.colorScheme.dividerLine))
}

@Composable
fun DashboardGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = modifier.fillMaxWidth(), content = content)
}

@Composable
fun DashboardSection(title: String) {
    Text(title, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp))
}

@Composable
fun DashboardBattery(params: BatteryParams, ears: EarDetectionParams, model: AirPodsBase?, diagnostics: Bundle? = null) {
    val showCase = params.case?.let { it.isConnected && it.battery in 0..100 } == true
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BatteryColumn(params.left, ears.left, model?.leftBudsRes ?: R.drawable.dashboard_headphones,
            stringResource(R.string.dashboard_left), Modifier.weight(1f), diagnostics, "left_battery_at", model == null)
        BatteryColumn(params.right, ears.right, model?.rightBudsRes ?: R.drawable.dashboard_headphones,
            stringResource(R.string.dashboard_right), Modifier.weight(1f), diagnostics, "right_battery_at", model == null)
        if (showCase) BatteryColumn(params.case, null, model?.caseRes ?: R.drawable.airpods_pro_case_notification,
            stringResource(R.string.dashboard_case), Modifier.weight(1f), diagnostics, "case_battery_at", model == null)
    }
}

@Composable
private fun BatteryColumn(params: PodBatteryParams?, ear: Byte?, image: Int, title: String, modifier: Modifier,
    diagnostics: Bundle?, timeKey: String, generic: Boolean) {
    val available = params?.isConnected == true && params.battery in 0..100
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.height(104.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(painterResource(image), title, modifier = Modifier.size(if (generic) 48.dp else 104.dp),
                colorFilter = if (generic) ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary) else null)
        }
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
        // Exact report times remain in diagnostics. Only stale data needs a daily-use warning.
        if (freshness == StatusFreshness.State.RETAINED) Text(stringResource(R.string.freshness_retained,
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))),
            fontSize = 10.sp, lineHeight = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 3.dp))
    }
}

/** A2DP can be connected while the independent control channel is still unavailable. */
fun dashboardConnectionStatus(diagnostics: Bundle?): Int = when {
    diagnostics == null -> R.string.diagnostics_audio_no_control_report
    diagnostics.getString("recovery") == "recovering" -> R.string.control_recovering
    diagnostics.getString("recovery") == "failed" -> R.string.control_recovery_failed
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
fun DashboardTopBar(title: String = "HyperPods", onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(MiuixIcons.Useful.Back, stringResource(R.string.dashboard_back), modifier = Modifier.size(22.dp),
                tint = MiuixTheme.colorScheme.onSurface)
        }
        Text(title, fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f))
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
    val items = listOf(
        stringResource(R.string.dashboard_headphones) to ImageVector.vectorResource(R.drawable.dashboard_headphones),
        stringResource(R.string.dashboard_about) to MiuixIcons.Useful.Info)
    Column(Modifier.fillMaxWidth().background(Fidelity.surface())) {
        FidelityDivider()
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            .selectableGroup()) {
            items.forEachIndexed { index, (label, icon) ->
                val color by animateColorAsState(if (selected == index) Fidelity.accent()
                    else Fidelity.muted(), tween(160), label = "TabColor")
                // Let the label determine height at large system font sizes.
                Column(Modifier.weight(1f).selectable(selected == index, role = Role.Tab, onClick = { onSelect(index) })
                    .heightIn(min = 60.dp).padding(vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                    Icon(icon, null, Modifier.size(22.dp), tint = color)
                    FidelityText(label, size = 13, color = color,
                        weight = if (selected == index) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
fun DashboardToggle(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    MinimalToggle(title, summary, checked, enabled, onChange)
}

@Composable
fun DashboardLink(title: String, summary: String? = null, rightText: String? = null, onClick: () -> Unit) {
    MinimalLink(title, summary, rightText, onClick = onClick)
}

@Composable
fun DashboardNoise(mode: NoiseControlMode?, onSelect: (NoiseControlMode) -> Unit, allowedModes: Set<Int> = setOf(1, 2, 3, 4)) {
    val modes = NoiseControlMode.entries
    val labels = listOf(R.string.off, R.string.noise_cancellation_title,
        R.string.dashboard_transparency, R.string.adaptive_title)
    val icons = listOf(R.drawable.dashboard_mode_off, R.drawable.dashboard_mode_anc,
        R.drawable.dashboard_mode_transparency, R.drawable.dashboard_mode_adaptive)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
        .background(MiuixTheme.colorScheme.surface).padding(4.dp).selectableGroup()) {
        modes.forEachIndexed { index, value ->
            val selected = value == mode
            val enabled = index + 1 in allowedModes
            val foreground by animateColorAsState(
                if (selected) MiuixTheme.colorScheme.onPrimaryVariant else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                animationSpec = tween(160), label = "NoiseModeForeground")
            val background by animateColorAsState(
                if (selected) MiuixTheme.colorScheme.primaryVariant else Color.Transparent,
                animationSpec = tween(160), label = "NoiseModeBackground")
            Column(Modifier.weight(1f).alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(20.dp))
                .background(background)
                .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(value) })
                .padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(icons[index]), null, modifier = Modifier.size(24.dp),
                    colorFilter = ColorFilter.tint(foreground))
                Text(stringResource(labels[index]), color = foreground, fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(top = 5.dp))
            }
        }
    }
}
