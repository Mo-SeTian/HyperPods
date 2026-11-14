package moe.chenxy.hyperpods.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.ui.components.*
import moe.chenxy.hyperpods.utils.AACPManager
import moe.chenxy.hyperpods.utils.LauncherIcon
import moe.chenxy.hyperpods.utils.ModuleStatus
import moe.chenxy.hyperpods.utils.HyperPodsBroadcasts
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.other.GitHub

@Composable
fun AboutPage(padding: PaddingValues, podsInfo: AACPManager.Companion.AirPodsInformation? = currentPodsInfo,
    diagnostics: Bundle? = null, isActive: Boolean = true, moduleStatus: ModuleStatus.Check? = null) {
    val travel = with(LocalDensity.current) { 28.dp.roundToPx() }
    val context = LocalContext.current
    var page by rememberSaveable { mutableIntStateOf(0) }
    var showLauncher by remember { mutableStateOf(LauncherIcon.isVisible(context)) }
    var retryRequested by remember { mutableStateOf(false) }
    val foreground = rememberUiForeground { retryRequested = false }
    val syncing = diagnostics?.getString("recovery") == "recovering" || retryRequested
    val canRetry = foreground && isActive && !syncing &&
        (diagnostics?.getString("connection") in listOf("connected", "failed") || diagnostics?.getString("recovery") == "failed")
    LaunchedEffect(retryRequested) { if (retryRequested) { delay(1000); retryRequested = false } }
    val scroll = listOf(rememberLazyListState(), rememberLazyListState(), rememberLazyListState(), rememberLazyListState())
    BackHandler(isActive && page != 0) { page = if (page == 3) 2 else 0 }
    fun open(url: String) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    Box(Modifier.fillMaxSize().background(Fidelity.surface()).padding(padding).consumeWindowInsets(padding)) {
        AnimatedContent(page, modifier = Modifier.fillMaxSize().clipToBounds(),
            transitionSpec = {
                fun depth(value: Int) = if (value == 0) 0 else if (value == 3) 2 else 1
                settingsPageTransition(depth(targetState) > depth(initialState), travel).using(null)
            }, label = "AboutNavigation") { target ->
            val title = stringResource(when(target) { 1 -> R.string.dashboard_device_info; 2 -> R.string.diagnostics_title
                3 -> R.string.diagnostics_logs; else -> R.string.dashboard_about })
            val interaction = if (isActive && target == page) Modifier.semantics { paneTitle = title }
                else Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
                }
            Column(Modifier.widthIn(max = 680.dp).fillMaxSize().then(interaction)) {
                if (target != 0) FidelityTopBar(title) { page = if (target == 3) 2 else 0 }
                LazyColumn(state = scroll[target], modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 18.dp)) {
                    if (target == 1) {
                        item { podsInfo?.let { PodsInfoPage(Modifier, Modifier.padding(horizontal = 16.dp), it) }
                            ?: FidelityText(stringResource(R.string.dashboard_info_pending), Modifier.padding(24.dp)) }
                        return@LazyColumn
                    }
                    if (target == 2 || target == 3) {
                        item { ConnectionDiagnostics(diagnostics, showLogs = target == 3, onOpenLogs = { page = 3 }) }
                        return@LazyColumn
                    }
                    item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp)) {
                            FidelityText(stringResource(R.string.dashboard_about), size = 26, weight = FontWeight.Bold)
                            Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(painterResource(R.drawable.dashboard_headphones), null, Modifier.size(48.dp), tint = Fidelity.blue)
                                FidelityText("HyperPods", Modifier.padding(top = 14.dp), size = 25, weight = FontWeight.Bold)
                                FidelityText(BuildConfig.VERSION_NAME.substringBefore("-ui"), Modifier.padding(top = 8.dp), size = 12, color = Fidelity.muted())
                                val active = moduleStatus != null && !moduleStatus.checking && moduleStatus.issue == null
                                val moduleText = stringResource(if (active) R.string.fidelity_module_active else if (moduleStatus?.checking == true)
                                    R.string.module_setup_checking else R.string.fidelity_module_check)
                                Row(Modifier.heightIn(min = 48.dp).clickable { page = 2 }, horizontalArrangement = Arrangement.spacedBy(7.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(7.dp).background(if (active) Fidelity.blue else Fidelity.muted(), androidx.compose.foundation.shape.CircleShape))
                                    FidelityText(moduleText, size = 13, color = Fidelity.muted())
                                }
                                Box(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .background(if (canRetry) Fidelity.blue else Fidelity.panel())
                                    .clickable(enabled = canRetry, role = Role.Button) {
                                        if (!retryRequested) {
                                            retryRequested = true
                                            HyperPodsBroadcasts.send(context, Intent(HyperPodsAction.ACTION_PODS_STATUS_RETRY), HyperPodsBroadcasts.BLUETOOTH)
                                        }
                                    }.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                                    FidelityText(stringResource(if (syncing) R.string.minimal_syncing else R.string.diagnostics_retry),
                                        size = 16, weight = FontWeight.SemiBold, color = if (canRetry) Color.White else Fidelity.muted())
                                }
                                val retryDescription = stringResource(R.string.diagnostics_retry_summary)
                                FidelityText(stringResource(R.string.minimal_retry_summary), Modifier.padding(top = 10.dp)
                                    .semantics { contentDescription = retryDescription },
                                    size = 12, color = Fidelity.muted(), align = TextAlign.Center)
                            }
                            MinimalLink(stringResource(R.string.dashboard_device_info),
                                podsInfo?.let { "${it.name} · ${it.modelNumber}" } ?: stringResource(R.string.dashboard_info_pending)) { page = 1 }
                            FidelityDivider()
                            MinimalLink(stringResource(R.string.diagnostics_title), stringResource(R.string.fidelity_diagnostics_summary)) { page = 2 }
                        }
                    }
                    item {
                        FidelitySection(stringResource(R.string.dashboard_app_settings), Modifier.padding(top = 12.dp))
                        FidelityPanel(Modifier.padding(horizontal = 24.dp)) {
                            MinimalToggle(stringResource(R.string.fidelity_launcher), stringResource(R.string.fidelity_launcher_summary),
                                showLauncher, onCheckedChange = { visible ->
                                    runCatching { LauncherIcon.setVisible(context, visible) }
                                        .onSuccess { showLauncher = LauncherIcon.isVisible(context) }
                                        .onFailure { android.widget.Toast.makeText(context, R.string.launcher_icon_failed, android.widget.Toast.LENGTH_LONG).show() }
                                })
                        }
                    }
                    item {
                        FidelitySection(stringResource(R.string.fidelity_project_support), Modifier.padding(top = 14.dp))
                        Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AuthorColumn(Modifier.weight(1f), stringResource(R.string.dashboard_author), "Art_Chen", "Art-Chen / HyperPods",
                                { open("https://github.com/Art-Chen/HyperPods") }, { open("https://afdian.com/a/art_chen?tab=home") })
                            AuthorColumn(Modifier.weight(1f), stringResource(R.string.dashboard_fork_author), "Mo-SeTian", "Mo-SeTian / HyperPods",
                                { open("https://github.com/Mo-SeTian/HyperPods") }, { open("https://afdian.com/a/mosetian") })
                        }
                    }
                    item {
                        FidelityPanel(Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp)) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { open("https://github.com/librepods-org/librepods") }
                                .padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                                Icon(painterResource(R.drawable.ui_code_xml), null, Modifier.size(25.dp), tint = Fidelity.muted())
                                Column(Modifier.weight(1f)) {
                                    FidelityText(stringResource(R.string.redesign_sources), size = 14, weight = FontWeight.SemiBold)
                                    FidelityText("AGPL-3.0", size = 11, color = Fidelity.muted())
                                }
                                Icon(painterResource(R.drawable.ui_chevron_left), null, Modifier.size(14.dp).graphicsLayer { rotationZ = 180f }, tint = Fidelity.muted())
                            }
                        }
                    }
                    item { Box(Modifier.fillMaxWidth().padding(top = 18.dp), contentAlignment = Alignment.Center) {
                        FidelityText(stringResource(R.string.fidelity_build, BuildConfig.VERSION_CODE, BuildConfig.BUILD_REVISION), size = 11, color = Fidelity.muted())
                    } }
                }
            }
        }
    }
}

