package moe.chenxy.hyperpods.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.ui.components.ModuleActivationGuide
import moe.chenxy.hyperpods.ui.components.ModuleRestartNotice
import moe.chenxy.hyperpods.utils.ModuleStatus
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ModuleWaitingPage(padding: PaddingValues, desktopEntry: Boolean, check: ModuleStatus.Check, onRetry: () -> Unit) {
    when (check.issue) {
        null -> WaitingPodsPage(padding, desktopEntry)
        ModuleStatus.Issue.RESTART -> WaitingPodsPage(padding, desktopEntry) { ModuleRestartNotice() }
        else -> ModuleActivationGuide(padding, check, onRetry)
    }
}

@Composable
fun WaitingPodsPage(padding: PaddingValues, desktopEntry: Boolean, notice: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
        notice?.invoke()
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Icon(painterResource(R.drawable.dashboard_headphones), null,
                    tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
                if (!desktopEntry) InfiniteProgressIndicator()
                Text(stringResource(if (desktopEntry) R.string.launcher_disconnected_title else R.string.waiting_for_pod), fontSize = 16.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                if (desktopEntry) Text(stringResource(R.string.launcher_disconnected_summary), fontSize = 14.sp,
                    textAlign = TextAlign.Center, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}
