package moe.chenxy.hyperpods.utils

import android.view.View
import android.widget.LinearLayout
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class PodsFocusCardSpacingTest {
    private fun view(margin: Int = 3): Pair<View, LinearLayout.LayoutParams> {
        val params = LinearLayout.LayoutParams(100, 30).apply {
            bottomMargin = margin
            topMargin = 7
        }
        val view = mock(View::class.java)
        `when`(view.layoutParams).thenReturn(params)
        return view to params
    }

    @Test fun aSingleBatteryRowReservesTheSystemGapWithoutChangingTheTopMargin() {
        val (view, params) = view()
        PodsFocusCardSpacing.apply(view, true, "", 36)
        assertEquals(36, params.bottomMargin) // System 12dp at density 3.
        assertEquals(7, params.topMargin)
        verify(view).layoutParams = params
    }

    @Test fun aCardWithCaseBatteryKeepsTheOriginalSpacing() {
        val (view, params) = view()
        PodsFocusCardSpacing.apply(view, true, "Charging case: 91%", 36)
        assertEquals(3, params.bottomMargin)
        verify(view, never()).setLayoutParams(any())
    }

    @Test fun aSingleRowWithoutAContentFieldUsesTheSameGap() {
        val (view, params) = view()
        PodsFocusCardSpacing.apply(view, true, null, 36)
        assertEquals(36, params.bottomMargin)
    }

    @Test fun aLowBatteryDescriptionDoesNotAddAnotherGap() {
        val (view, params) = view()
        PodsFocusCardSpacing.apply(view, true, "Low battery: left 20%", 36)
        assertEquals(3, params.bottomMargin)
    }

    @Test fun togglingCaseAndWarningRowsRestoresSpacingOnTheSameView() {
        val (view, params) = view()
        for (content in listOf("", "Charging case: 91%", "", "Low battery: left 20%", "")) {
            PodsFocusCardSpacing.apply(view, true, content, 36)
            assertEquals(if (content.isEmpty()) 36 else 3, params.bottomMargin)
        }
    }

    @Test fun recyclingIntoAnotherAppRestoresTheOriginalMargin() {
        val (view, params) = view()
        PodsFocusCardSpacing.apply(view, true, "", 36)
        assertEquals(36, params.bottomMargin)
        PodsFocusCardSpacing.apply(view, false, "", 0)
        assertEquals(3, params.bottomMargin)
        clearInvocations(view)
        PodsFocusCardSpacing.apply(view, false, "", 36)
        verify(view, never()).setLayoutParams(any())
    }

    @Test fun unrelatedNotificationsAreNotModified() {
        val (view, params) = view()
        PodsFocusCardSpacing.apply(view, false, null, 36)
        assertEquals(3, params.bottomMargin)
        verify(view, never()).setLayoutParams(any())
    }

    @Test fun theNextAppsOwnSpacingIsPreservedAfterThePreviousChangeIsRemoved() {
        val (view, params) = view()
        PodsFocusCardSpacing.apply(view, true, "", 36)
        PodsFocusCardSpacing.restore(view) // before the original system bind
        assertEquals(3, params.bottomMargin)
        params.bottomMargin = 24 // the next application's system template requests a margin
        PodsFocusCardSpacing.apply(view, false, "", 0)
        assertEquals(24, params.bottomMargin)
    }

    @Test fun aLargerStockMarginIsPreserved() {
        val (view, params) = view(50)
        PodsFocusCardSpacing.apply(view, true, "", 36)
        assertEquals(50, params.bottomMargin)
        params.bottomMargin = 56
        PodsFocusCardSpacing.apply(view, false, "", 0)
        assertEquals(56, params.bottomMargin) // No record is kept if we did not change the view.
        verify(view, never()).setLayoutParams(any())
    }
}
