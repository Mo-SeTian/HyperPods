package moe.chenxy.hyperpods.utils

import android.widget.TextView
import org.junit.Test
import org.mockito.Mockito.*

class PodsFocusButtonTextTest {
    @Test fun ownButtonUsesFullFontMetricsAndRecyclingRestoresOnlyOurChanges() {
        val view = mock(TextView::class.java)
        `when`(view.includeFontPadding).thenReturn(false)
        `when`(view.isFallbackLineSpacing).thenReturn(false)
        PodsFocusButtonText.apply(view)
        PodsFocusButtonText.apply(view)
        verify(view, times(2)).includeFontPadding = true
        verify(view, times(2)).isFallbackLineSpacing = true
        PodsFocusButtonText.restore(view)
        verify(view).includeFontPadding = false
        verify(view).isFallbackLineSpacing = false
        clearInvocations(view)
        PodsFocusButtonText.restore(view)
        verifyNoInteractions(view)
    }

    @Test fun existingFontPaddingAndOtherApplicationsAreNotChanged() {
        val view = mock(TextView::class.java)
        PodsFocusButtonText.restore(view)
        verifyNoInteractions(view)
        `when`(view.includeFontPadding).thenReturn(true)
        `when`(view.isFallbackLineSpacing).thenReturn(true)
        PodsFocusButtonText.apply(view)
        PodsFocusButtonText.restore(view)
        verify(view, never()).includeFontPadding = false
        verify(view, never()).isFallbackLineSpacing = false
    }
}
