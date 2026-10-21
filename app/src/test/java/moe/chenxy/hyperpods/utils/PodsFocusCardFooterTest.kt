package moe.chenxy.hyperpods.utils

import android.view.View
import android.widget.RelativeLayout
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class PodsFocusCardFooterTest {
    private fun row(top: Int): Pair<View, RelativeLayout.LayoutParams> {
        val params = RelativeLayout.LayoutParams(200, 80).apply { topMargin = top; bottomMargin = 9 }
        val view = mock(View::class.java)
        `when`(view.layoutParams).thenReturn(params)
        return view to params
    }

    @Test fun removingTheDividerAlsoMovesTheNameAndButtonUpWithoutChangingBottomSpacing() {
        val line = mock(View::class.java)
        `when`(line.visibility).thenReturn(View.VISIBLE)
        val (name, nameParams) = row(12)
        val (button, buttonParams) = row(57)
        PodsFocusCardFooter.apply(line, name, button, 24)
        verify(line).visibility = View.GONE
        assertEquals(0, nameParams.topMargin)
        assertEquals(33, buttonParams.topMargin)
        assertEquals(9, nameParams.bottomMargin)
        assertEquals(9, buttonParams.bottomMargin)
    }

    @Test fun repeatedBindingDoesNotAccumulateTheUpwardOffsetAndRecyclingRestoresTheSystemMargins() {
        val (name, params) = row(57)
        repeat(3) { PodsFocusCardFooter.apply(null, name, null, 24) }
        assertEquals(33, params.topMargin)
        PodsFocusCardFooter.restore(null, name, null)
        assertEquals(57, params.topMargin)
        params.topMargin = 66 // the next app's original bind chooses a new margin
        PodsFocusCardFooter.restore(null, name, null)
        assertEquals(66, params.topMargin)
    }

    @Test fun dividerVisibilityIsRestoredAndMissingViewsAreHarmless() {
        val line = mock(View::class.java)
        `when`(line.visibility).thenReturn(View.INVISIBLE)
        PodsFocusCardFooter.apply(line, null, null, 24)
        PodsFocusCardFooter.restore(line, null, null)
        verify(line).visibility = View.INVISIBLE
        PodsFocusCardFooter.apply(null, null, null, 24)
        PodsFocusCardFooter.restore(null, null, null)
    }

    @Test fun untouchedMarginsAndOtherAppViewsAreNotOverridden() {
        val (name, params) = row(0)
        PodsFocusCardFooter.restore(null, name, null)
        verify(name, never()).setLayoutParams(any())
        PodsFocusCardFooter.apply(null, name, null, 24)
        assertEquals(0, params.topMargin)
        params.topMargin = 11
        PodsFocusCardFooter.restore(null, name, null)
        assertEquals(11, params.topMargin)
    }
}
