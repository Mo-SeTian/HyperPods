package moe.chenxy.hyperpods.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*

import androidx.compose.foundation.shape.CircleShape

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.draw.clip

import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.utils.AirPodsBase
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Measured against the selected 852×1846 mocks at a 393 dp content width. */
object Fidelity {
    val blue = Color(0xFF007AFF)
    @Composable fun dark() = MiuixTheme.colorScheme.surface.luminance() < .2f
    @Composable fun ink() = if (dark()) Color(0xFFF0F4FC) else Color(0xFF0A1018)
    @Composable fun muted() = if (dark()) Color(0xFFADBBD0) else Color(0xFF657185)
    @Composable fun accent() = if (dark()) Color(0xFF86B7FF) else Color(0xFF006DE0)
    @Composable fun panel() = if (dark()) Color(0xFF23272E) else Color(0xFFF2F4F8)
    @Composable fun divider() = if (dark()) Color(0xFF3B424D) else Color(0xFFE2E6EC)
    @Composable fun surface() = if (dark()) Color(0xFF101215) else Color.White
}

@OptIn(ExperimentalTextApi::class)
private val fidelityFont = FontFamily(
    Font(R.font.misans_vf, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.misans_vf, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.misans_vf, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.misans_vf, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(660))),
)

@Composable
fun FidelityText(text: String, modifier: Modifier = Modifier, size: Int = 14, weight: FontWeight = FontWeight.Normal,
    color: Color = Fidelity.ink(), align: TextAlign? = null, maxLines: Int = Int.MAX_VALUE) {
    BasicText(text, modifier, style = TextStyle(fontFamily = fidelityFont, fontSize = size.sp,
        lineHeight = (size * 1.4f).sp, fontWeight = weight, color = color, textAlign = align ?: TextAlign.Start,
        platformStyle = PlatformTextStyle(includeFontPadding = false)), maxLines = maxLines,
        overflow = TextOverflow.Ellipsis)
}

/** Normalize transparent padding at draw time; preserve the original generated files. */
@Composable
fun fidelityPainter(res: Int): Painter {
    val bounds = when(res) {
        R.drawable.fidelity_afdian -> intArrayOf(132,256,1105,1050)
        R.drawable.transparency -> intArrayOf(40,22,160,178)
        R.drawable.noise_cancellation -> intArrayOf(43,27,156,173)
        R.drawable.adaptive -> intArrayOf(34,20,183,180)
        else -> null
    }
    return if (bounds == null) painterResource(res) else BitmapPainter(ImageBitmap.imageResource(res),
        IntOffset(bounds[0],bounds[1]), IntSize(bounds[2]-bounds[0],bounds[3]-bounds[1]))
}

@Composable
fun FidelitySwitch(checked: Boolean, enabled: Boolean = true) {
    val color by animateColorAsState(if (checked) Fidelity.blue else
        if (Fidelity.dark()) Color(0xFF68768B) else Color(0xFFD8DDE4), tween(150), label = "SwitchTrack")
    val x by animateDpAsState(if (checked) 22.dp else 2.dp, tween(150), label = "SwitchThumb")
    Box(Modifier.size(44.dp, 24.dp).clip(CircleShape).background(color).clearAndSetSemantics {}) {
        Box(Modifier.offset(x, 2.dp).size(20.dp).background(Color.White, CircleShape))
    }
}

@Composable
fun FidelitySection(title: String, modifier: Modifier = Modifier) {
    FidelityText(title, modifier.padding(horizontal = 24.dp, vertical = 10.dp), size = 13, color = Fidelity.muted(), weight = FontWeight.Medium)
}

@Composable
fun FidelityPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), content = content)
}

@Composable
fun FidelityDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Fidelity.divider()))
}

@Composable
fun FidelityTopBar(title: String, onBack: (() -> Unit)? = null) {
    val backLabel = stringResource(R.string.dashboard_back)
    Box(Modifier.fillMaxWidth().heightIn(min = 52.dp), contentAlignment = Alignment.Center) {
        FidelityText(title, Modifier.padding(horizontal = 56.dp), size = 17, weight = FontWeight.SemiBold)
        if (onBack != null) Box(Modifier.align(Alignment.CenterStart).padding(start = 4.dp).size(48.dp)
            .clip(CircleShape).clickable(role = Role.Button, onClick = onBack)
            .semantics { contentDescription = backLabel }, contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ui_chevron_left), null, Modifier.size(23.dp), tint = Fidelity.ink())
        }
    }
}

@Composable
fun FidelityConnected(text: String, ready: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(6.dp).background(if (ready) Fidelity.blue else Fidelity.muted(), CircleShape))
        FidelityText(text, size = 14, color = Fidelity.muted())
    }
}

@Composable
fun FidelityProduct(model: AirPodsBase?, showCase: Boolean, modifier: Modifier = Modifier) {
    if (model?.name?.startsWith("AirPods Pro") == true) {
        Image(painterResource(if (showCase) R.drawable.fidelity_pro2_hero_v2 else R.drawable.fidelity_pro2_buds),
            null, modifier, contentScale = ContentScale.Fit)
    } else Box(modifier, contentAlignment = Alignment.Center) {
        if (showCase) Image(painterResource(when (model?.name) {
            "AirPods 1", "AirPods 2" -> R.drawable.ui_classic_case
            "AirPods 3" -> R.drawable.ui_open_case
            else -> model?.caseRes ?: R.drawable.airpods_pro_case_notification
        }), null,
            Modifier.fillMaxWidth(.58f).fillMaxHeight(.68f).align(Alignment.TopCenter))
        ModelArtwork(model, Modifier.fillMaxWidth(.9f).fillMaxHeight(.77f).align(Alignment.BottomCenter))
    }
}

@Composable
fun FidelityMicrophoneRow(title: String, items: List<String>, selected: Int, enabled: Boolean,
    onSelect: (Int) -> Unit) {
    MinimalDropdown(title, items, selected, enabled, onSelectedIndexChange = onSelect)
}
