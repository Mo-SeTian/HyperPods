package moe.chenxy.hyperpods.ui.components

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.testTag

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.pods.NoiseControlMode
import kotlinx.coroutines.delay
import moe.chenxy.hyperpods.utils.ListeningPosition
import moe.chenxy.hyperpods.utils.StatusFreshness
import moe.chenxy.hyperpods.pods.EarDetectionStatus
import moe.chenxy.hyperpods.utils.AirPodsBase
import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.EarDetectionParams
import moe.chenxy.hyperpods.utils.data.PodBatteryParams
import top.yukonga.miuix.kmp.basic.Icon

@Composable
fun DeviceHero(name: String, model: AirPodsBase?, battery: BatteryParams, ears: EarDetectionParams,
    diagnostics: Bundle?, renameBlocked: Boolean, onRename: (String) -> Unit) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val visibility = remember { MutableTransitionState(false) }.apply { targetState = expanded }
    rememberUiForeground { expanded = false }
    BackHandler(expanded) { expanded = false }
    val density = LocalDensity.current
    val menuPosition = remember(density) {
        val gap = with(density) { 8.dp.roundToPx() }
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - popupContentSize.width else anchorBounds.left
                val below = anchorBounds.bottom + gap
                val y = if (below + popupContentSize.height <= windowSize.height - gap) below
                    else anchorBounds.top - gap - popupContentSize.height
                return IntOffset(x.coerceIn(gap, (windowSize.width - popupContentSize.width - gap).coerceAtLeast(gap)),
                    y.coerceIn(gap, (windowSize.height - popupContentSize.height - gap).coerceAtLeast(gap)))
            }
        }
    }
    val showCase = battery.case?.let { it.isConnected && it.battery in 0..100 } == true
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                FidelityText(name, size = 26, weight = FontWeight.Bold)
                FidelityConnected(stringResource(dashboardConnectionStatus(diagnostics)), diagnostics?.getBoolean("ready") == true,
                    Modifier.padding(top = 4.dp))
            }
            Box(Modifier.size(48.dp).clip(CircleShape).clickable { expanded = !expanded }
                .semantics { contentDescription = context.getString(R.string.fidelity_device_options) },
                contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ui_ellipsis), null, Modifier.size(24.dp), tint = Fidelity.ink())
                if (visibility.currentState || visibility.targetState) Popup(menuPosition,
                    onDismissRequest = { expanded = false }, properties = PopupProperties(focusable = true)) {
                    androidx.compose.animation.AnimatedVisibility(visibility, enter = fadeIn(tween(120)), exit = fadeOut(tween(100))) {
                        Column(Modifier.width(236.dp).shadow(4.dp, RoundedCornerShape(14.dp))
                            .background(Fidelity.surface(), RoundedCornerShape(14.dp)).padding(16.dp)) {
                            FidelityText(name, size = 13, weight = FontWeight.SemiBold)
                            FidelityText(model?.name?.replace(" with Magsafe Charging Case (", " · ")?.removeSuffix(")")
                                ?: stringResource(R.string.settings_reading), Modifier.padding(top = 4.dp, bottom = 12.dp), size = 12, color = Fidelity.muted())
                            FidelityDivider()
                            RenamePod(Modifier.fillMaxWidth(), onRename, name, pending = renameBlocked || !expanded)
                            FidelityDivider()
                            MinimalLink(stringResource(R.string.redesign_bluetooth), enabled = expanded, titleColor = Fidelity.accent()) {
                                expanded = false
                                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                            }
                        }
                    }
                }
            }
        }
        FidelityProduct(model, showCase, Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp).size(220.dp, 126.dp))
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Reading(stringResource(R.string.dashboard_left), battery.left, ears.left, diagnostics, Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(40.dp).background(Fidelity.divider()))
            Reading(stringResource(R.string.dashboard_right), battery.right, ears.right, diagnostics, Modifier.weight(1f))
            if (showCase) {
                Box(Modifier.width(1.dp).height(40.dp).background(Fidelity.divider()))
                Reading(stringResource(R.string.dashboard_case), battery.case, null, diagnostics, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Reading(label: String, params: PodBatteryParams?, wear: Byte?, diagnostics: Bundle?, modifier: Modifier) {
    val available = params?.isConnected == true && params.battery in 0..100
    val level = if (available) "${params!!.battery}%" else "—"
    val reportedWear = StatusFreshness.wear(diagnostics?.getBoolean("wear") == true,
        diagnostics?.getLong("wear_report_at") ?: 0, diagnostics?.getString("connection"))
    val status = stringResource(when {
        !available -> R.string.dashboard_unavailable
        params?.isCharging == true -> R.string.dashboard_charging
        wear == null -> R.string.dashboard_connected
        reportedWear == StatusFreshness.State.UNKNOWN -> R.string.settings_reading
        wear == EarDetectionStatus.IN_EAR -> R.string.dashboard_wearing
        wear.toInt() in 2..3 || params?.isInCase == true -> R.string.dashboard_in_case
        else -> R.string.dashboard_out_of_ear
    })
    val reading = modifier.semantics(mergeDescendants = true) { contentDescription = "$label $level, $status" }
    if (LocalDensity.current.fontScale > 1.25f) {
        Column(reading, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(painterResource(R.drawable.dashboard_battery), null, Modifier.size(25.dp).rotate(90f),
                tint = if (available) Fidelity.blue else Fidelity.muted())
            FidelityText(label, size = 13, color = Fidelity.muted(), align = androidx.compose.ui.text.style.TextAlign.Center)
            FidelityText(level, size = 19, weight = FontWeight.SemiBold, maxLines = 1)
        }
    } else Row(reading,
        horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.dashboard_battery), null, Modifier.size(25.dp).rotate(90f),
            tint = if (available) Fidelity.blue else Fidelity.muted())
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            FidelityText(label, size = 13, color = Fidelity.muted())
            FidelityText(level, size = 19, weight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun ModelArtwork(model: AirPodsBase?, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        for (right in listOf(false, true)) {
            val res = if (right) model?.rightBudsRes else model?.leftBudsRes
            Image(painterResource(res ?: R.drawable.dashboard_headphones), null,
                Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
fun ListeningSlider(mode: NoiseControlMode?, adaptiveLevel: Float, adaptive: Boolean, enabled: Boolean,
    feedback: String?, onCommit: (Float) -> Unit, available: Boolean = enabled, strengthFeedback: String? = null,
    containerColor: Color = Fidelity.surface(),
    contentPadding: PaddingValues = PaddingValues(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 22.dp),
    compact: Boolean = false,
) {
    val position = ListeningPosition.fromReport(mode, adaptiveLevel)
    var draft by remember { mutableFloatStateOf(position) }
    var dragging by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var direction by remember { mutableFloatStateOf(1f) }
    var requestedPosition by remember { mutableStateOf<Float?>(null) }
    var feedbackAtRequest by remember { mutableStateOf(feedback to strengthFeedback) }
    val latestPosition by rememberUpdatedState(position)
    val currentOnCommit by rememberUpdatedState(onCommit)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun reset() {
        dragging = false
        pressed = false
        requestedPosition = null
        draft = latestPosition
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                reset()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(position, mode, available, feedback, strengthFeedback, requestedPosition) {
        val target = requestedPosition
        val failures = listOf("timeout", "write_failed", "interrupted", "invalid")
        val failed = target != null && (feedback != feedbackAtRequest.first && feedback in failures ||
            ListeningPosition.modeAt(target, adaptive) == NoiseControlMode.ADAPTIVE &&
                strengthFeedback != feedbackAtRequest.second && strengthFeedback in failures)
        if (target != null && !failed) feedbackAtRequest = feedback to strengthFeedback
        if (!available || failed) reset()
        else if (!dragging && (target == null || mode == ListeningPosition.modeAt(target, adaptive) &&
                kotlin.math.abs(position - target) < .004f)) {
            requestedPosition = null
            draft = position
        }
    }
    LaunchedEffect(enabled) {
        if (!enabled) {
            pressed = false
            dragging = false
            if (requestedPosition == null) draft = position
        }
    }
    // The thumb shows this request while reports catch up, without changing the
    // controller's confirmed mode. Never retain it after an unanswered request.
    LaunchedEffect(requestedPosition) {
        if (requestedPosition != null) {
            delay(6500)
            requestedPosition = null
            draft = latestPosition
        }
    }
    // LibrePods writes AUTO_ANC_STRENGTH while adjusting. Limit traffic here to
    // one changed value per 150 ms; release below always flushes the final value.
    // The parent keys this component by the control session, cancelling old work.
    LaunchedEffect(dragging, enabled, mode, adaptive) {
        if (!dragging || !enabled || !adaptive || mode != NoiseControlMode.ADAPTIVE) return@LaunchedEffect
        var sent = ListeningPosition.wireStrength(adaptiveLevel)
        while (true) {
            delay(150)
            if (!dragging || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
            if (draft in ListeningPosition.ADAPTIVE_START..ListeningPosition.ADAPTIVE_END) {
                val strength = ListeningPosition.wireStrength(ListeningPosition.adaptiveLevel(draft))
                if (strength != sent) {
                    currentOnCommit(draft)
                    sent = strength
                }
            }
        }
    }
    val thumb by animateFloatAsState(draft, if (dragging) snap() else tween(180), label = "ListeningPosition")
    val deformation by animateFloatAsState(if (dragging) 1f else if (pressed) .55f else 0f,
        if (dragging || pressed) tween(110) else spring(dampingRatio = .78f, stiffness = 600f), label = "ListeningDrop")
    val tailDirection by animateFloatAsState(direction, tween(80), label = "ListeningDirection")
    fun commit(value: Float) {
        if (!enabled || !available || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val target = when (ListeningPosition.modeAt(value, adaptive)) {
            NoiseControlMode.TRANSPARENCY -> 0f
            NoiseControlMode.NOISE_CANCELLATION -> 1f
            else -> value.coerceIn(ListeningPosition.ADAPTIVE_START, ListeningPosition.ADAPTIVE_END)
        }
        draft = target
        requestedPosition = target
        feedbackAtRequest = feedback to strengthFeedback
        currentOnCommit(target)
    }
    val thumbWidth = if (compact) 54.dp else 68.dp
    val thumbInset = if (compact) 6.dp else 7.dp
    val thumbCenterInsetPx = with(LocalDensity.current) { (thumbInset + thumbWidth / 2).toPx() }
    val sliderLabel = stringResource(R.string.redesign_slider)
    val rangeLabel = stringResource(R.string.redesign_adaptive_range)
    val waitingForMode = compact && mode == null
    val displayedMode = if (!waitingForMode && (dragging || requestedPosition != null))
        ListeningPosition.modeAt(draft, adaptive) else mode
    val modeText = stringResource(when(displayedMode) {
        NoiseControlMode.TRANSPARENCY -> R.string.dashboard_transparency
        NoiseControlMode.NOISE_CANCELLATION -> R.string.noise_cancellation_title
        NoiseControlMode.ADAPTIVE -> R.string.adaptive_title
        NoiseControlMode.OFF -> R.string.off
        else -> R.string.settings_reading
    })
    val displayedStrength = ListeningPosition.wireStrength(if (dragging || requestedPosition != null)
        ListeningPosition.adaptiveLevel(draft) else adaptiveLevel)
    val pendingText = stringResource(R.string.setting_pending)
    Column(Modifier.fillMaxWidth().background(containerColor).padding(contentPadding)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            val labelModifier = if (compact) Modifier.weight(1f) else Modifier
            FidelityText(stringResource(R.string.redesign_listening), labelModifier,
                size = if (compact) 16 else 18, weight = FontWeight.SemiBold)
            FidelityText(modeText, labelModifier, size = if (compact) 15 else 17,
                weight = FontWeight.SemiBold, color = if (waitingForMode) Fidelity.muted() else Fidelity.accent(),
                align = if (compact) androidx.compose.ui.text.style.TextAlign.End else null)
        }
        BoxWithConstraints(Modifier.padding(top = if (compact) 10.dp else 20.dp).fillMaxWidth().height(if (compact) 48.dp else 56.dp)
            .alpha(if (available && !waitingForMode) 1f else .45f)
            .semantics {
                contentDescription = sliderLabel
                stateDescription = (if (adaptive && displayedMode == NoiseControlMode.ADAPTIVE) "$modeText; $rangeLabel; $displayedStrength%" else modeText) +
                    if (!waitingForMode && requestedPosition != null) "; $pendingText" else ""
                if (!enabled) disabled()
                if (!waitingForMode) {
                    progressBarRangeInfo = ProgressBarRangeInfo(draft, 0f..1f)
                    setProgress { value -> if (enabled) { commit(value); true } else false }
                }
            }.pointerInput(enabled) {
                if (!enabled) return@pointerInput
                try {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        pressed = true
                        do { val event = awaitPointerEvent(PointerEventPass.Initial) }
                        while (event.changes.any { it.pressed })
                        pressed = false
                    }
                } finally { pressed = false }
            }.pointerInput(enabled, adaptive, thumbCenterInsetPx) {
                if (!enabled) return@pointerInput
                fun positionAt(x: Float) = ((x - thumbCenterInsetPx) /
                    (size.width - 2 * thumbCenterInsetPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
                // Drag start already contains the touch-slop displacement.
                // Accumulating the first delta again would send the wrong strength.
                detectHorizontalDragGestures(onDragStart = {
                    direction = if (positionAt(it.x) < draft) -1f else 1f
                    dragging = true
                    requestedPosition = null
                    draft = positionAt(it.x)
                }, onHorizontalDrag = { change, amount ->
                    change.consume()
                    if (amount != 0f) direction = if (amount < 0f) -1f else 1f
                    draft = positionAt(change.position.x)
                }, onDragEnd = { dragging = false; commit(draft) },
                    onDragCancel = { if (dragging) reset() })
            }) {
            val trackColor = if (compact) Color.White.copy(alpha = .44f) else Fidelity.panel()
            Box(Modifier.matchParentSize().padding(vertical = if (compact) 6.dp else 0.dp)
                .background(trackColor, CircleShape))
            val travel = maxWidth - thumbWidth - thumbInset * 2
            if (adaptive && !waitingForMode) {
                // This visible region uses exactly the same bounds as modeAt /
                // adaptiveLevel. Both ends belong to Adaptive and reach 0 / 100.
                val rangePadding = 12.dp
                val rangeColor = when {
                    compact -> Color(0xFF9EC9FC).copy(alpha = .78f)
                    Fidelity.dark() -> Color(0xFF233B58)
                    else -> Color(0xFFDCECFF)
                }
                Row(Modifier.offset(x = thumbInset + thumbWidth / 2 + travel * ListeningPosition.ADAPTIVE_START - rangePadding)
                    .padding(vertical = if (compact) 8.dp else 6.dp)
                    .width(travel * (ListeningPosition.ADAPTIVE_END - ListeningPosition.ADAPTIVE_START) + rangePadding * 2)
                    .fillMaxHeight().clip(CircleShape)
                    .background(rangeColor)
                    .padding(horizontal = rangePadding),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    if (!compact) {
                        Box(Modifier.size(4.dp).background(Fidelity.muted().copy(alpha = .9f), CircleShape))
                        Box(Modifier.size(4.dp).background(Fidelity.muted().copy(alpha = .9f), CircleShape))
                    }
                }
            }
            val modeIconSize = if (compact) 26.dp else 30.dp
            Row(Modifier.fillMaxSize().padding(horizontal = if (compact) 20.dp else 23.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Icon(fidelityPainter(R.drawable.transparency), null, Modifier.size(modeIconSize), tint = Fidelity.muted())
                Icon(fidelityPainter(R.drawable.noise_cancellation), null, Modifier.size(modeIconSize), tint = Fidelity.muted())
            }
            if (!waitingForMode) Box(Modifier.offset(x = travel * thumb + thumbInset).padding(vertical = 6.dp).width(thumbWidth).fillMaxHeight()
                .testTag("listening-thumb"), contentAlignment = Alignment.Center) {
                Canvas(Modifier.matchParentSize()) {
                    val amount = deformation.coerceIn(-.06f, 1f)
                    val width = size.width - 22.dp.toPx() * amount
                    val height = size.height - 5.dp.toPx() * amount
                    val tail = 8.dp.toPx() * amount.coerceAtLeast(0f)
                    withTransform({ scale(tailDirection, 1f, center) }) {
                        if (tail > 0f) {
                            val back = center.x - width / 2 - tail
                            val shoulder = center.x - width * .12f
                            val tip = 3.dp.toPx()
                            val path = Path().apply {
                                moveTo(back + tip, center.y - tip)
                                cubicTo(center.x - width * .48f, center.y - height * .15f,
                                    shoulder, center.y - height * .5f, shoulder, center.y - height * .5f)
                                lineTo(shoulder, center.y + height * .5f)
                                cubicTo(shoulder, center.y + height * .5f,
                                    center.x - width * .48f, center.y + height * .15f, back + tip, center.y + tip)
                                cubicTo(back - 1.dp.toPx(), center.y + tip, back - 1.dp.toPx(), center.y - tip, back + tip, center.y - tip)
                                close()
                            }
                            drawPath(path, Fidelity.blue)
                        }
                    }
                    drawRoundRect(Fidelity.blue, Offset(center.x - width / 2, center.y - height / 2),
                        Size(width, height), CornerRadius(height / 2))
                }
                val draftMode = ListeningPosition.modeAt(draft, adaptive)
                Icon(fidelityPainter(when (draftMode) {
                    NoiseControlMode.TRANSPARENCY -> R.drawable.transparency
                    NoiseControlMode.ADAPTIVE -> R.drawable.adaptive
                    else -> R.drawable.noise_cancellation
                }), null, Modifier.size(modeIconSize + 1.dp).graphicsLayer {
                    scaleX = 1f - .1f * deformation.coerceAtLeast(0f)
                    scaleY = scaleX
                }, tint = Color.White)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = if (compact) 4.dp else 8.dp), verticalAlignment = Alignment.Top) {
            FidelityText(stringResource(R.string.dashboard_transparency), Modifier.weight(1f), size = 13,
                color = if (displayedMode == NoiseControlMode.TRANSPARENCY) Fidelity.accent() else Fidelity.muted(), align = androidx.compose.ui.text.style.TextAlign.Center)
            if (adaptive) Column(Modifier.weight(3f), horizontalAlignment = Alignment.CenterHorizontally) {
                FidelityText(stringResource(R.string.adaptive_title), size = 13, weight = FontWeight.SemiBold,
                    color = if (displayedMode == NoiseControlMode.ADAPTIVE) Fidelity.accent() else Fidelity.muted())
            } else Spacer(Modifier.weight(1f))
            FidelityText(stringResource(R.string.noise_cancellation_title), Modifier.weight(1f), size = 13,
                color = if (displayedMode == NoiseControlMode.NOISE_CANCELLATION) Fidelity.accent() else Fidelity.muted(), align = androidx.compose.ui.text.style.TextAlign.Center)
        }
        if (displayedMode == NoiseControlMode.OFF) FidelityText(stringResource(R.string.redesign_current_off), size = 11, color = Fidelity.muted())
    }
}
