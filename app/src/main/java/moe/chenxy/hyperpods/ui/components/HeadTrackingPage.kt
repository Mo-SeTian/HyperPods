package moe.chenxy.hyperpods.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.utils.HeadPose
import moe.chenxy.hyperpods.utils.HeadTrackingPreview.Snapshot
import moe.chenxy.hyperpods.utils.HyperPodsBroadcasts
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import top.yukonga.miuix.kmp.basic.Button
import moe.chenxy.hyperpods.ui.components.DashboardGroup as Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.UUID
import kotlin.math.roundToInt

private fun running(state: String) = state in setOf("starting", "calibrating", "tracking")

/** The receiver lives only with this page. UUID + sequence reject late results;
 * a Bluetooth-side lease also handles process death, where onDispose cannot run. */
@Composable
fun HeadTrackingPage(isActive: Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var snapshot by remember { mutableStateOf(Snapshot("", 0, "idle")) }
    var requested by remember { mutableStateOf(false) }
    var lastReceived by remember { mutableLongStateOf(0L) }
    var stale by remember { mutableStateOf(false) }
    val history = remember { mutableStateListOf<HeadPose.Sample>() }

    fun command(id: String, action: String) {
        if (id.isEmpty()) return
        HyperPodsBroadcasts.send(context, Intent(HyperPodsAction.ACTION_HEAD_TRACKING_COMMAND)
            .putExtra("session", id).putExtra("command", action), HyperPodsBroadcasts.BLUETOOTH)
    }
    fun leave() {
        command(snapshot.session, "stop")
        requested = false
        if (running(snapshot.state)) snapshot = snapshot.copy(state = "background", sample = null)
        history.clear()
    }

    DisposableEffect(context, lifecycle) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!HyperPodsBroadcasts.isTrusted(context, this, HyperPodsBroadcasts.BLUETOOTH) ||
                    intent.getStringExtra("session") != snapshot.session) return
                val sequence = intent.getLongExtra("sequence", 0)
                if (sequence <= snapshot.sequence) return
                val state = intent.getStringExtra("state") ?: return
                if (!requested && running(state)) return
                val sample = if (intent.hasExtra("pitch")) HeadPose.Sample(
                    intent.getFloatExtra("pitch", 0f), intent.getFloatExtra("yaw", 0f),
                    intent.getBooleanExtra("calibrated", false),
                    intent.getIntExtra("horizontal", 0), intent.getIntExtra("vertical", 0)) else null
                if (sample != null && (!sample.pitch.isFinite() || !sample.yaw.isFinite() ||
                        sample.horizontal !in -6000..6000 || sample.vertical !in -6000..6000)) return
                snapshot = Snapshot(snapshot.session, sequence, state, intent.getIntExtra("packets", 0), sample,
                    intent.getIntExtra("nods", 0), intent.getIntExtra("shakes", 0), intent.getStringExtra("gesture") ?: "")
                lastReceived = SystemClock.elapsedRealtime()
                stale = false
                if (sample != null && running(state)) {
                    history.add(sample)
                    if (history.size > 100) history.removeAt(0)
                } else history.clear()
                if (!running(state)) requested = false
            }
        }
        context.registerReceiver(receiver, IntentFilter(HyperPodsAction.ACTION_HEAD_TRACKING_SAMPLE), Context.RECEIVER_EXPORTED)
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!foreground) leave()
        }
        lifecycle.addObserver(observer)
        onDispose {
            leave()
            lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    LaunchedEffect(isActive) { if (!isActive) leave() }
    LaunchedEffect(snapshot.session, requested, isActive, foreground) {
        if (!requested || !isActive || !foreground) return@LaunchedEffect
        val id = snapshot.session
        command(id, "start")
        try {
            while (true) {
                delay(2000)
                val age = SystemClock.elapsedRealtime() - lastReceived
                stale = snapshot.packets > 0 && age > 1500
                if (age >= 8000) {
                    snapshot = snapshot.copy(state = "unavailable", sample = null)
                    history.clear()
                    requested = false
                    break
                }
                command(id, "heartbeat")
            }
        } finally { command(id, "stop") }
    }
    LaunchedEffect(snapshot.state) {
        if (snapshot.state == "stopping") {
            delay(3000)
            snapshot = snapshot.copy(state = "stop_unconfirmed", sample = null)
        }
    }
    HeadTrackingContent(snapshot, history, stale, isActive && foreground,
        onStart = {
            history.clear()
            snapshot = Snapshot(UUID.randomUUID().toString(), 0, "starting")
            lastReceived = SystemClock.elapsedRealtime()
            stale = false
            requested = true
        },
        onStop = {
            requested = false
            snapshot = snapshot.copy(state = "stopping", sample = null)
            history.clear()
        },
        onCenter = { command(snapshot.session, "center"); history.clear() })
}

/** Separate rendering also lets instrumentation exercise results without fake
 * Bluetooth broadcasts or permissions, and without touching a real phone call. */
