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
import androidx.compose.foundation.layout.*

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import moe.chenxy.hyperpods.utils.AirPodsBase

import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
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
import top.yukonga.miuix.kmp.basic.VerticalSlider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Text
import moe.chenxy.hyperpods.ui.components.MinimalDropdown as SuperDropdown
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/** Use real native controls; no writes on composition, page entry or incoming reports. */
@Composable
fun EqualizerPage(state: CustomEqState, firmware: String, available: Boolean, active: Boolean,
    feedback: String?, model: AirPodsBase? = null, onChange: (CustomEq, Long) -> Unit) {
    val scope = rememberCoroutineScope()
    val send by rememberUpdatedState(onChange)
    var draft by remember(state.session) { mutableStateOf(state.value ?: CustomEq()) }
    var submitted by remember(state.session) { mutableStateOf(state.value) }
    var dragging by remember(state.session) { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Job?>(null) }
    val foreground = rememberUiForeground {
        pending?.cancel()
        dragging = false
        draft = state.value ?: CustomEq()
        submitted = state.value
    }
    val enabled by rememberUpdatedState(available && active && foreground)
    val custom = state.value?.state == CustomEq.CUSTOM
    val secondary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val uriHandler = LocalUriHandler.current

    // Headset reports remain authoritative, but must not move a thumb underneath
    // an active finger. A failed write also changes revision, allowing repeated retries.
    LaunchedEffect(state, available, active, foreground, feedback) {
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

    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp)) {
        FidelityText(stringResource(R.string.minimal_eq_lead), size = 23, weight = FontWeight.SemiBold)
        FidelityText(stringResource(R.string.minimal_eq_description), Modifier.padding(top = 10.dp, bottom = 30.dp), size = 13, color = Fidelity.muted())
        if (!available) {
            DashboardGroup {
                Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(if (firmware.isBlank()) R.string.eq_firmware_reading else R.string.eq_update_required),
                        fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    if (firmware.isNotBlank()) Text(stringResource(R.string.eq_current_firmware, firmware),
                        fontSize = 14.sp, lineHeight = 20.sp, color = secondary)
                    Text(stringResource(if (firmware.isBlank()) R.string.eq_firmware_unknown else R.string.eq_firmware_required),
                        fontSize = 14.sp, lineHeight = 21.sp, color = secondary)
                }
                if (firmware.isNotBlank()) {
                    FidelityDivider()
                    DashboardLink(stringResource(R.string.eq_firmware_help)) {
                        uriHandler.openUri("https://support.apple.com/zh-cn/106340")
                    }
                }
            }
            return@Column
        }
        FidelityDivider()
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
        FidelityDivider()
        when (feedback) {
            "write_failed" -> Text(stringResource(R.string.setting_write_failed), fontSize = 13.sp, color = secondary)
            "invalid" -> Text(stringResource(R.string.setting_invalid), fontSize = 13.sp, color = secondary)
            else -> Unit // Sent-only state is available in connection diagnostics.
        }
        if (state.value?.state == CustomEq.RECOMMENDED) Column(Modifier.fillMaxWidth().padding(top = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            FidelityProduct(model, true, Modifier.size(210.dp, 144.dp))
            FidelityText(stringResource(R.string.eq_recommended), Modifier.padding(top = 25.dp), size = 18, weight = FontWeight.SemiBold)
            FidelityText(stringResource(R.string.eq_recommended_summary), Modifier.padding(top = 8.dp), size = 13, color = Fidelity.muted(), align = TextAlign.Center)
        }
        AnimatedVisibility(visible = custom,
            enter = fadeIn(tween(180)) + expandVertically(tween(220)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(220))) {
            Column(Modifier.padding(top = 28.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FidelityText(stringResource(R.string.eq_custom_tuning), Modifier.weight(1f), size = 16, weight = FontWeight.SemiBold)
                    Row(Modifier.heightIn(min = 48.dp).clickable(enabled = enabled && custom, role = Role.Button) {
                        pending?.cancel()
                        dragging = false
                        draft = CustomEq(CustomEq.CUSTOM)
                        commit(draft)
                    }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        FidelityText(stringResource(R.string.eq_reset), size = 14, color = Fidelity.accent())
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 26.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    listOf(R.string.eq_low, R.string.eq_mid, R.string.eq_high).forEachIndexed { index, label ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            val value = when (index) { 0 -> draft.low; 1 -> draft.mid; else -> draft.high }
                            val title = stringResource(label)
                            FidelityText(title, size = 14)
                            Box(Modifier.padding(top = 22.dp, bottom = 18.dp).size(48.dp, 206.dp), contentAlignment = Alignment.Center) {
                            VerticalSlider(value = value.toFloat(), valueRange = 0f..100f, steps = 99,
                                enabled = enabled && custom, width = 12.dp,
                                colors = SliderDefaults.sliderColors(foregroundColor = Fidelity.panel(),
                                    disabledForegroundColor = Fidelity.panel(), backgroundColor = Fidelity.panel()),
                                modifier = Modifier.fillMaxSize()
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
                                Box(Modifier.align(Alignment.TopCenter).offset(y = (206 - 28).dp * (1f - value / 100f)).size(28.dp)
                                    .background(Fidelity.surface(), CircleShape).border(3.dp, Fidelity.blue, CircleShape)
                                    .alpha(if (enabled && custom) 1f else .4f))
                            }
                            FidelityText(value.toString(), size = 16, weight = FontWeight.SemiBold, color = Fidelity.accent())
                        }
                    }
                }
                FidelityText(stringResource(R.string.minimal_eq_caption), Modifier.fillMaxWidth().padding(top = 34.dp), size = 12, color = Fidelity.muted(), align = TextAlign.Center)
            }
        }
    }
}
