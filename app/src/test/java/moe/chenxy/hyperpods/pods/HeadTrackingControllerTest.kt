package moe.chenxy.hyperpods.pods

import android.app.BroadcastOptions
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.telephony.TelephonyManager
import java.io.ByteArrayOutputStream
import java.util.UUID
import moe.chenxy.hyperpods.utils.AACPManager
import moe.chenxy.hyperpods.utils.AACPWriter
import moe.chenxy.hyperpods.utils.HeadTrackingPreview
import moe.chenxy.hyperpods.utils.data.EarDetectionParams
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.*
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real controller commands/callbacks, with synthetic sockets and no platform calls. */
class HeadTrackingControllerTest {
    private val controller = L2CAPController
    private val context = mock(Context::class.java)
    private val phone = mock(TelephonyManager::class.java)
    private val socket = mock(BluetoothSocket::class.java)
    private val output = ByteArrayOutputStream()
    private val manager = AACPManager(socket)
    private lateinit var broadcasts: MockedStatic<BroadcastOptions>
    private lateinit var clock: MockedStatic<SystemClock>
    private var at = 100L
    private val session = UUID.randomUUID().toString()
    private fun field(name: String) = controller.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private val preview get() = field("headPreview").get(controller) as HeadTrackingPreview
    private fun reset() = controller.javaClass.getDeclaredMethod("resetControlState").apply { isAccessible = true }.invoke(controller)

    @Before fun setUp() {
        broadcasts = mockStatic(BroadcastOptions::class.java)
        broadcasts.`when`<BroadcastOptions> { BroadcastOptions.makeBasic() }.thenReturn(mock(BroadcastOptions::class.java, RETURNS_SELF))
        clock = mockStatic(SystemClock::class.java)
        clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { at }
        reset()
        field("mContext").set(controller, context)
        field("headGestures").setBoolean(controller, false)
        `when`(context.getSystemService(TelephonyManager::class.java)).thenReturn(phone)
        `when`(phone.callState).thenReturn(TelephonyManager.CALL_STATE_IDLE)
        `when`(socket.isConnected).thenReturn(true)
        `when`(socket.outputStream).thenReturn(output)
        field("aacpManager").set(controller, manager)
        field("earDetectionStateValid").setBoolean(controller, true)
        controller.currentEarDetectionParams = EarDetectionParams(EarDetectionStatus.IN_EAR, EarDetectionStatus.OUT_OF_EAR)
        controller.currentPodsInfo = AACPManager.Companion.AirPodsInformation(
            "Fixture", "A3049", "Apple", "NOT-REAL", "0", "0", "0", "DEMO", "DEMO", "DEMO", "DEMO")
        manager.setPacketCallback(field("packetCallback").get(controller) as AACPManager.PacketCallback)
    }

    @After fun tearDown() {
        reset()
        field("mContext").set(controller, null)
        clock.close()
        broadcasts.close()
    }

    private fun command(action: String, id: String = session) {
        val intent = mock(Intent::class.java)
        `when`(intent.action).thenReturn(HyperPodsAction.ACTION_HEAD_TRACKING_COMMAND)
        `when`(intent.getStringExtra("session")).thenReturn(id)
        `when`(intent.getStringExtra("command")).thenReturn(action)
        controller.handleUIEvent(intent)
    }

    @Test fun explicitPreviewUsesExistingSocketWhileCallToggleIsOff() {
        command("start")
        assertTrue(preview.active)
        assertArrayEquals(manager.createDataPacket(manager.createAlternateStartHeadTrackingPacket()), output.toByteArray())
        command("stop", "wrong-session")
        assertTrue(preview.active)
        at += 100
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 0x17, 0) + ByteArray(64))
        val snapshot = preview.javaClass.getDeclaredField("snapshot").apply { isAccessible = true }.get(preview) as HeadTrackingPreview.Snapshot
        assertEquals(1, snapshot.packets)
        command("stop")
        assertFalse(preview.active)
        assertArrayEquals(manager.createDataPacket(manager.createAlternateStartHeadTrackingPacket()) +
            manager.createDataPacket(manager.createAlternateStopHeadTrackingPacket()), output.toByteArray())
    }

    @Test fun phoneBroadcastStopsPreviewEvenWhenCallGesturesAreDisabled() {
        command("start")
        `when`(phone.callState).thenReturn(TelephonyManager.CALL_STATE_RINGING)
        val intent = mock(Intent::class.java)
        `when`(intent.action).thenReturn(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        (field("broadcastReceiver").get(controller) as BroadcastReceiver).onReceive(context, intent)
        assertFalse(preview.active)
        assertArrayEquals(manager.createDataPacket(manager.createAlternateStartHeadTrackingPacket()) +
            manager.createDataPacket(manager.createAlternateStopHeadTrackingPacket()), output.toByteArray())
    }

    @Test fun controlResetReleasesThePreviewBeforeForgettingTheSocket() {
        command("start")
        reset()
        assertFalse(preview.active)
        assertNull(field("headPreviewWatchdog").get(controller))
        assertArrayEquals(manager.createDataPacket(manager.createAlternateStartHeadTrackingPacket()) +
            manager.createDataPacket(manager.createAlternateStopHeadTrackingPacket()), output.toByteArray())
    }

    @Test fun missingWearOrControlConnectionNeverSendsTrackingCommands() {
        field("earDetectionStateValid").setBoolean(controller, false)
        command("start", "no-wear")
        assertFalse(preview.active)
        field("aacpManager").set(controller, null)
        command("start", "no-control")
        assertFalse(preview.active)
        assertEquals(0, output.size())
    }

    @Test fun queuedProductionWriterCannotHoldControllerLockDuringABlockedSensorStart() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        `when`(socket.outputStream).thenReturn(object : OutputStream() {
            override fun write(b: Int) {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
        })
        AACPWriter(socket) {}.use { writer ->
            field("aacpManager").set(controller, AACPManager(socket, writer::send))
            try {
                command("start")
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                val acquired = CountDownLatch(1)
                Thread { synchronized(controller) { acquired.countDown() } }.start()
                assertTrue("Lifecycle cleanup must not wait for output.write", acquired.await(1, TimeUnit.SECONDS))
            } finally {
                release.countDown()
                command("stop")
            }
        }
    }

    @Test fun failedPreviewStopRetainsRecoveryEvenAfterRecognitionHasStopped() {
        command("start")
        `when`(socket.isConnected).thenReturn(false)
        command("stop")
        assertFalse(preview.active)
        val retry = field("headStopRetryJob").get(controller) as kotlinx.coroutines.Job
        assertTrue(retry.isActive)
        // Timer IPC is not the subject of this JVM test.
        field("mContext").set(controller, null)
        `when`(socket.isConnected).thenReturn(true)
        verify(socket, timeout(2000).atLeast(2)).getOutputStream()
        kotlinx.coroutines.runBlocking { retry.join() }
        val snapshot = preview.javaClass.getDeclaredField("snapshot").apply { isAccessible = true }
            .get(preview) as HeadTrackingPreview.Snapshot
        assertEquals("stopped", snapshot.state)
        assertArrayEquals(manager.createDataPacket(manager.createAlternateStartHeadTrackingPacket()) +
            manager.createDataPacket(manager.createAlternateStopHeadTrackingPacket()), output.toByteArray())
    }
}
