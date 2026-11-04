package moe.chenxy.hyperpods.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import moe.chenxy.hyperpods.utils.HyperPodsBroadcasts
import moe.chenxy.hyperpods.utils.ModuleStatus
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import java.util.UUID

@Stable
class ModuleCheckState {
    var check by mutableStateOf(ModuleStatus.Check())
        internal set
    var request by mutableStateOf(UUID.randomUUID().toString())
        private set

    fun retry() {
        check = ModuleStatus.Check(previousIssue = check.issue)
        request = UUID.randomUUID().toString()
    }
}

/** Read-only probes live with the UI and never open or reset a headphone control channel. */
@Composable
fun rememberModuleCheck(): ModuleCheckState {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = remember(context) { ModuleCheckState() }
    val request = state.request
    DisposableEffect(context, request) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (state.request != request) return
                ModuleStatus.read(context, this, intent, request)?.let {
                    val reports = state.check.reports + (it.packageName to it)
                    state.check = state.check.copy(reports = reports,
                        checking = state.check.checking && !reports.keys.containsAll(ModuleStatus.packages))
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(HyperPodsAction.ACTION_MODULE_STATUS), Context.RECEIVER_EXPORTED)
        ModuleStatus.packages.forEach { host ->
            HyperPodsBroadcasts.send(context, Intent(HyperPodsAction.ACTION_MODULE_STATUS_REQUEST).putExtra("request", request), host)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }
    LaunchedEffect(request) {
        delay(2500)
        if (state.request == request) state.check = state.check.copy(checking = false)
    }
    DisposableEffect(lifecycle) {
        // Returning from the module manager must refresh the result without polling in the background.
        var firstResume = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (firstResume) firstResume = false else state.retry()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return state
}
