package moe.chenxy.hyperpods.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.utils.ModuleStatus
import moe.chenxy.hyperpods.utils.HyperPodsBroadcasts
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

private fun issueTitle(issue: ModuleStatus.Issue?) = when (issue) {
    ModuleStatus.Issue.RESTART -> R.string.module_setup_restart_title
    ModuleStatus.Issue.PARTIAL -> R.string.module_setup_partial_title
    else -> R.string.module_setup_title
}

private fun issueSummary(issue: ModuleStatus.Issue?) = when (issue) {
    ModuleStatus.Issue.RESTART -> R.string.module_setup_restart_summary
    ModuleStatus.Issue.PARTIAL -> R.string.module_setup_partial_summary
    else -> R.string.module_setup_summary
}

@Composable
private fun MissingHosts(check: ModuleStatus.Check) {
    if (check.issue == ModuleStatus.Issue.PARTIAL && !check.checking) {
        val names = check.missing.map { host -> stringResource(when (host) {
            HyperPodsBroadcasts.BLUETOOTH -> R.string.module_bluetooth
            HyperPodsBroadcasts.XIAOMI_BLUETOOTH -> R.string.module_xiaomi_bluetooth
            else -> R.string.module_system_ui
        }) }.joinToString("、")
        Text(stringResource(R.string.module_setup_missing, names), fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

/** The selected onboarding design: an explanation, three steps, and two real actions. */
@Composable
fun ModuleActivationGuide(padding: PaddingValues, check: ModuleStatus.Check, onRetry: () -> Unit) {
    val secondary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxHeight().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(26.dp))
            Box(Modifier.size(92.dp).background(if (MiuixTheme.colorScheme.background.luminance() < .5f)
                Color(0xFF3E301C) else Color(0xFFFFEED5), CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.module_shield), null, tint = Color(0xFFF29B00), modifier = Modifier.size(48.dp))
            }
            Text(stringResource(issueTitle(check.issue)), fontSize = 30.sp, lineHeight = 38.sp,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 18.dp))
            Text(stringResource(issueSummary(check.issue)), fontSize = 16.sp, lineHeight = 24.sp,
                textAlign = TextAlign.Center, color = secondary,
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 12.dp))
            MissingHosts(check)
            Spacer(Modifier.height(28.dp))
            DashboardGroup {
                listOf(R.string.module_setup_step_enable, R.string.module_setup_step_scope, R.string.module_setup_step_restart)
                    .forEachIndexed { index, id ->
                        if (index > 0) DashboardDivider(Modifier.padding(horizontal = 16.dp))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Box(Modifier.size(28.dp).background(MiuixTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                                Text("${index + 1}", color = MiuixTheme.colorScheme.onPrimary, fontSize = 16.sp)
                            }
                            Text(stringResource(id), fontSize = 16.sp, lineHeight = 22.sp,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        }
                    }
            }
            Spacer(Modifier.height(40.dp))
            ModuleSetupActions(check.checking, onRetry)
        }
    }
}

/** A missing self-check report must not hide already-working headphone controls. */
@Composable
fun ModuleWarningCard(check: ModuleStatus.Check, onRetry: () -> Unit) {
    DashboardGroup(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (check.issue == ModuleStatus.Issue.NO_REPORT)
                R.string.module_setup_control_report_title else issueTitle(check.issue)),
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(if (check.issue == ModuleStatus.Issue.NO_REPORT)
                R.string.module_setup_control_available else issueSummary(check.issue)), fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            MissingHosts(check)
            ModuleSetupActions(check.checking, onRetry)
        }
    }
}

@Composable
private fun ModuleSetupActions(checking: Boolean, onRetry: () -> Unit) {
    val showHelp = remember { mutableStateOf(false) }
    Button(onClick = { showHelp.value = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        colors = ButtonDefaults.buttonColorsPrimary()) {
        Text(stringResource(R.string.module_setup_help), color = MiuixTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
    TextButton(stringResource(if (checking) R.string.module_setup_checking else R.string.module_setup_retry),
        onClick = onRetry, enabled = !checking, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp),
        colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent,
            textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary))
    CustomSuperBottomSheet(showHelp, title = stringResource(R.string.module_setup_help_title),
        onDismissRequest = { showHelp.value = false }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(stringResource(R.string.module_setup_requirements), fontSize = 15.sp, lineHeight = 23.sp)
            Text(stringResource(R.string.module_setup_enable_detail), fontSize = 15.sp, lineHeight = 23.sp)
            Text(stringResource(R.string.module_setup_scope_detail), fontSize = 15.sp, lineHeight = 23.sp)
            // Match the module manifest's recommended scope instead of maintaining another package list.
            Text(stringArrayResource(R.array.xposedscope).joinToString("\n"), fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(stringResource(R.string.module_setup_restart_detail), fontSize = 15.sp, lineHeight = 23.sp)
            Button(onClick = { showHelp.value = false; onRetry() }, enabled = !checking, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary()) {
                Text(stringResource(R.string.module_setup_retry), color = MiuixTheme.colorScheme.onPrimary)
            }
        }
    }
}
