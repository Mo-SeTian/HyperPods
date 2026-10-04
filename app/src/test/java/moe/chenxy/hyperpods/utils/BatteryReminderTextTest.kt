package moe.chenxy.hyperpods.utils

import android.content.res.Resources
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.pods.BatteryComponent as C
import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.PodBatteryParams
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class BatteryReminderTextTest {
    private val resources = mock(Resources::class.java).also {
        `when`(it.getString(R.string.dashboard_left)).thenReturn("Left")
        `when`(it.getString(R.string.dashboard_right)).thenReturn("Right")
        `when`(it.getString(R.string.dashboard_case)).thenReturn("Case")
    }

    @Test fun mergedTextContainsIndependentReadingsWithoutDeviceIdentity() {
        val params = BatteryParams(PodBatteryParams(18), PodBatteryParams(19))
        `when`(resources.getString(R.string.battery_reminder_low_message, "Left 18% · Right 19%"))
            .thenReturn("Low battery: Left 18% · Right 19%. Please charge.")
        val text = BatteryReminderText.format(resources, params, listOf(C.LEFT, C.RIGHT))
        assertEquals("Low battery: Left 18% · Right 19%. Please charge.", text)
    }

    @Test fun caseAtTenPercentUsesTheCriticalMessage() {
        `when`(resources.getString(R.string.battery_reminder_critical_message, "Case 10%"))
            .thenReturn("Very low battery: Case 10%.")
        assertEquals("Very low battery: Case 10%.", BatteryReminderText.format(resources,
            BatteryParams(case = PodBatteryParams(10)), listOf(C.CASE)))
    }

    @Test fun missingAndUnknownComponentsProduceNoWarningText() {
        assertEquals("", BatteryReminderText.format(resources, BatteryParams(), listOf(C.LEFT, 999)))
        assertEquals("", BatteryReminderText.format(resources, BatteryParams(), emptyList()))
    }
}
