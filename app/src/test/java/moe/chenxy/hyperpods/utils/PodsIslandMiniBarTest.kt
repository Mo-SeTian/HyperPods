package moe.chenxy.hyperpods.utils

import android.view.View
import org.junit.Test
import org.mockito.Mockito.*

class PodsIslandMiniBarTest {
    @Test fun onlyTheVisibleHyperPodsIndicatorIsHiddenWithoutChangingLayout() {
        val bar = mock(View::class.java)
        `when`(bar.visibility).thenReturn(View.VISIBLE)
        PodsIslandMiniBar.hide(bar, isHyperPods = true)
        verify(bar).visibility = View.INVISIBLE
        verify(bar, never()).setLayoutParams(any())
        verify(bar, never()).setTranslationY(anyFloat())
        verify(bar, never()).setAlpha(anyFloat())
    }

    @Test fun nativeGoneAndInvisibleIndicatorsAreNotMadeVisibleOrAddedToLayout() {
        for (visibility in listOf(View.GONE, View.INVISIBLE)) {
            val bar = mock(View::class.java)
            `when`(bar.visibility).thenReturn(visibility)
            PodsIslandMiniBar.hide(bar, isHyperPods = true)
            verify(bar, never()).setVisibility(anyInt())
        }
    }

    @Test fun otherAppsAreUntouchedEvenWhenReusingTheSameIndicator() {
        val bar = mock(View::class.java)
        `when`(bar.visibility).thenReturn(View.VISIBLE)
        PodsIslandMiniBar.hide(bar, isHyperPods = true)
        clearInvocations(bar)
        // updateMiniBar sets the next app's native visibility before our hook.
        PodsIslandMiniBar.hide(bar, isHyperPods = false)
        verifyNoInteractions(bar)
    }

    @Test fun eachNativeUpdateCanBeHiddenAgain() {
        val bar = mock(View::class.java)
        `when`(bar.visibility).thenReturn(View.VISIBLE)
        repeat(2) { PodsIslandMiniBar.hide(bar, isHyperPods = true) }
        verify(bar, times(2)).visibility = View.INVISIBLE
    }

    @Test fun missingIndicatorsAndOtherAppsAreIgnored() {
        PodsIslandMiniBar.hide(null, isHyperPods = true)
        val other = mock(View::class.java)
        PodsIslandMiniBar.hide(other, isHyperPods = false)
        verifyNoInteractions(other)
    }
}
