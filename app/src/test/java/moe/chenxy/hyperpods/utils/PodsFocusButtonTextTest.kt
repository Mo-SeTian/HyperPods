package moe.chenxy.hyperpods.utils

import android.widget.TextView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.*

class PodsFocusButtonTextTest {
    @Test fun buttonContentsMeasureTheirOwnHeightInsteadOfFillingACachedParentHeight() {
        val view = mock(TextView::class.java)
        val row = mock(LinearLayout::class.java)
        val params = FrameLayout.LayoutParams(100, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        `when`(view.parent).thenReturn(row)
        `when`(row.layoutParams).thenReturn(params)
        repeat(2) { PodsFocusButtonText.apply(view) }
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, params.height)
        PodsFocusButtonText.restore(view)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, params.height)
    }

    @Test fun anExplicitSystemHeightIsNotChanged() {
        val view = mock(TextView::class.java)
        val row = mock(LinearLayout::class.java)
        val params = FrameLayout.LayoutParams(100, 40).apply { height = 40 }
        `when`(view.parent).thenReturn(row)
        `when`(row.layoutParams).thenReturn(params)
        PodsFocusButtonText.apply(view)
        PodsFocusButtonText.restore(view)
        assertEquals(40, params.height)
        verify(row, never()).setLayoutParams(any())
    }
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
