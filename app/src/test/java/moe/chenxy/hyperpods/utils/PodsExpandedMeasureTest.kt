package moe.chenxy.hyperpods.utils

import android.view.View
import android.view.ViewGroup.LayoutParams
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class PodsExpandedMeasureTest {
    private fun view(height: Int) = mock(View::class.java).also {
        // Android's stub constructor does not populate fields in JVM tests.
        `when`(it.layoutParams).thenReturn(LayoutParams(320, height).apply {
            width = 320
            this.height = height
        })
    }

    @Test fun anOldFixedHeightIsReleasedOnlyDuringOurNaturalMeasurement() {
        val view = view(120)
        PodsExpandedMeasure.prepare(view, isHyperPods = true)
        assertEquals(LayoutParams.WRAP_CONTENT, view.layoutParams.height)
        assertEquals(320, view.layoutParams.width)
        verify(view).setLayoutParams(any())
        PodsExpandedMeasure.finish(view)
        assertEquals(120, view.layoutParams.height)
    }

    @Test fun theNativeResultIncludingItsHeightLimitIsKept() {
        for (nativeHeight in listOf(100, 140)) {
            val view = view(120)
            PodsExpandedMeasure.prepare(view, isHyperPods = true)
            view.layoutParams.height = nativeHeight
            PodsExpandedMeasure.finish(view)
            assertEquals(nativeHeight, view.layoutParams.height)
        }
    }

    @Test fun otherAppsAndNativeFlexibleHeightsAreNotModified() {
        val other = view(120)
        PodsExpandedMeasure.prepare(other, isHyperPods = false)
        PodsExpandedMeasure.finish(other)
        assertEquals(120, other.layoutParams.height)
        verify(other, never()).setLayoutParams(any())
        for (height in listOf(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, 0)) {
            val view = view(height)
            PodsExpandedMeasure.prepare(view, isHyperPods = true)
            PodsExpandedMeasure.finish(view)
            assertEquals(height, view.layoutParams.height)
            verify(view, never()).setLayoutParams(any())
        }
    }

    @Test fun missingViewsAreIgnored() {
        PodsExpandedMeasure.prepare(null, isHyperPods = true)
        PodsExpandedMeasure.finish(null)
    }
}
