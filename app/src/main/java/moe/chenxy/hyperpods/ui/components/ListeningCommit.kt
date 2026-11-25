package moe.chenxy.hyperpods.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import moe.chenxy.hyperpods.pods.NoiseControlMode
import moe.chenxy.hyperpods.utils.ListeningPosition

internal class ListeningCommit(val waitingForAdaptive: Boolean, val apply: (Float) -> Unit)

/** Both surfaces follow the same mode-write/strength-write sequence. */
@Composable
internal fun rememberListeningCommit(
    session: Long, mode: NoiseControlMode?, adaptive: Boolean, available: Boolean,
    modeReady: Boolean, strengthReady: Boolean, modeWritten: Boolean, modeFeedback: String?,
    onMode: (NoiseControlMode) -> Unit, onStrength: (Float) -> Unit,
): ListeningCommit {
    var adaptiveRequest by remember(session) { mutableStateOf<Float?>(null) }
    val foreground = rememberUiForeground { adaptiveRequest = null }
    val sendStrength by rememberUpdatedState(onStrength)
    LaunchedEffect(session, mode, adaptiveRequest, available, strengthReady, modeWritten, foreground) {
        val level = adaptiveRequest
        if (!available || !foreground) adaptiveRequest = null
        else if ((mode == NoiseControlMode.ADAPTIVE || modeWritten) && level != null && strengthReady) {
            sendStrength(level)
            adaptiveRequest = null
        }
    }
    LaunchedEffect(modeFeedback) {
        if (modeFeedback in listOf("timeout", "write_failed", "interrupted", "invalid")) adaptiveRequest = null
    }
    LaunchedEffect(session, adaptiveRequest) {
        if (adaptiveRequest != null) {
            delay(6500)
            adaptiveRequest = null
        }
    }
    return ListeningCommit(adaptiveRequest != null) { position ->
        if (foreground && modeReady) {
            adaptiveRequest = null
            val target = ListeningPosition.modeAt(position, adaptive)
            if (target != NoiseControlMode.ADAPTIVE) onMode(target)
            else {
                val level = ListeningPosition.adaptiveLevel(position)
                if (mode == NoiseControlMode.ADAPTIVE) {
                    if (strengthReady) onStrength(level)
                } else {
                    if (strengthReady) adaptiveRequest = level
                    onMode(target)
                }
            }
        }
    }
}
