package moe.chenxy.hyperpods.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Check the actual theme delivered to controls in both light and dark settings. */
@RunWith(Parameterized::class)
class SettingsThemeTest(private val colorMode: Int) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "colorMode={0}")
        fun modes() = listOf(arrayOf(1), arrayOf(2))
    }

    @get:Rule val compose = createAndroidComposeRule<DashboardPreviewActivity>()

    @Test fun accentsAreBlueAndSelectedTextRemainsReadable() {
        var pairs = emptyList<Pair<Color, Color>>()
        compose.runOnUiThread {
            compose.activity.setContent {
                AppTheme(colorMode) {
                    val colors = MiuixTheme.colorScheme
                    SideEffect {
                        pairs = listOf(colors.primary to colors.onPrimary,
                            colors.primaryVariant to colors.onPrimaryVariant)
                    }
                }
            }
        }
        compose.runOnIdle {
            assertTrue(pairs.isNotEmpty())
            pairs.forEach { (background, foreground) ->
                assertTrue("Accent must be blue, not green",
                    background.blue > background.green && background.blue > background.red)
                val brighter = maxOf(background.luminance(), foreground.luminance())
                val darker = minOf(background.luminance(), foreground.luminance())
                assertTrue("Selected text needs at least 4.5:1 contrast",
                    (brighter + .05f) / (darker + .05f) >= 4.5f)
            }
        }
    }
}
