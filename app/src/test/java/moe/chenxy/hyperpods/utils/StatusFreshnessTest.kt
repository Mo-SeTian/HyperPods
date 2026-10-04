package moe.chenxy.hyperpods.utils

import moe.chenxy.hyperpods.pods.BatteryStatus
import moe.chenxy.hyperpods.utils.StatusFreshness.State
import moe.chenxy.hyperpods.utils.data.PodBatteryParams
import org.junit.Assert.*
import org.junit.Test

class StatusFreshnessTest {
    private val battery = PodBatteryParams(80, false, true, BatteryStatus.NOT_CHARGING)

    @Test fun oldButValidReportsAreNotExpiredByAnInventedTimeout() {
        assertEquals(State.REPORTED, StatusFreshness.battery(battery, 1, "connected"))
        assertEquals(State.REPORTED, StatusFreshness.wear(true, 1, "connected"))
    }

    @Test fun pendingBatteryAndFailedControlChannelsAreExplicitlyCached() {
        assertEquals(State.RETAINED, StatusFreshness.battery(battery.copy(rawStatus = BatteryStatus.NEED_AGAIN), 1, "connected"))
        assertEquals(State.RETAINED, StatusFreshness.battery(battery, 1, "failed"))
        assertEquals(State.RETAINED, StatusFreshness.wear(true, 1, "failed"))
    }

    @Test fun missingDataAndTimestampRemainUnknown() {
        assertEquals(State.UNKNOWN, StatusFreshness.battery(battery, 0, "connected"))
        assertEquals(State.UNKNOWN, StatusFreshness.battery(battery.copy(isConnected = false), 1, "connected"))
        assertEquals(State.UNKNOWN, StatusFreshness.battery(battery.copy(battery = -1), 1, "connected"))
        assertEquals(State.UNKNOWN, StatusFreshness.battery(null, 1, "connected"))
        assertEquals(State.UNKNOWN, StatusFreshness.wear(false, 1, "connected"))
        assertEquals(State.UNKNOWN, StatusFreshness.wear(true, 0, "connected"))
    }
}
