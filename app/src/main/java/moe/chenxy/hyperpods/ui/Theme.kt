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
        background = Color(0xFFF6F6F6), onBackground = Color(0xFF171717),
        surface = Color.White, onSurface = Color(0xFF171717),
        surfaceVariant = Color(0xFFF0F0F0),
        onBackgroundVariant = Color(0xFF66717E),
        onSurfaceVariantSummary = Color(0xFF66717E),
        dividerLine = Color(0xFFECEEF1)
    )
    val dark = darkColorScheme(
        primary = Color(0xFF82B1FF), onPrimary = Color(0xFF18212F),
        primaryVariant = Color(0xFF006BE6), onPrimaryVariant = Color.White,
        background = Color(0xFF101010), surface = Color(0xFF202020),
        onBackgroundVariant = Color(0xFFA6ABB2), onSurfaceVariantSummary = Color(0xFFA6ABB2),
        surfaceVariant = Color(0xFF2C2C2C), dividerLine = Color(0xFF333333)
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
