package moe.chenxy.hyperpods.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

@Composable
fun AppTheme(
    colorMode: Int = 0,
    content: @Composable () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    val light = lightColorScheme(
        primary = Color(0xFF147F66), onPrimary = Color.White,
        primaryVariant = Color(0xFF65D9BA), onPrimaryVariant = Color(0xFF182421),
        background = Color(0xFFF8FAF9), onBackground = Color(0xFF182421),
        surface = Color(0xFFF8FAF9), onSurface = Color(0xFF182421),
        surfaceVariant = Color(0xFFEEF2EF),
        onSurfaceVariantSummary = Color(0xFF68736F),
        dividerLine = Color(0xFFDFE5E1)
    )
    val dark = darkColorScheme(
        primary = Color(0xFF65D9BA), onPrimary = Color(0xFF182421),
        primaryVariant = Color(0xFF65D9BA), onPrimaryVariant = Color(0xFF182421),
        background = Color(0xFF111517), surface = Color(0xFF111517),
        surfaceVariant = Color(0xFF202629), dividerLine = Color(0xFF2D3438)
    )
    return MiuixTheme(
        colors = when (colorMode) {
            1 -> light
            2 -> dark
            else -> if (darkTheme) dark else light
        },
        content = content
    )
}
