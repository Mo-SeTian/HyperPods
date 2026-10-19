package moe.chenxy.hyperpods.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.ui.components.ConnectionDiagnostics
import top.yukonga.miuix.kmp.basic.BasicComponent
import moe.chenxy.hyperpods.ui.components.DashboardGroup as Card
import moe.chenxy.hyperpods.ui.components.DashboardSection
import moe.chenxy.hyperpods.ui.components.DashboardLink
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AboutPage(
    padding: PaddingValues,
    diagnostics: Bundle? = null,
) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 680.dp).fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item(key = "app_info") {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
                        modifier = Modifier.size(72.dp), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary))
                    Column(
                        modifier = Modifier
                            .weight(1f),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = "HyperPods",
                            modifier = Modifier,
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp
                        )
                        Text(
                            text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_REVISION})",
                            modifier = Modifier,
                            fontWeight = FontWeight.Normal,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }
            item(key = "project") {
                DashboardSection(stringResource(R.string.dashboard_project))
                Card {
                    BasicComponent(
                        title = stringResource(R.string.dashboard_author),
                        summary = "Art_Chen",
                        enabled = true
                    )
                    DashboardLink(
                        title = "GitHub",
                        summary = "https://github.com/Art-Chen/HyperPods",
                        onClick = {
                            Intent(Intent.ACTION_VIEW).apply {
                                this.data = Uri.parse("https://github.com/Art-Chen/HyperPods")
                                context.startActivity(this)
                            }
                        }
                    )
                    DashboardLink(
                        title = stringResource(R.string.donate),
                        summary = stringResource(R.string.donate_detail),
                        onClick = {
                            Intent(Intent.ACTION_VIEW).apply {
                                this.data = Uri.parse("https://afdian.com/a/art_chen?tab=home")
                                context.startActivity(this)
                            }
                        }
                    )
                }
            }
            item(key = "diagnostics") {
                DashboardSection(stringResource(R.string.diagnostics_title))
                Card {
                    ConnectionDiagnostics(diagnostics)
                }

            }
        }
    }
}
