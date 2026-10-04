package moe.chenxy.hyperpods.pods

import android.app.BroadcastOptions
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.ContextWrapper
import android.content.Intent
import kotlinx.coroutines.Job
import moe.chenxy.hyperpods.utils.AACPManager
import moe.chenxy.hyperpods.utils.PodsSettings
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import moe.chenxy.hyperpods.utils.data.HyperPodsPrefsKey as Key
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.*
import java.io.ByteArrayOutputStream

/** Exercises the real broadcast handler, writer, incoming parser and acknowledgement lifecycle. */
class L2CAPControllerSettingsTest {
    private val controller = L2CAPController
    private val type = controller.javaClass
    private val device = mock(BluetoothDevice::class.java)
    private val socket = mock(BluetoothSocket::class.java)
    private val output = ByteArrayOutputStream()
    private val manager = AACPManager(socket)
    private lateinit var broadcasts: MockedStatic<BroadcastOptions>
    private fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
    private fun pending() = field("pendingSettings").get(controller) as Map<*, *>

    @Before fun setUp() {
        AirPodsNotifications.BatteryNotification.reset()
        field("batteryStateValid").set(controller, false)
        field("earDetectionStateValid").set(controller, false)
        broadcasts = mockStatic(BroadcastOptions::class.java)
        broadcasts.`when`<BroadcastOptions> { BroadcastOptions.makeBasic() }
            .thenReturn(mock(BroadcastOptions::class.java, RETURNS_SELF))
        field("mContext").set(controller, ContextWrapper(null))
        field("mDevice").set(controller, device)
        field("aacpManager").set(controller, manager)
        manager.setPacketCallback(field("packetCallback").get(controller) as AACPManager.PacketCallback)
        `when`(socket.isConnected).thenReturn(true)
        `when`(socket.outputStream).thenReturn(output)
        controller.currentPodsInfo = AACPManager.Companion.AirPodsInformation(
            "Demo", "A3049", "", "", "", "", "", "", "", "", "")
    }

    @After fun tearDown() {
        field("mContext").set(controller, null)
        controller.disconnectedPod(ContextWrapper(null), device)
        broadcasts.close()
    }

    private fun request(key: String, value: Int) = synchronized(controller) {
        val intent = mock(Intent::class.java)
        `when`(intent.action).thenReturn(HyperPodsAction.ACTION_PODS_SETTINGS_CHANGED)
        `when`(intent.getStringExtra("key")).thenReturn(key)
        `when`(intent.hasExtra("value")).thenReturn(true)
        `when`(intent.getIntExtra("value", -1)).thenReturn(value)
        controller.handleUIEvent(intent)
    }

