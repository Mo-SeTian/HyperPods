package moe.chenxy.hyperpods.utils

import moe.chenxy.hyperpods.pods.BatteryComponent as C
import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.PodBatteryParams
import org.junit.Assert.*
import org.junit.Test

class BatteryReminderBatchTest {
    private val batch = BatteryReminderBatch()
    private val settings = LowBatterySettings()
    private fun battery(level: Int = 19, charging: Boolean = false) = PodBatteryParams(level, charging, true, if (charging) 1 else 2)

    @Test fun splitEarAndCaseAlertsBecomeOneBatchWithTheLatestReadings() {
        assertTrue(batch.update(BatteryParams(left = battery()), settings, listOf(C.LEFT)))
        assertTrue(batch.update(BatteryParams(battery(18), battery()), settings, listOf(C.RIGHT)))
        val latest = BatteryParams(battery(17), battery(18), battery(10))
        assertTrue(batch.update(latest, settings, listOf(C.CASE)))
        val (params, components) = batch.take()!!
        assertSame(latest, params)
        assertEquals(listOf(C.LEFT, C.RIGHT, C.CASE), components)
        assertNull(batch.take())
    }

    @Test fun chargingOrDisablingTheFeatureCancelsAnUndeliveredWarning() {
        batch.update(BatteryParams(left = battery()), settings, listOf(C.LEFT))
        assertFalse(batch.update(BatteryParams(left = battery(charging = true)), settings, emptyList()))
        assertNull(batch.take())
        batch.update(BatteryParams(left = battery()), settings, listOf(C.LEFT))
        assertFalse(batch.update(BatteryParams(left = battery()), settings.copy(earsEnabled = false), emptyList()))
        assertNull(batch.take())
    }

    @Test fun anUnavailableCaseIsRemovedWithoutDroppingTheEarWarning() {
        batch.update(BatteryParams(left = battery(), case = battery()), settings, listOf(C.LEFT, C.CASE))
        batch.update(BatteryParams(left = battery(), case = PodBatteryParams()), settings, emptyList())
        assertEquals(listOf(C.LEFT), batch.take()!!.second)
    }

    @Test fun disconnectOrDeviceReplacementClearsPendingAlerts() {
        batch.update(BatteryParams(left = battery()), settings, listOf(C.LEFT))
        batch.clear()
        assertNull(batch.take())
        batch.update(BatteryParams(right = battery()), settings, listOf(C.RIGHT))
        assertEquals(listOf(C.RIGHT), batch.take()!!.second)
    }
}
