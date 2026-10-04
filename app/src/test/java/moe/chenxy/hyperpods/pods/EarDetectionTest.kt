package moe.chenxy.hyperpods.pods

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EarDetectionTest {
    private val parser = AirPodsNotifications.EarDetection

    @Before fun reset() = parser.reset()

    private fun packet(primary: Byte, secondary: Byte) = byteArrayOf(4, 0, 4, 0, 6, 0, primary, secondary)

    @Test fun batteryMappingAloneDoesNotInventAWearReport() {
        assertNull(parser.getLeftRightStatus(BatteryComponent.LEFT))
        assertNull(parser.getLeftRightStatus(BatteryComponent.RIGHT))
    }

    @Test fun primaryAndSecondaryStatusesMapToBothPhysicalSideOrders() {
        for (primary in 0..3) for (secondary in 0..3) {
            assertTrue(parser.setStatus(packet(primary.toByte(), secondary.toByte())))
            assertEquals(listOf(primary.toByte(), secondary.toByte()), parser.getLeftRightStatus(BatteryComponent.LEFT))
            assertEquals(listOf(secondary.toByte(), primary.toByte()), parser.getLeftRightStatus(BatteryComponent.RIGHT))
        }
    }

    @Test fun unknownPrimaryDoesNotGuessLeftOrRight() {
        parser.setStatus(packet(EarDetectionStatus.IN_EAR, EarDetectionStatus.OUT_OF_EAR))
        assertNull(parser.getLeftRightStatus(null))
        assertNull(parser.getLeftRightStatus(BatteryComponent.CASE))
        assertNull(parser.getLeftRightStatus(1))
    }

    @Test fun invalidWearPacketsDoNotOverwriteTheLastReport() {
        val report = packet(EarDetectionStatus.IN_EAR, EarDetectionStatus.IN_CASE)
        parser.setStatus(report)
        val before = parser.getLeftRightStatus(BatteryComponent.RIGHT)
        for (invalid in listOf(report.copyOf(7), report + 0,
            report.copyOf().apply { this[4] = 4 },
            report.copyOf().apply { this[6] = 4 },
            report.copyOf().apply { this[7] = 0xff.toByte() })) {
            assertFalse(parser.setStatus(invalid))
            assertEquals(before, parser.getLeftRightStatus(BatteryComponent.RIGHT))
        }
    }

    @Test fun resetClearsThePreviousSessionsWearReport() {
        parser.setStatus(packet(EarDetectionStatus.IN_EAR, EarDetectionStatus.OUT_OF_EAR))
        parser.reset()
        assertNull(parser.getLeftRightStatus(BatteryComponent.RIGHT))
    }
}
