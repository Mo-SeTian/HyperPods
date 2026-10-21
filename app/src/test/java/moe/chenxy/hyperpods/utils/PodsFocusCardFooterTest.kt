package moe.chenxy.hyperpods.utils

import android.view.View
import org.junit.Test
import org.mockito.Mockito.*

class PodsFocusCardFooterTest {
    @Test fun hidingTheDividerMustNotChangeVisibilityOrRequestAnotherLayout() {
        val line = mock(View::class.java)
        `when`(line.alpha).thenReturn(0.6f)
        `when`(line.visibility).thenReturn(View.VISIBLE)
        PodsFocusCardFooter.apply(line)
        verify(line).alpha = 0f
        verify(line, never()).setVisibility(anyInt())
        verify(line, never()).setLayoutParams(any())
        verify(line, never()).requestLayout()
        PodsFocusCardFooter.restore(line)
        verify(line).alpha = 0.6f
    }

    @Test fun repeatedBindingRestoresTheOriginalAlphaOnce() {
        val line = mock(View::class.java)
        `when`(line.alpha).thenReturn(0.4f)
        repeat(3) { PodsFocusCardFooter.apply(line) }
        PodsFocusCardFooter.restore(line)
        verify(line, times(3)).alpha = 0f
        verify(line).alpha = 0.4f
        clearInvocations(line)
        PodsFocusCardFooter.restore(line)
        verifyNoInteractions(line)
    }

    @Test fun recyclingUsesTheNextTemplatesOwnAlpha() {
        val line = mock(View::class.java)
        `when`(line.alpha).thenReturn(0.6f, 0.2f)
        PodsFocusCardFooter.apply(line)
        PodsFocusCardFooter.restore(line)
        PodsFocusCardFooter.apply(line)
        PodsFocusCardFooter.restore(line)
        verify(line).alpha = 0.6f
        verify(line).alpha = 0.2f
    }

    @Test fun aSystemHiddenDividerIsNotMadeVisible() {
        val line = mock(View::class.java)
        `when`(line.visibility).thenReturn(View.GONE)
        PodsFocusCardFooter.apply(line)
        PodsFocusCardFooter.restore(line)
        verify(line, never()).setVisibility(anyInt())
    }

    @Test fun missingDividersAndUntouchedOtherAppViewsAreNotModified() {
        PodsFocusCardFooter.apply(null)
        PodsFocusCardFooter.restore(null)
        val other = mock(View::class.java)
        PodsFocusCardFooter.restore(other)
        verifyNoInteractions(other)
    }
}
