package moe.chenxy.hyperpods.pods

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BatteryNotificationTest {
    private val parser = AirPodsNotifications.BatteryNotification

    @Before fun reset() = parser.reset()

    private fun packet(vararg batteries: Battery): ByteArray {
        require(batteries.size == 3)
        return byteArrayOf(4, 0, 4, 0, 4, 0, 3) + batteries.flatMap {
            listOf(it.component.toByte(), 1, it.level.toByte(), it.status.toByte(), 0)
        }.toByteArray()
    }

    @Test fun fastRemovalDoesNotDiscardReadyEarWhenCaseOrOtherEarIsPending() {
        assertTrue(parser.setBattery(packet(
            Battery(BatteryComponent.LEFT, 82, BatteryStatus.NOT_CHARGING),
            Battery(BatteryComponent.RIGHT, 0, BatteryStatus.NEED_AGAIN),
            Battery(BatteryComponent.CASE, 0, BatteryStatus.NEED_AGAIN)
        )))
        val values = parser.getBattery()
        assertTrue(values[0].isAvailable)
        assertEquals(82, values[0].level)
        assertFalse(values[1].isAvailable)
        assertEquals(-1, values[1].level)
        assertFalse(values[2].isAvailable)
    }

    @Test fun zeroPercentAndSingleEarAreValidWithoutCase() {
        parser.setBattery(packet(
            Battery(BatteryComponent.LEFT, 0, BatteryStatus.NOT_CHARGING),
            Battery(BatteryComponent.RIGHT, 0, BatteryStatus.DISCONNECTED),
            Battery(BatteryComponent.CASE, 0, BatteryStatus.DISCONNECTED)
        ))
        assertTrue(parser.getBattery()[0].isAvailable)
        assertEquals(0, parser.getBattery()[0].level)
        assertFalse(parser.getBattery()[1].isAvailable)
    }

    @Test fun reorderedPendingRecordsKeepTheCorrectEarCache() {
        parser.setBattery(packet(
            Battery(BatteryComponent.LEFT, 85, BatteryStatus.NOT_CHARGING),
            Battery(BatteryComponent.RIGHT, 60, BatteryStatus.NOT_CHARGING),
            Battery(BatteryComponent.CASE, 95, BatteryStatus.CHARGING)
        ))
        parser.setBattery(packet(
            Battery(BatteryComponent.RIGHT, 0, BatteryStatus.NEED_AGAIN),
            Battery(BatteryComponent.LEFT, 80, BatteryStatus.NOT_CHARGING),
            Battery(BatteryComponent.CASE, 0, BatteryStatus.DISCONNECTED)
        ))
        assertEquals(listOf(80, 60, 95), parser.getBattery().map { it.level })
        assertFalse(parser.getBattery()[1].isAvailable)
    }

    @Test fun reconnectClearsPreviousDeviceBattery() {
        parser.setBattery(packet(
            Battery(BatteryComponent.LEFT, 85, BatteryStatus.NOT_CHARGING),
            Battery(BatteryComponent.RIGHT, 60, BatteryStatus.NOT_CHARGING),
            Battery(BatteryComponent.CASE, 95, BatteryStatus.CHARGING)
        ))
        parser.reset()
        assertTrue(parser.getBattery().all { it.level == -1 && !it.isAvailable })
    }

    @Test fun truncatedPacketIsRejectedWithoutChangingState() {
        val before = parser.getBattery()
        assertFalse(parser.setBattery(byteArrayOf(4, 0, 4, 0, 4, 0)))
        assertEquals(before, parser.getBattery())
    }
}
