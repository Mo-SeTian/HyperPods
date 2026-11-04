package moe.chenxy.hyperpods.utils

import android.bluetooth.BluetoothSocket
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class CustomEqTest {
    private fun report(state: Int = 2, low: Int = 65, mid: Int = 50, high: Int = 60) =
        byteArrayOf(4, 0, 4, 0, 0x63, 0, 5, 0, 1, state.toByte(), low.toByte(), mid.toByte(), high.toByte())

    @Test fun matchesLibrePodsWireFormatAndKeepsBandsWhenChangingMode() {
        val eq = CustomEq(2, 65, 50, 60)
        assertArrayEquals(report(), byteArrayOf(4, 0, 4, 0) + eq.toPacket())
        assertEquals(eq, CustomEq.fromPacket(report()))
        assertEquals(CustomEq(1, 65, 50, 60), CustomEq.fromPacket(report(state = 1)))
        assertArrayEquals(report(state = 1), byteArrayOf(4, 0, 4, 0) + eq.copy(state = 1).toPacket())
        for (value in listOf(0, 100)) assertEquals(CustomEq(2, value, value, value),
            CustomEq.fromPacket(report(low = value, mid = value, high = value)))
    }

    @Test fun malformedAndNonSettingsVariantsDoNotBecomeDefaultReports() {
        val bad = (0 until 13).map { report().copyOf(it) } + listOf(
            report().copyOf(14), report(state = 0), report(state = 3),
            report(low = 101), report(mid = 255), report(high = 128),
            report().apply { this[6] = 4 }, report().apply { this[7] = 1 },
            report().apply { this[8] = 0 }, report().apply { this[4] = 0x53 },
        )
        bad.forEach { assertNull(CustomEq.fromPacket(it)) }
        for (eq in listOf({ CustomEq(0) }, { CustomEq(low = -1) }, { CustomEq(mid = 101) }, { CustomEq(high = 256) }))
            assertThrows(IllegalArgumentException::class.java) { eq() }
    }

    @Test fun cacheChangesOnlyAfterSuccessfulWritesAndRealReportsRemainAuthoritative() {
        val writes = mutableListOf<ByteArray>()
        var writable = true
        var now = 100L
        val manager = AACPManager(mock(BluetoothSocket::class.java), { writes.add(it); writable }, { now })
        val callback = mock(AACPManager.PacketCallback::class.java)
        manager.setPacketCallback(callback)
        assertNull(manager.customEq)
        assertTrue(writes.isEmpty())
        assertTrue(manager.sendCustomEq(CustomEq(2, 65, 50, 60)))
        assertArrayEquals(report(), writes.single())
        assertEquals(CustomEq(2, 65, 50, 60), manager.customEq)
        assertTrue(manager.customEqUnconfirmed)
        assertEquals(0L, manager.settingsReceivedAt)
        verifyNoInteractions(callback)

        manager.receivePacket(report(state = 1, low = 40))
        assertEquals(CustomEq(1, 40, 50, 60), manager.customEq)
        assertFalse(manager.customEqUnconfirmed)
        assertEquals(100L, manager.settingsReceivedAt)
        verify(callback).onCustomEqReceived(CustomEq(1, 40, 50, 60))
        val revision = manager.customEqRevision
        now = 200
        manager.receivePacket(report(low = 255))
        assertEquals(revision, manager.customEqRevision)
        assertEquals(100L, manager.settingsReceivedAt)
        writable = false
        assertFalse(manager.sendCustomEq(CustomEq(2)))
        assertEquals(CustomEq(1, 40, 50, 60), manager.customEq)
        assertFalse(manager.customEqUnconfirmed)
        assertEquals(revision + 1, manager.customEqRevision)
        assertFalse(manager.sendCustomEq(CustomEq(2)))
        assertEquals(revision + 2, manager.customEqRevision)
    }

    @Test fun firmwareGateHandlesFutureVersionsUnknownVersionsAndObservedSupport() {
        for (model in listOf(AirPods4(), AirPods4ANC(), AirPods5(), AirPods5WCC(),
                AirPodsPro2Lightning(), AirPodsPro2USBC(), AirPodsPro3())) {
            for (firmware in listOf("9A348", " 9B123 ", "10A100"))
                assertTrue(CustomEq.available(model, firmware, false))
            for (firmware in listOf("", "unknown", "8A358", "8454592", "A9", "999999999999999A"))
                assertFalse(CustomEq.available(model, firmware, false))
        }
        for (model in listOf(null, AirPods(), AirPods2(), AirPods3(), AirPodsPro1()))
            assertFalse(CustomEq.available(model, "9A348", false))
        assertTrue(CustomEq.available(null, "", true))
    }

    @Test fun statusRecoveryNeverWritesEqOrRequiresItToInitialize() {
        val writes = mutableListOf<ByteArray>()
        val manager = AACPManager(mock(BluetoothSocket::class.java), { writes.add(it); true })
        manager.requestInitialStatus(full = true)
        assertTrue(writes.isNotEmpty())
        assertFalse(writes.any { it.size >= 6 && it[4] == CustomEq.OPCODE })
        assertNull(manager.customEq)
    }
}
