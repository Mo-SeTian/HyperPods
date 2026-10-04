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
        primary = Color(0xFF006BE6), onPrimary = Color.White,
        primaryVariant = Color(0xFF006BE6), onPrimaryVariant = Color.White,
        background = Color(0xFFF7F8FA), onBackground = Color(0xFF18212F),
        surface = Color(0xFFF7F8FA), onSurface = Color(0xFF18212F),
        surfaceVariant = Color(0xFFEEF1F5),
        onSurfaceVariantSummary = Color(0xFF687380),
        dividerLine = Color(0xFFDFE4EB)
    )
    val dark = darkColorScheme(
        primary = Color(0xFF82B1FF), onPrimary = Color(0xFF18212F),
        primaryVariant = Color(0xFF006BE6), onPrimaryVariant = Color.White,
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
