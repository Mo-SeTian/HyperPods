package moe.chenxy.hyperpods.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.utils.CustomEq
import moe.chenxy.hyperpods.utils.CustomEqState
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperDropdown
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/** Use real native controls; no writes on composition, page entry or incoming reports. */
@Composable
fun EqualizerPage(state: CustomEqState, deviceName: String, firmware: String, available: Boolean, active: Boolean,
    feedback: String?, onChange: (CustomEq, Long) -> Unit) {
    val scope = rememberCoroutineScope()
    val send by rememberUpdatedState(onChange)
    val enabled by rememberUpdatedState(available && active)
    var draft by remember(state.session) { mutableStateOf(state.value ?: CustomEq()) }
    var submitted by remember(state.session) { mutableStateOf(state.value) }
    var dragging by remember(state.session) { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Job?>(null) }
    val custom = state.value?.state == CustomEq.CUSTOM
    val secondary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val uriHandler = LocalUriHandler.current

    // Headset reports remain authoritative, but must not move a thumb underneath
    // an active finger. A failed write also changes revision, allowing repeated retries.
    LaunchedEffect(state, available, active, feedback) {
        if (!enabled || !dragging || state.value == null || state.value.state != draft.state ||
            feedback == "write_failed" || feedback == "invalid") {
            pending?.cancel()
            draft = state.value ?: CustomEq()
            submitted = state.value
            dragging = false
        }
    }
    DisposableEffect(state.session) {
        onDispose { pending?.cancel() }
    }
    fun commit(value: CustomEq) {
        if (enabled && value != submitted) {
            submitted = value
            send(value, state.session)
        }
    }
    fun finish() {
        pending?.cancel()
        dragging = false
        commit(draft)
    }
    fun editBand(index: Int, value: Float) {
        val level = value.roundToInt().coerceIn(0, 100)
        draft = when (index) {
            0 -> draft.copy(low = level)
            1 -> draft.copy(mid = level)
            else -> draft.copy(high = level)
        }
    }
    val finishDrag by rememberUpdatedState { if (dragging) finish() }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (deviceName.isNotBlank()) Text(deviceName, modifier = Modifier.padding(horizontal = 8.dp),
            fontSize = 14.sp, color = secondary)
        if (!available) {
            DashboardGroup {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(if (firmware.isBlank()) R.string.eq_firmware_reading else R.string.eq_update_required),
                        fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    if (firmware.isNotBlank()) Text(stringResource(R.string.eq_current_firmware, firmware),
                        fontSize = 14.sp, lineHeight = 20.sp, color = secondary)
                    Text(stringResource(if (firmware.isBlank()) R.string.eq_firmware_unknown else R.string.eq_firmware_required),
                        fontSize = 14.sp, lineHeight = 21.sp, color = secondary)
                }
                if (firmware.isNotBlank()) {
                    DashboardDivider(Modifier.padding(horizontal = 16.dp))
                    DashboardLink(stringResource(R.string.eq_firmware_help)) {
                        uriHandler.openUri("https://support.apple.com/zh-cn/106340")
                    }
                }
            }
            return@Column
        }
        DashboardGroup {
            SuperDropdown(title = stringResource(R.string.eq_mode),
                items = listOf(stringResource(R.string.eq_recommended), stringResource(R.string.eq_custom)),
                selectedIndex = state.value?.state?.minus(1) ?: -1, showValue = state.value != null,
                summary = if (state.value == null) stringResource(R.string.eq_unknown) else null,
                enabled = enabled,
                onSelectedIndexChange = { index ->
                    if (index in 0..1) {
                        pending?.cancel()
                        dragging = false
                        draft = draft.copy(state = index + 1)
                        commit(draft)
                    }
                })
        }
        when (feedback) {
            "write_failed" -> Text(stringResource(R.string.setting_write_failed), fontSize = 13.sp, color = secondary)
            "invalid" -> Text(stringResource(R.string.setting_invalid), fontSize = 13.sp, color = secondary)
            else -> Unit // Sent-only state is available in connection diagnostics.
        }
        if (state.value?.state == CustomEq.RECOMMENDED)
            Text(stringResource(R.string.eq_recommended_summary), fontSize = 13.sp, color = secondary,
                modifier = Modifier.padding(horizontal = 8.dp))
        AnimatedVisibility(visible = custom,
            enter = fadeIn(tween(180)) + expandVertically(tween(220)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(220))) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.eq_custom_tuning), fontWeight = FontWeight.SemiBold,
                        color = secondary, modifier = Modifier.weight(1f))
                    Row(Modifier.heightIn(min = 48.dp).clickable(enabled = enabled && custom, role = Role.Button) {
                        pending?.cancel()
                        dragging = false
                        draft = CustomEq(CustomEq.CUSTOM)
                        commit(draft)
                    }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.eq_reset), color = MiuixTheme.colorScheme.primary)
                    }
                }
                DashboardGroup {
                    listOf(R.string.eq_low, R.string.eq_mid, R.string.eq_high).forEachIndexed { index, label ->
                        if (index != 0) DashboardDivider(Modifier.padding(horizontal = 16.dp))
                        Column(Modifier.padding(16.dp)) {
                            val value = when (index) { 0 -> draft.low; 1 -> draft.mid; else -> draft.high }
                            val title = stringResource(label)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(title, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                Text(value.toString(), color = secondary)
                            }
                            Slider(value = value.toFloat(), valueRange = 0f..100f, steps = 99,
                                enabled = enabled && custom,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .pointerInput(state.session) {
                                        // Miuix only calls its finish callback on drag end.
                                        // Also settle on pointer cancellation, without consuming input.
                                        awaitEachGesture {
                                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                            try {
                                                do { val event = awaitPointerEvent(PointerEventPass.Final) }
                                                while (event.changes.any { it.pressed })
                                            } finally { finishDrag() }
                                        }
                                    }
                                    .testTag("eq_band_$index").progressSemantics(value.toFloat(), 0f..100f, 99)
                                    .semantics {
                                        contentDescription = title
                                        if (!enabled || !custom) disabled()
                                        setProgress { next ->
                                            if (!enabled || !custom || !next.isFinite()) false else {
                                                editBand(index, next)
                                                finish()
                                                true
                                            }
                                        }
                                    },
                                onValueChange = { next ->
                                    dragging = true
                                    editBand(index, next)
                                    // At most ten packets per second while dragging. Release
                                    // flushes the last value immediately, even after a fast flick.
                                    if (pending?.isActive != true) pending = scope.launch {
                                        delay(100)
                                        commit(draft)
                                    }
                                }, onValueChangeFinished = { finish() })
                        }
                    }
                }
                Text(stringResource(R.string.eq_neutral), fontSize = 13.sp, color = secondary,
                    modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
    }
}