    private fun receive(identifier: Byte, value: Byte) = synchronized(controller) {
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 9, 0, identifier, value, 0, 0, 0))
    }

    @Test fun selectingOffEnablesTheOptionAndWaitsForConfirmationBeforeChangingMode() {
        receive(0x0d, 4)
        receive(0x34, 2)
        controller.setANCMode(1)
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x34, 1, 0, 0, 0), output.toByteArray())
        assertTrue(field("pendingOffSelection").getBoolean(controller))
        receive(0x34, 2) // A stale state report is not an acknowledgement.
        assertEquals(11, output.size())
        receive(0x34, 1)
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x34, 1, 0, 0, 0,
            4, 0, 4, 0, 9, 0, 0x0d, 1, 0, 0, 0), output.toByteArray())
        assertFalse(field("pendingOffSelection").getBoolean(controller))
        assertTrue(pending().containsKey(PodsSettings.NOISE_MODE))
        assertEquals(4.toByte(), manager.getControlCommandStatus(AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE)!!.value[0])
        receive(0x0d, 1)
        assertTrue(pending().isEmpty())
    }

    @Test fun alreadyEnabledOffDoesNotRewriteTheOption() {
        receive(0x34, 1)
        controller.setANCMode(1)
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x0d, 1, 0, 0, 0), output.toByteArray())
    }

    @Test fun failedOptionWriteCancelsOffSelectionWithoutSendingMode() {
        receive(0x0d, 4)
        `when`(socket.isConnected).thenReturn(false)
        controller.setANCMode(1)
        assertFalse(field("pendingOffSelection").getBoolean(controller))
        assertEquals(0, output.size())
        assertTrue(pending().isEmpty())
    }

    @Test fun offOptionTimeoutNeverSendsOffAndAllowsAnotherClick() {
        controller.setANCMode(1)
        field("mContext").set(controller, null)
        val deadline = System.nanoTime() + 4_000_000_000L
        while (synchronized(controller) { pending().isNotEmpty() } && System.nanoTime() < deadline) Thread.sleep(20)
        assertFalse(field("pendingOffSelection").getBoolean(controller))
        assertEquals(11, output.size())
        field("mContext").set(controller, ContextWrapper(null))
        controller.setANCMode(1)
        assertTrue(field("pendingOffSelection").getBoolean(controller))
        assertEquals(22, output.size())
    }

    private fun initialReady(): Boolean = type.getDeclaredMethod("initialStatusReady")
        .apply { isAccessible = true }.invoke(controller) as Boolean

    @Test fun batteryAloneDoesNotStopRecoveryBeforeInformationAndSettingsArrive() {
        field("batteryStateValid").set(controller, true)
        assertFalse(initialReady())
        field("earDetectionStateValid").set(controller, true)
        assertFalse(initialReady())
        receive(0x0d, 4)
        assertTrue(initialReady())
        controller.currentPodsInfo = null
        assertFalse(initialReady())
    }

    @Test fun cachedPendingBatteryStillRequestsRefreshButSingleEarCanFinishInitialization() {
        field("batteryStateValid").set(controller, true)
        field("earDetectionStateValid").set(controller, true)
        receive(0x0d, 4)
        val report = byteArrayOf(4, 0, 4, 0, 4, 0, 1, 4, 1, 80, 2, 0)
        AirPodsNotifications.BatteryNotification.setBattery(report)
        assertTrue(initialReady())
        AirPodsNotifications.BatteryNotification.setBattery(report.copyOf().apply { this[10] = 3 })
        assertFalse(initialReady())
        AirPodsNotifications.BatteryNotification.setBattery(report)
        assertTrue(initialReady())
    }

    @Test fun partialBatteryReportsUpdateTheControllerAcrossFastRemoval() {
        // Service broadcasts and hidden AdapterService mirroring are outside this JVM test.
        field("mContext").set(controller, null)
        val left = byteArrayOf(4, 0, 4, 0, 4, 0, 1, 4, 1, 82, 2, 0)
        val right = byteArrayOf(4, 0, 4, 0, 4, 0, 1, 2, 1, 65, 2, 0)
        manager.receivePacket(left)
        assertEquals(82, controller.currentBatteryParams.left!!.battery)
        assertTrue(controller.currentBatteryParams.left!!.isConnected)
        manager.receivePacket(right)
        assertEquals(65, controller.currentBatteryParams.right!!.battery)
        assertEquals(82, controller.currentBatteryParams.left!!.battery)
        manager.receivePacket(left.copyOf().apply { this[10] = 3 })
        assertTrue(controller.currentBatteryParams.left!!.isConnected)
        assertEquals(82, controller.currentBatteryParams.left!!.battery)
        manager.receivePacket(left.copyOf().apply { this[10] = 4 })
        assertFalse(controller.currentBatteryParams.left!!.isConnected)
    }

    @Test fun disconnectAbandonsTheOffPrerequisiteAndCannotSendALateModeCommand() {
        controller.setANCMode(1)
        field("mContext").set(controller, null)
        controller.disconnectedPod(ContextWrapper(null), device)
        receive(0x34, 1)
        assertFalse(field("pendingOffSelection").getBoolean(controller))
        assertTrue(pending().isEmpty())
        assertEquals(11, output.size())
    }

    @Test fun microphoneRequestIsNotConfirmedUntilMatchingReplyArrives() {
        receive(1, 0)
        request(Key.MICROPHONE_MODE, 2)
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 1, 2, 0, 0, 0), output.toByteArray())
        assertTrue(pending().containsKey(Key.MICROPHONE_MODE))
        assertEquals(0.toByte(), manager.getControlCommandStatus(AACPManager.Companion.ControlCommandIdentifiers.MIC_MODE)!!.value[0])
        receive(1, 1)
        assertTrue(pending().containsKey(Key.MICROPHONE_MODE))
        receive(1, 2)
        assertTrue(pending().isEmpty())
        assertEquals(2.toByte(), manager.getControlCommandStatus(AACPManager.Companion.ControlCommandIdentifiers.MIC_MODE)!!.value[0])
    }

    @Test fun writeFailureDoesNotQueueOrChangeConfirmedValue() {
        receive(0x26, 2)
        `when`(socket.isConnected).thenReturn(false)
        request(Key.PERSONLIZED_VOLUME, 1)
        assertTrue(pending().isEmpty())
        assertEquals(2.toByte(), manager.getControlCommandStatus(AACPManager.Companion.ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG)!!.value[0])
        assertEquals(0, output.size())
    }

    @Test fun loudSoundReductionIsNeverWrittenIntoAacp() {
        request(Key.LOUD_SOUND_REDUCTION, 1)
        assertEquals(0, output.size())
        assertTrue(pending().isEmpty())
    }

    @Test fun timeoutRetainsTheReceivedStateAndAllowsRetry() {
        receive(1, 0)
        request(Key.MICROPHONE_MODE, 2)
        // JVM Android stubs cannot deliver broadcasts from the timer thread.
        field("mContext").set(controller, null)
        val deadline = System.nanoTime() + 4_000_000_000L
        while (synchronized(controller) { pending().isNotEmpty() } && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(pending().isEmpty())
        assertEquals(0.toByte(), manager.getControlCommandStatus(AACPManager.Companion.ControlCommandIdentifiers.MIC_MODE)!!.value[0])
        field("mContext").set(controller, ContextWrapper(null))
        request(Key.MICROPHONE_MODE, 1)
        assertTrue(pending().containsKey(Key.MICROPHONE_MODE))
    }

    @Test fun disconnectCancelsOutstandingSettings() {
        request(Key.MICROPHONE_MODE, 2)
        val entry = pending().values.single()!!
        val timeout = entry.javaClass.getDeclaredField("timeout").apply { isAccessible = true }.get(entry) as Job
        field("mContext").set(controller, null)
        controller.disconnectedPod(ContextWrapper(null), device)
        assertTrue(timeout.isCancelled)
        assertTrue(pending().isEmpty())
    }
}
