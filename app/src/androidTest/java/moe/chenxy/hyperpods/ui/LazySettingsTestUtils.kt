package moe.chenxy.hyperpods.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performScrollToNode

/** Find rows by scrolling the real lazy list, not by assuming off-screen rows exist. */
internal fun ComposeTestRule.revealSetting(matcher: SemanticsMatcher): SemanticsNodeInteraction {
    onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToNode(matcher)
    return onNode(matcher)
}

internal fun ComposeTestRule.revealSetting(text: String): SemanticsNodeInteraction = revealSetting(hasText(text))
