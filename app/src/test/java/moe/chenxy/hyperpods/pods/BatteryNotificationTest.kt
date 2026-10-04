package moe.chenxy.hyperpods.pods

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BatteryNotificationTest {
    private val parser = AirPodsNotifications.BatteryNotification

    @Before fun reset() = parser.reset()

    private fun packet(vararg batteries: Battery): ByteArray {
        return byteArrayOf(4, 0, 4, 0, 4, 0, batteries.size.toByte()) + batteries.flatMap {
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
        assertFalse(parser.needsRefresh)
    }

    @Test fun singleAndTwoEarReportsAreAcceptedWithoutAChargingCase() {
        assertTrue(parser.setBattery(packet(Battery(BatteryComponent.LEFT, 82, BatteryStatus.NOT_CHARGING))))
        assertEquals(82, parser.getBattery()[0].level)
        assertFalse(parser.getBattery()[1].isAvailable)
        assertTrue(parser.setBattery(packet(
            Battery(BatteryComponent.RIGHT, 65, BatteryStatus.NOT_CHARGING),
            Battery(BatteryComponent.LEFT, 81, BatteryStatus.NOT_CHARGING))))
        assertEquals(listOf(81, 65, -1), parser.getBattery().map { it.level })
        assertFalse(parser.needsRefresh)
    }

    @Test fun partialUpdateKeepsTheOtherEarsLastReportButExplicitDisconnectHidesIt() {
        parser.setBattery(packet(Battery(BatteryComponent.LEFT, 80, BatteryStatus.NOT_CHARGING),
            Battery(BatteryComponent.RIGHT, 70, BatteryStatus.NOT_CHARGING)))
        parser.setBattery(packet(Battery(BatteryComponent.LEFT, 79, BatteryStatus.NOT_CHARGING)))
        assertEquals(70, parser.getBattery()[1].level)
        assertTrue(parser.getBattery()[1].isAvailable)
        parser.setBattery(packet(Battery(BatteryComponent.RIGHT, 0, BatteryStatus.DISCONNECTED)))
        assertFalse(parser.getBattery()[1].isAvailable)
    }

    @Test fun pendingReadingDoesNotEraseBatteryDuringFastRemoval() {
        parser.setBattery(packet(Battery(BatteryComponent.LEFT, 80, BatteryStatus.NOT_CHARGING)))
        parser.setBattery(packet(Battery(BatteryComponent.LEFT, 0, BatteryStatus.NEED_AGAIN)))
        assertEquals(80, parser.getBattery()[0].level)
        assertTrue(parser.getBattery()[0].isAvailable)
        assertTrue(parser.needsRefresh)
        parser.setBattery(packet(Battery(BatteryComponent.LEFT, 79, BatteryStatus.NOT_CHARGING)))
        assertFalse(parser.needsRefresh)
    }

    @Test fun invalidCountAndTruncatedRecordsDoNotChangeTheCache() {
        parser.setBattery(packet(Battery(BatteryComponent.LEFT, 80, BatteryStatus.NOT_CHARGING)))
        val before = parser.getBattery()
        val report = packet(Battery(BatteryComponent.LEFT, 70, BatteryStatus.NOT_CHARGING))
        assertFalse(parser.setBattery(report.copyOf(11)))
        assertFalse(parser.setBattery(report.copyOf().apply { this[6] = 3 }))
        assertFalse(parser.setBattery(byteArrayOf(4, 0, 4, 0, 4, 0, 0)))
        assertFalse(parser.setBattery(packet(*Array(4) { Battery(BatteryComponent.LEFT, 50, BatteryStatus.NOT_CHARGING) })))
        assertEquals(before, parser.getBattery())
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
        assertTrue(parser.getBattery()[1].isAvailable)
        assertTrue(parser.needsRefresh)
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