@Composable
fun HeadTrackingContent(snapshot: Snapshot, history: List<HeadPose.Sample>, stale: Boolean,
    enabled: Boolean, onStart: () -> Unit, onStop: () -> Unit, onCenter: () -> Unit) {
    val active = running(snapshot.state)
    val live = active && !stale
    val sample = snapshot.sample.takeIf { live && it?.calibrated == true }
    val primary = MiuixTheme.colorScheme.primary
    val secondary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.head_test_safety), fontSize = 13.sp, color = secondary)
        Card {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (stale && active) R.string.head_test_stale else headTestStatus(snapshot.state)),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Text(stringResource(R.string.head_test_packets, snapshot.packets), fontSize = 13.sp, color = secondary)
                Button(onClick = if (active) onStop else onStart,
                    enabled = enabled && snapshot.state != "stopping", modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (active) R.string.head_test_stop else R.string.head_test_start),
                        color = if (enabled && snapshot.state != "stopping") primary else secondary.copy(alpha = .4f))
                }
                val poseDescription = stringResource(R.string.head_test_pose)
                Canvas(Modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = poseDescription }
                    .graphicsLayer {
                        // Follow real data directly: no decorative lag/spring over sensor samples.
                        rotationX = -(sample?.pitch ?: 0f).coerceIn(-40f, 40f)
                        rotationY = (sample?.yaw ?: 0f).coerceIn(-60f, 60f)
                        cameraDistance = 12 * density
                    }) {
                    val r = size.height * .35f
                    val c = center
                    val color = if (sample != null) primary else secondary.copy(alpha = .4f)
                    drawCircle(color, r, c, style = Stroke(3.dp.toPx()))
                    drawCircle(color, 3.dp.toPx(), c + Offset(-r * .3f, -r * .2f))
                    drawCircle(color, 3.dp.toPx(), c + Offset(r * .3f, -r * .2f))
                    drawLine(color, c, c + Offset(0f, r * .2f), 3.dp.toPx(), StrokeCap.Round)
                    drawLine(color, c + Offset(-r * .22f, r * .45f), c + Offset(r * .22f, r * .45f), 3.dp.toPx(), StrokeCap.Round)
                    drawLine(color, c + Offset(-r * 1.15f, -r * .25f), c + Offset(-r * 1.15f, r * .25f), 5.dp.toPx(), StrokeCap.Round)
                    drawLine(color, c + Offset(r * 1.15f, -r * .25f), c + Offset(r * 1.15f, r * .25f), 5.dp.toPx(), StrokeCap.Round)
                }
                Text(if (sample == null) stringResource(R.string.head_test_pose_waiting) else
                    stringResource(R.string.head_test_angles, sample.pitch.roundToInt(), sample.yaw.roundToInt()), fontSize = 14.sp)
                Text(stringResource(R.string.head_test_estimated), fontSize = 12.sp, color = secondary)
                Button(onClick = onCenter, enabled = enabled && sample != null, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.head_test_center),
                        color = if (enabled && sample != null) primary else secondary.copy(alpha = .4f))
                }
            }
        }
        Card {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.head_test_motion))
                Text(stringResource(R.string.head_test_legend), fontSize = 12.sp, color = secondary)
                val chartDescription = stringResource(R.string.head_test_graph)
                Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = chartDescription }) {
                    drawLine(secondary.copy(alpha = .3f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
                    if (live && history.size >= 2) {
                        // Fixed, labelled raw range: changes in scale cannot look like head motion.
                        for (vertical in listOf(false, true)) {
                            val path = Path()
                            history.forEachIndexed { index, point ->
                                val value = if (vertical) point.vertical else point.horizontal
                                val x = size.width * index / 99f
                                val y = size.height * (.5f - value / 12000f)
                                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            drawPath(path, if (vertical) secondary else primary,
                                style = Stroke(if (vertical) 1.5.dp.toPx() else 2.5.dp.toPx()))
                        }
                    }
                }
            }
        }
        Card {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.head_test_instruction))
                AnimatedContent(snapshot.gesture, transitionSpec = {
                    fadeIn(tween(160)) togetherWith fadeOut(tween(100))
                }, label = "HeadGestureResult") { gesture ->
                    Text(stringResource(when (gesture) {
                        "NOD" -> R.string.head_test_nod
                        "SHAKE" -> R.string.head_test_shake
                        else -> R.string.head_test_no_gesture
                    }), color = primary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                Text(stringResource(R.string.head_test_counts, snapshot.nods, snapshot.shakes), fontSize = 13.sp)
            }
        }
    }
}

private fun headTestStatus(state: String) = when (state) {
    "starting" -> R.string.head_test_starting
    "calibrating" -> R.string.head_test_calibrating
    "tracking" -> R.string.head_test_tracking
    "not_worn" -> R.string.head_gestures_not_worn
    "unsupported" -> R.string.head_gestures_unsupported
    "no_data" -> R.string.head_gestures_no_data
    "disconnected" -> R.string.head_test_disconnected
    "interrupted" -> R.string.head_test_interrupted
    "expired", "timeout" -> R.string.head_test_expired
    "background" -> R.string.head_test_background
    "stopping" -> R.string.head_test_stopping
    "stop_unconfirmed" -> R.string.head_test_stop_unconfirmed
    "stop_failed" -> R.string.head_test_stop_failed
    "stopped" -> R.string.head_test_stopped
    "unavailable" -> R.string.head_test_unavailable
    else -> R.string.head_test_idle
}