@Composable
private fun AuthorColumn(modifier: Modifier, title: String, name: String, repo: String, onGithub: () -> Unit, onDonate: () -> Unit) {
    FidelityPanel(modifier) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)) {
            FidelityText(title, size = 12, color = Fidelity.muted())
            FidelityText(name, Modifier.padding(top = 2.dp), size = 15, weight = FontWeight.Bold)
            FidelityText(repo, Modifier.padding(top = 2.dp), size = 11, color = Fidelity.muted(), maxLines = 1)
            FidelityDivider(Modifier.padding(top = 8.dp, bottom = 3.dp))
            AuthorLink("GitHub", "$name GitHub", rememberVectorPainter(MiuixIcons.Other.GitHub), onGithub)
            val donate = stringResource(R.string.fidelity_afdian)
            AuthorLink(donate, "$name $donate", fidelityPainter(R.drawable.fidelity_afdian), onDonate)
        }
    }
}

@Composable
private fun AuthorLink(title: String, description: String, icon: androidx.compose.ui.graphics.painter.Painter, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = description }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = Fidelity.accent())
        FidelityText(title, Modifier.weight(1f), size = 14, weight = FontWeight.SemiBold, color = Fidelity.accent())
        Icon(painterResource(R.drawable.ui_chevron_left), null, Modifier.size(13.dp).graphicsLayer { rotationZ = 180f }, tint = Fidelity.muted())
    }
}
