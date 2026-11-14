package moe.chenxy.hyperpods.ui.components

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import moe.chenxy.hyperpods.R
import top.yukonga.miuix.kmp.basic.Icon

/** Shared alignment and typography for the prototype's plain setting rows. */
@Composable
private fun SettingRow(title: String, summary: String?, modifier: Modifier, minHeight: Int,
    titleColor: Color = Fidelity.ink(),
    trailing: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = minHeight.dp).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            FidelityText(title, size = 16, weight = FontWeight.SemiBold, color = titleColor)
            if (!summary.isNullOrBlank()) FidelityText(summary, size = 12, color = Fidelity.muted())
        }
        trailing()
    }
}

@Composable
fun MinimalToggle(title: String, summary: String? = null, checked: Boolean, enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit) {
    SettingRow(title, summary, Modifier.toggleable(checked, enabled, Role.Switch, onValueChange = onCheckedChange)
        .semantics { contentDescription = title }.alpha(if (enabled) 1f else .4f), 58) {
        FidelitySwitch(checked, enabled)
    }
}

@Composable
fun MinimalLink(title: String, summary: String? = null, value: String? = null, enabled: Boolean = true,
    titleColor: Color = Fidelity.ink(), onClick: () -> Unit) {
    SettingRow(title, summary, Modifier.clickable(enabled, role = Role.Button, onClick = onClick)
        .alpha(if (enabled) 1f else .4f), if (summary.isNullOrBlank()) 48 else 58, titleColor) {
        if (value != null) FidelityText(value, Modifier.widthIn(max = 120.dp), size = 14, color = Fidelity.muted(), maxLines = 2)
        Icon(painterResource(R.drawable.ui_chevron_left), null,
            Modifier.size(20.dp).rotate(180f), tint = Fidelity.muted())
    }
}

/** Short diagnostics share a right edge; real text measurements move long values
 * below the label, including translated strings and larger system fonts. */
@Composable
fun DiagnosticField(title: String, value: String, stacked: Boolean = false) {
    if (stacked) Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)
        .semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FidelityText(title, size = 16, weight = FontWeight.SemiBold)
        FidelityText(value, size = 12, color = Fidelity.muted())
    } else SubcomposeLayout(Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics(mergeDescendants = true) {}) { constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val label = subcompose("label") { FidelityText(title, size = 14) }.single().measure(loose)
        val detail = subcompose("value") { FidelityText(value, size = 14, color = Fidelity.muted()) }.single().measure(loose)
        val inline = '\n' !in value && label.width + 16.dp.roundToPx() + detail.width <= constraints.maxWidth
        val height = if (inline) maxOf(label.height, detail.height) else label.height + 4.dp.roundToPx() + detail.height
        layout(constraints.maxWidth, height) {
            label.placeRelative(0, if (inline) (height - label.height) / 2 else 0)
            detail.placeRelative(if (inline) constraints.maxWidth - detail.width else 0,
                if (inline) (height - detail.height) / 2 else label.height + 4.dp.roundToPx())
        }
    }
}

@Composable
fun MinimalDropdown(title: String, items: List<String>, selectedIndex: Int, enabled: Boolean = true,
    summary: String? = null, showValue: Boolean = true, onSelectedIndexChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val visibility = remember { MutableTransitionState(false) }.apply { targetState = expanded }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Box {
        SettingRow(title, summary, Modifier.clickable(enabled, role = Role.DropdownList) { expanded = true }
            .alpha(if (enabled) 1f else .4f), 56) {
            if (showValue) FidelityText(items.getOrNull(selectedIndex) ?: stringResource(R.string.settings_reading),
                Modifier.widthIn(max = 140.dp), size = 14, color = Fidelity.muted(), maxLines = 2)
            Icon(painterResource(R.drawable.ui_chevron_left), null,
                Modifier.size(17.dp).rotate(-90f), tint = Fidelity.muted())
        }
        if ((visibility.currentState || visibility.targetState) && enabled) Popup(Alignment.TopEnd, onDismissRequest = { expanded = false },
            properties = PopupProperties(focusable = true)) {
            AnimatedVisibility(visibility, enter = fadeIn(tween(120)), exit = fadeOut(tween(100))) {
            Column(Modifier.widthIn(min = 180.dp, max = 220.dp).shadow(4.dp, RoundedCornerShape(16.dp))
                .background(Fidelity.surface(), RoundedCornerShape(16.dp)).padding(6.dp).selectableGroup()) {
                items.forEachIndexed { index, text ->
                    FidelityText(text, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .selectable(selectedIndex == index, enabled = expanded && enabled, role = Role.RadioButton) {
                            expanded = false
                            onSelectedIndexChange(index)
                        }.heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 14.dp),
                        color = if (selectedIndex == index) Fidelity.accent() else Fidelity.ink(),
                        weight = if (selectedIndex == index) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
            }
        }
    }
}

@Composable
fun MinimalSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp)) {
        FidelityText(title, Modifier.padding(bottom = 8.dp), size = 13, color = Fidelity.muted(), weight = FontWeight.Medium)
        content()
    }
}

/** Cancel queued UI work synchronously when the activity leaves the foreground. */
@Composable
fun rememberUiForeground(onBackground: () -> Unit): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val cancel by rememberUpdatedState(onBackground)
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!foreground) cancel()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return foreground
}

/** One motion treatment for pushed settings pages, including deeper routes. */
internal fun settingsPageTransition(forward: Boolean, travel: Int): ContentTransform {
    val direction = if (forward) 1 else -1
    return (fadeIn(tween(220)) + slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { direction * travel })
        .togetherWith(fadeOut(tween(120)) + slideOutHorizontally(tween(220, easing = FastOutSlowInEasing)) { -direction * travel / 2 })
}
