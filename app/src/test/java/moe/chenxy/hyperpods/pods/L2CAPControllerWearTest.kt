package moe.chenxy.hyperpods.pods

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.app.BroadcastOptions
import android.content.ContextWrapper
import android.content.Context
import android.content.Intent
import android.bluetooth.BluetoothSocket
import android.media.MediaRoute2Info
import android.media.AudioManager
import android.media.MediaRouter2
import android.telephony.TelephonyManager
import android.view.KeyEvent
import android.content.BroadcastReceiver
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import org.mockito.ArgumentCaptor
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.PodBatteryParams
import moe.chenxy.hyperpods.utils.AACPManager
import moe.chenxy.hyperpods.utils.MediaControl
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.*
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import moe.chenxy.hyperpods.utils.data.HyperPodsPrefsKey

/**
 * Run the real wear handler with no-op Android service stubs. These tests verify
 * job cancellation and local state, not Bluetooth or MediaRouter on a device.
 */
class L2CAPControllerWearTest {
    private val controller = L2CAPController
    private val type = controller.javaClass
    private lateinit var broadcasts: MockedStatic<BroadcastOptions>
    private lateinit var device: BluetoothDevice

    private fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
    private fun pendingSwitch() = field("speakerSwitchJob").get(controller) as Job?

    private fun wear(left: Byte, right: Byte) {
        type.getDeclaredMethod("handleInEarStatusChanged", List::class.java).apply {
            isAccessible = true
        }.invoke(controller, listOf(left, right))
    }

    private fun settings(enabled: Boolean, speaker: Boolean) {
        type.getDeclaredMethod("updateEarDetectionSettings", Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType).apply {
            isAccessible = true
        }.invoke(controller, enabled, speaker)
    }

    @Before fun setUp() {
        AirPodsNotifications.BatteryNotification.reset()
        AirPodsNotifications.EarDetection.reset()
        broadcasts = mockStatic(BroadcastOptions::class.java)
        val options = mock(BroadcastOptions::class.java, RETURNS_SELF)
        broadcasts.`when`<BroadcastOptions> { BroadcastOptions.makeBasic() }.thenReturn(options)
        device = mock(BluetoothDevice::class.java)
        field("mDevice").set(controller, device)
        field("mContext").set(controller, ContextWrapper(null))
        field("earDetectionStateValid").setBoolean(controller, false)
        field("conversationPauseMedia").setBoolean(controller, false)
        field("conversationAwarenessActive").setBoolean(controller, false)
        field("batteryStateValid").setBoolean(controller, false)
        field("switchedToSpeaker").setBoolean(controller, false)
        field("pausedAudio").setBoolean(controller, false)
        field("pendingAudioRoute").set(controller, null)
        field("routeRetryCount").setInt(controller, 0)
        field("headphoneRouteId").set(controller, null)
        field("aacpManager").set(controller, null)
        field("socket").set(controller, null)
        field("lastDiagnostics").set(controller, null)
        field("lastStatusFailure").set(controller, "")
        val router = mock(MediaRouter2::class.java)
        val routing = mock(MediaRouter2.RoutingController::class.java)
        `when`(router.systemController).thenReturn(routing)
        `when`(routing.selectedRoutes).thenReturn(emptyList())
        field("mediaRouter").set(controller, router)
        field("localControlClose").set(controller, null)
        field("headGestures").setBoolean(controller, false)
        controller.currentBatteryParams = BatteryParams(PodBatteryParams(), PodBatteryParams(), PodBatteryParams())
        settings(true, true)
    }

    @After fun tearDown() {
        settings(false, false)
        field("mContext").set(controller, null)
        field("earDetectionStateValid").setBoolean(controller, false)
        (field("routeRetryJob").get(controller) as Job?)?.cancel()
        field("routeRetryJob").set(controller, null)
        (field("routeTransferTimeout").get(controller) as Job?)?.cancel()
        field("routeTransferTimeout").set(controller, null)
        broadcasts.close()
    }

    private fun protocolReceiver(): AACPManager {
        // Exercise the actual packet callbacks without notification or audio services.
        field("mContext").set(controller, null)
        val socket = mock(BluetoothSocket::class.java)
        `when`(socket.isConnected).thenReturn(true)
        `when`(socket.outputStream).thenReturn(ByteArrayOutputStream())
        return AACPManager(socket).also {
            field("aacpManager").set(controller, it)
            it.setPacketCallback(field("packetCallback").get(controller) as AACPManager.PacketCallback)
        }
    }

    private fun batteryReport(vararg components: Int): ByteArray =
        byteArrayOf(4, 0, 4, 0, 4, 0, components.size.toByte()) + components.flatMap {
            val level = when (it) { BatteryComponent.LEFT -> 82; BatteryComponent.RIGHT -> 65; else -> 91 }
            listOf(it.toByte(), 1, level.toByte(), BatteryStatus.NOT_CHARGING.toByte(), 0)
        }.toByteArray()

    private fun earReport(primary: Byte, secondary: Byte): ByteArray =
        byteArrayOf(4, 0, 4, 0, 6, 0, primary, secondary)

    private fun assertEars(left: Byte, right: Byte) {
        assertTrue(field("earDetectionStateValid").getBoolean(controller))
        assertEquals(left, controller.currentEarDetectionParams.left)
        assertEquals(right, controller.currentEarDetectionParams.right)
    }

    @Test fun rightPrimaryMapsSingleLeftAndRightEarWear() {
        val manager = protocolReceiver()
        manager.receivePacket(batteryReport(BatteryComponent.RIGHT, BatteryComponent.LEFT))
        manager.receivePacket(earReport(EarDetectionStatus.OUT_OF_EAR, EarDetectionStatus.IN_EAR))
        assertEars(EarDetectionStatus.IN_EAR, EarDetectionStatus.OUT_OF_EAR)
        manager.receivePacket(earReport(EarDetectionStatus.IN_EAR, EarDetectionStatus.OUT_OF_EAR))
        assertEars(EarDetectionStatus.OUT_OF_EAR, EarDetectionStatus.IN_EAR)
    }

    @Test fun leftPrimaryMapsSingleLeftAndRightEarWear() {
        val manager = protocolReceiver()
        manager.receivePacket(batteryReport(BatteryComponent.LEFT, BatteryComponent.RIGHT))
        manager.receivePacket(earReport(EarDetectionStatus.IN_EAR, EarDetectionStatus.OUT_OF_EAR))
        assertEars(EarDetectionStatus.IN_EAR, EarDetectionStatus.OUT_OF_EAR)
        manager.receivePacket(earReport(EarDetectionStatus.OUT_OF_EAR, EarDetectionStatus.IN_EAR))
        assertEars(EarDetectionStatus.OUT_OF_EAR, EarDetectionStatus.IN_EAR)
    }

    @Test fun wearBeforeBatteryWaitsForSideMappingThenAppliesTheCachedReport() {
        val manager = protocolReceiver()
        manager.receivePacket(earReport(EarDetectionStatus.OUT_OF_EAR, EarDetectionStatus.IN_EAR))
        assertFalse("Do not guess physical sides before the primary is known",
            field("earDetectionStateValid").getBoolean(controller))
        manager.receivePacket(batteryReport(BatteryComponent.RIGHT, BatteryComponent.LEFT))
        assertEars(EarDetectionStatus.IN_EAR, EarDetectionStatus.OUT_OF_EAR)
    }

    @Test fun primaryChangeRemapsCachedWearAndCaseStateWithoutChangingBatteryLevels() {
        val manager = protocolReceiver()
        manager.receivePacket(batteryReport(BatteryComponent.RIGHT, BatteryComponent.LEFT))
        manager.receivePacket(earReport(EarDetectionStatus.IN_EAR, EarDetectionStatus.IN_CASE))
        assertEars(EarDetectionStatus.IN_CASE, EarDetectionStatus.IN_EAR)
        assertTrue(controller.currentBatteryParams.left!!.isInCase)
        assertFalse(controller.currentBatteryParams.right!!.isInCase)
        manager.receivePacket(batteryReport(BatteryComponent.LEFT, BatteryComponent.RIGHT))
        assertEars(EarDetectionStatus.IN_EAR, EarDetectionStatus.IN_CASE)
        assertFalse(controller.currentBatteryParams.left!!.isInCase)
        assertTrue(controller.currentBatteryParams.right!!.isInCase)
        assertEquals(82, controller.currentBatteryParams.left!!.battery)
        assertEquals(65, controller.currentBatteryParams.right!!.battery)
    }

    @Test fun anOutOfCaseWearReportOverridesChargingUntilTheEarReturnsToTheCase() {
        val manager = protocolReceiver()
        val charging = batteryReport(BatteryComponent.LEFT, BatteryComponent.RIGHT, BatteryComponent.CASE).apply {
            this[10] = BatteryStatus.CHARGING.toByte()
            this[15] = BatteryStatus.CHARGING.toByte()
            this[20] = BatteryStatus.CHARGING.toByte()
        }
        manager.receivePacket(charging)
        manager.receivePacket(earReport(EarDetectionStatus.IN_CASE, EarDetectionStatus.IN_CASE))
        assertTrue(controller.currentBatteryParams.left!!.isCharging)

        manager.receivePacket(earReport(EarDetectionStatus.OUT_OF_EAR, EarDetectionStatus.IN_CASE))
        assertFalse(controller.currentBatteryParams.left!!.isCharging)
        assertFalse(controller.currentBatteryParams.left!!.isInCase)
        assertTrue(controller.currentBatteryParams.right!!.isCharging)
        assertTrue(controller.currentBatteryParams.case!!.isCharging)
        assertEquals(82, controller.currentBatteryParams.left!!.battery)

        manager.receivePacket(charging)
        assertFalse(controller.currentBatteryParams.left!!.isCharging)
        assertEquals(BatteryStatus.CHARGING, controller.currentBatteryParams.left!!.rawStatus)

        manager.receivePacket(earReport(EarDetectionStatus.IN_CASE, EarDetectionStatus.IN_CASE))
        assertTrue(controller.currentBatteryParams.left!!.isCharging)
    }

    @Test fun caseOnlyReportRetainsThePrimaryAndCaseFirstIsNotAnEar() {
        val manager = protocolReceiver()
        manager.receivePacket(batteryReport(BatteryComponent.CASE, BatteryComponent.RIGHT, BatteryComponent.LEFT))
        manager.receivePacket(earReport(EarDetectionStatus.IN_EAR, EarDetectionStatus.OUT_OF_EAR))
        assertEars(EarDetectionStatus.OUT_OF_EAR, EarDetectionStatus.IN_EAR)
        manager.receivePacket(batteryReport(BatteryComponent.CASE))
        assertEars(EarDetectionStatus.OUT_OF_EAR, EarDetectionStatus.IN_EAR)
    }

    @Test fun singleEarReportMapsItsPrimaryStatusToThePhysicalEar() {
        val manager = protocolReceiver()
        manager.receivePacket(batteryReport(BatteryComponent.RIGHT))
        manager.receivePacket(earReport(EarDetectionStatus.IN_EAR, 3))
        assertEars(3, EarDetectionStatus.IN_EAR)
    }

    @Test fun controlReconnectClearsOldPrimaryAndWearReports() {
        val manager = protocolReceiver()
        manager.receivePacket(batteryReport(BatteryComponent.RIGHT, BatteryComponent.LEFT))
        manager.receivePacket(earReport(EarDetectionStatus.IN_EAR, EarDetectionStatus.OUT_OF_EAR))
        type.getDeclaredMethod("resetControlState").apply { isAccessible = true }.invoke(controller)
        val reconnected = protocolReceiver()
        reconnected.receivePacket(batteryReport(BatteryComponent.LEFT, BatteryComponent.RIGHT))
        assertFalse("A new battery report must not replay the previous session's wear state",
            field("earDetectionStateValid").getBoolean(controller))
        reconnected.receivePacket(earReport(EarDetectionStatus.IN_EAR, EarDetectionStatus.OUT_OF_EAR))
        assertEars(EarDetectionStatus.IN_EAR, EarDetectionStatus.OUT_OF_EAR)
    }

    @Test fun legacyPacketEntryUsesTheSamePhysicalSideMapping() {
        field("mContext").set(controller, null)
        controller.handleAirPodsPacket(batteryReport(BatteryComponent.RIGHT, BatteryComponent.LEFT))
        controller.handleAirPodsPacket(earReport(EarDetectionStatus.OUT_OF_EAR, EarDetectionStatus.IN_EAR))
        assertEars(EarDetectionStatus.IN_EAR, EarDetectionStatus.OUT_OF_EAR)
    }

    @Test fun physicalDisconnectClearsPrimaryBeforeTheNextWearReport() {
        val manager = protocolReceiver()
        manager.receivePacket(batteryReport(BatteryComponent.RIGHT, BatteryComponent.LEFT))
        manager.receivePacket(earReport(EarDetectionStatus.IN_EAR, EarDetectionStatus.OUT_OF_EAR))
        controller.disconnectedPod(ContextWrapper(null), device)
        val reconnected = protocolReceiver()
        reconnected.receivePacket(earReport(EarDetectionStatus.IN_EAR, EarDetectionStatus.OUT_OF_EAR))
        assertFalse(field("earDetectionStateValid").getBoolean(controller))
        reconnected.receivePacket(batteryReport(BatteryComponent.LEFT, BatteryComponent.RIGHT))
        assertEars(EarDetectionStatus.IN_EAR, EarDetectionStatus.OUT_OF_EAR)
    }

    @Test fun invalidWearReportCannotCorruptARemapWhenPrimaryChanges() {
        val manager = protocolReceiver()
        manager.receivePacket(batteryReport(BatteryComponent.RIGHT, BatteryComponent.LEFT))
        manager.receivePacket(earReport(EarDetectionStatus.IN_EAR, EarDetectionStatus.OUT_OF_EAR))
        assertEars(EarDetectionStatus.OUT_OF_EAR, EarDetectionStatus.IN_EAR)
        manager.receivePacket(earReport(0xff.toByte(), EarDetectionStatus.IN_EAR))
        manager.receivePacket(batteryReport(BatteryComponent.LEFT, BatteryComponent.RIGHT))
        assertEars(EarDetectionStatus.IN_EAR, EarDetectionStatus.OUT_OF_EAR)
    }

    @Test fun initialWearDoesNotForceAnAudioReconnect() = synchronized(controller) {
        wear(EarDetectionStatus.IN_EAR, EarDetectionStatus.IN_EAR)
        assertNull(pendingSwitch())
        assertFalse(field("switchedToSpeaker").getBoolean(controller))
    }

    @Test fun quickRemovalAndRewearCancelsTheDelayedSpeakerSwitch() = synchronized(controller) {
        wear(EarDetectionStatus.OUT_OF_EAR, EarDetectionStatus.OUT_OF_EAR)
        val pending = requireNotNull(pendingSwitch())
        assertTrue(pending.isActive)
        wear(EarDetectionStatus.IN_EAR, EarDetectionStatus.OUT_OF_EAR)
        assertTrue(pending.isCancelled)
        assertNull(pendingSwitch())
    }

    @Test fun removingOnlyOneEarDoesNotSwitchToSpeaker() = synchronized(controller) {
        wear(EarDetectionStatus.IN_EAR, EarDetectionStatus.IN_EAR)
        wear(EarDetectionStatus.OUT_OF_EAR, EarDetectionStatus.IN_EAR)
        assertNull(pendingSwitch())
    }

    @Test fun disablingSpeakerSwitchCancelsWithoutAnotherWearEvent() = synchronized(controller) {
        wear(EarDetectionStatus.OUT_OF_EAR, EarDetectionStatus.OUT_OF_EAR)
        val pending = requireNotNull(pendingSwitch())
        settings(true, false)
        assertTrue(pending.isCancelled)
        assertNull(pendingSwitch())
    }

    @Test fun disablingDetectionCancelsAndForgetsAutomaticPause() = synchronized(controller) {
        wear(EarDetectionStatus.OUT_OF_EAR, EarDetectionStatus.OUT_OF_EAR)
        val pending = requireNotNull(pendingSwitch())
        field("pausedAudio").setBoolean(controller, true)
        settings(false, true)
        assertTrue(pending.isCancelled)
        assertNull(pendingSwitch())
        assertFalse(field("pausedAudio").getBoolean(controller))
    }

    @Test fun policyBroadcastPreservesSpeakerChoiceWhenDetectionIsDisabled() = synchronized(controller) {
        val intent = mock(Intent::class.java)
        `when`(intent.action).thenReturn(HyperPodsAction.ACTION_PODS_SETTINGS_CHANGED)
        `when`(intent.getStringExtra("key")).thenReturn(HyperPodsPrefsKey.EAR_DETECTION)
        `when`(intent.hasExtra("ear_detection")).thenReturn(true)
        `when`(intent.hasExtra("switch_speaker")).thenReturn(true)
        `when`(intent.getBooleanExtra("ear_detection", true)).thenReturn(false)
        `when`(intent.getBooleanExtra("switch_speaker", true)).thenReturn(true)
        controller.handleUIEvent(intent)
        assertFalse(field("earDetection").getBoolean(controller))
        assertTrue(field("autoSwitchToSpeaker").getBoolean(controller))
        assertNull(pendingSwitch())
        `when`(intent.getBooleanExtra("ear_detection", true)).thenReturn(true)
        controller.handleUIEvent(intent)
        assertTrue(field("earDetection").getBoolean(controller))
        assertTrue(field("autoSwitchToSpeaker").getBoolean(controller))
    }

    @Test fun repeatedQuickWearChangesDoNotReuseCancelledJobs() = synchronized(controller) {
        repeat(10) {
            wear(EarDetectionStatus.OUT_OF_EAR, EarDetectionStatus.OUT_OF_EAR)
            val pending = requireNotNull(pendingSwitch())
            wear(EarDetectionStatus.IN_EAR, EarDetectionStatus.IN_EAR)
            assertTrue(pending.isCancelled)
            assertNull(pendingSwitch())
        }
    }

    @Test fun duplicateOutOfEarReportKeepsOnlyOnePendingSwitch() = synchronized(controller) {
        wear(EarDetectionStatus.OUT_OF_EAR, EarDetectionStatus.OUT_OF_EAR)
        val pending = requireNotNull(pendingSwitch())
        wear(EarDetectionStatus.OUT_OF_EAR, EarDetectionStatus.OUT_OF_EAR)
        assertSame(pending, pendingSwitch())
    }

    @Test fun disconnectCancelsPendingSwitchAndClearsAudioState() = synchronized(controller) {
        wear(EarDetectionStatus.OUT_OF_EAR, EarDetectionStatus.OUT_OF_EAR)
        val pending = requireNotNull(pendingSwitch())
        field("pausedAudio").setBoolean(controller, true)
        // No live receiver/router is registered in this JVM fixture.
        field("mContext").set(controller, null)
        controller.disconnectedPod(ContextWrapper(null), device)
        assertTrue(pending.isCancelled)
        assertNull(pendingSwitch())
        assertFalse(field("pausedAudio").getBoolean(controller))
        assertFalse(field("switchedToSpeaker").getBoolean(controller))
        assertFalse(field("earDetectionStateValid").getBoolean(controller))
    }

    @Test fun anotherDeviceDisconnectCannotCancelTheCurrentSession() = synchronized(controller) {
        wear(EarDetectionStatus.OUT_OF_EAR, EarDetectionStatus.OUT_OF_EAR)
        val pending = pendingSwitch()
        val generation = field("sessionId").getLong(controller)
        controller.disconnectedPod(ContextWrapper(null), mock(BluetoothDevice::class.java))
        assertSame(pending, pendingSwitch())
        assertTrue(requireNotNull(pending).isActive)
        assertEquals(generation, field("sessionId").getLong(controller))
    }

    @Test fun physicalDisconnectRequestsNotificationRemovalForTheConnectedDevice() {
        val context = mock(Context::class.java)
        field("mContext").set(controller, context)
        val cancellations = mutableListOf<Intent>()
        mockConstruction(Intent::class.java) { intent, construction ->
            if (construction.arguments().firstOrNull() == HyperPodsAction.ACTION_CANCEL_NOTIFICATION)
                cancellations.add(intent)
        }.use {
            controller.disconnectedPod(context, device)
            val cancellation = cancellations.single()
            verify(cancellation).putExtra("device", device)
            verify(cancellation, atLeastOnce()).setPackage("com.xiaomi.bluetooth")
            verify(context).sendBroadcast(same(cancellation), isNull(), isNull())
        }
    }

    @Test fun obsoleteControlAttemptCannotReplaceTheNewSessionSocket() = runBlocking {
        val generation = field("sessionId").getLong(controller)
        val current = mock(BluetoothSocket::class.java)
        val obsolete = mock(BluetoothSocket::class.java)
        field("socket").set(controller, current)
        with(controller) {
            try {
                runControlAttempt(ContextWrapper(null), device, generation - 1) { obsolete }
                fail("Obsolete attempt must be cancelled")
            } catch (_: CancellationException) { }
        }
        verify(obsolete).close()
        verify(obsolete, never()).connect()
        assertSame(current, field("socket").get(controller))
        assertEquals(generation, field("sessionId").getLong(controller))
    }

    private fun closedStreamSocket() = mock(BluetoothSocket::class.java).apply {
        `when`(isConnected).thenReturn(true)
        `when`(outputStream).thenReturn(ByteArrayOutputStream())
        `when`(inputStream).thenReturn(ByteArrayInputStream(byteArrayOf()))
    }

    @Test fun stuckConnectIsClosedAndLeavesAudioSessionConnected() = runBlocking {
        val context = field("mContext").get(controller) as ContextWrapper
        field("mContext").set(controller, null)
        field("audioConnectionState").setInt(controller, BluetoothProfile.STATE_CONNECTED)
        val generation = field("sessionId").getLong(controller)
        val audioGeneration = field("audioSessionId").getLong(controller)
        val socket = closedStreamSocket()
        val closed = CountDownLatch(1)
        doAnswer { closed.countDown(); null }.`when`(socket).close()
        doAnswer {
            if (!closed.await(15, TimeUnit.SECONDS)) throw IOException("Watchdog did not close the socket")
            throw IOException("Socket closed during connect")
        }.`when`(socket).connect()
        val attempt = launch(kotlinx.coroutines.Dispatchers.IO) {
            with(controller) { runControlAttempt(context, device, generation) { socket } }
        }
        withTimeout(16000) { attempt.join() }
        verify(socket, atLeastOnce()).close()
        assertEquals("socket_connect_failed", field("lastStatusFailure").get(controller))
        assertEquals(BluetoothProfile.STATE_CONNECTED, field("audioConnectionState").getInt(controller))
        assertEquals(audioGeneration, field("audioSessionId").getLong(controller))
    }

    @Test fun controlStreamEndClosesOnlyTheControlSocketAndKeepsTheUiSession() = runBlocking {
        val context = field("mContext").get(controller) as ContextWrapper
        val socket = closedStreamSocket()
        with(controller) { runControlAttempt(context, device, field("sessionId").getLong(controller)) { socket } }
        verify(socket).close()
        assertNull(field("socket").get(controller))
        assertNull(field("aacpManager").get(controller))
        assertSame(context, field("mContext").get(controller))
        assertEquals("failed", field("connectionState").get(controller))
        assertEquals("stream_closed", field("lastStatusFailure").get(controller))
    }

    @Test fun controlStreamEndDoesNotRemoveTheConnectedNotification() = runBlocking {
        val context = field("mContext").get(controller) as ContextWrapper
        val actions = mutableListOf<Any?>()
        mockConstruction(Intent::class.java) { _, construction ->
            actions.add(construction.arguments().firstOrNull())
        }.use {
            with(controller) {
                runControlAttempt(context, device, field("sessionId").getLong(controller)) { closedStreamSocket() }
            }
        }
        assertFalse(actions.contains(HyperPodsAction.ACTION_CANCEL_NOTIFICATION))
        assertFalse(actions.contains(HyperPodsAction.ACTION_PODS_DISCONNECTED))
        assertSame(context, field("mContext").get(controller))
    }

    @Test fun oldReaderCompletionCannotClearANewerControlSession() = runBlocking {
        val context = field("mContext").get(controller) as ContextWrapper
        val generation = field("sessionId").getLong(controller)
        val oldSocket = closedStreamSocket()
        val newSocket = mock(BluetoothSocket::class.java)
        val newManager = AACPManager(newSocket)
        val newContext = ContextWrapper(null)
        `when`(oldSocket.inputStream).thenAnswer {
            field("sessionId").setLong(controller, generation + 1)
            field("socket").set(controller, newSocket)
            field("aacpManager").set(controller, newManager)
            field("mContext").set(controller, newContext)
            ByteArrayInputStream(byteArrayOf())
        }
        with(controller) { runControlAttempt(context, device, generation) { oldSocket } }
        verify(oldSocket).close()
        verify(newSocket, never()).close()
        assertSame(newSocket, field("socket").get(controller))
        assertSame(newManager, field("aacpManager").get(controller))
        assertSame(newContext, field("mContext").get(controller))
        assertEquals(generation + 1, field("sessionId").getLong(controller))
    }

    @Test fun automaticRecoveryIsBoundedAndRetainsManualRecoveryAfterExhaustion() = runBlocking {
        val context = field("mContext").get(controller) as ContextWrapper
        val sockets = mutableListOf<BluetoothSocket>()
        with(controller) {
            recoverControlSession(context, device, field("sessionId").getLong(controller)) {
                closedStreamSocket().also { sockets.add(it) }
            }
        }
        assertEquals(4, sockets.size)
        sockets.forEach { verify(it).close() }
        assertSame(context, field("mContext").get(controller))
        assertEquals("failed", field("connectionState").get(controller))
    }

    @Test fun initialBatteryAndInfoMissingAfterFeatureAckRecoverOnAFreshControlSocket() = runBlocking {
        val context = field("mContext").get(controller) as ContextWrapper
        field("mContext").set(controller, null) // No broadcasts from the timeout thread in this fixture.
        field("audioConnectionState").setInt(controller, BluetoothProfile.STATE_CONNECTED)
        val audioGeneration = field("audioSessionId").getLong(controller)
        val first = closedStreamSocket()
        val closed = CountDownLatch(1)
        doAnswer { closed.countDown(); null }.`when`(first).close()
        `when`(first.inputStream).thenReturn(object : InputStream() {
            private var sentFeaturesAck = false
            override fun read(): Int = error("Use SDU reads")
            override fun read(buffer: ByteArray): Int {
                if (!sentFeaturesAck) {
                    sentFeaturesAck = true
                    val packet = byteArrayOf(4, 0, 4, 0, 0x2b, 0)
                    packet.copyInto(buffer)
                    return packet.size
                }
                if (!closed.await(14, TimeUnit.SECONDS)) throw IOException("Initial recovery did not close the socket")
                return -1
            }
        })
        val second = closedStreamSocket()
        val reports = ArrayDeque(listOf(
            batteryReport(BatteryComponent.LEFT, BatteryComponent.RIGHT),
            earReport(EarDetectionStatus.IN_EAR, EarDetectionStatus.IN_EAR),
            byteArrayOf(4, 0, 4, 0, 0x1d, 0, 0) + "protocol\u0000Test headset\u0000A3049\u0000".toByteArray(),
        ))
        `when`(second.inputStream).thenReturn(object : InputStream() {
            override fun read(): Int = error("Use SDU reads")
            override fun read(buffer: ByteArray): Int {
                val packet = reports.removeFirstOrNull() ?: throw CancellationException("Finished reading recovery reports")
                packet.copyInto(buffer)
                return packet.size
            }
        })

        try {
            var attempts = 0
            with(controller) {
                try {
                    recoverControlSession(context, device, field("sessionId").getLong(controller)) {
                        if (++attempts == 1) first else {
                            assertEquals("status_timeout", field("lastStatusFailure").get(controller))
                            second
                        }
                    }
                } catch (expected: CancellationException) {
                    assertEquals("Finished reading recovery reports", expected.message)
                }
            }
            assertEquals(2, attempts)
            assertEquals(82, controller.currentBatteryParams.left!!.battery)
            assertEquals(65, controller.currentBatteryParams.right!!.battery)
            assertEquals("A3049", controller.currentPodsInfo?.modelNumber)
            assertEquals(audioGeneration, field("audioSessionId").getLong(controller))
            assertEquals(BluetoothProfile.STATE_CONNECTED, field("audioConnectionState").getInt(controller))
        } finally {
            controller.disconnectedPod(context, device)
        }
    }

    @Test fun readerFrameAndTrailingStatusAutomaticallyRecoverWithoutAUserCommand() = runBlocking {
        val context = field("mContext").get(controller) as ContextWrapper
        field("mContext").set(controller, null)
        field("audioConnectionState").setInt(controller, BluetoothProfile.STATE_CONNECTED)
        val audioGeneration = field("audioSessionId").getLong(controller)
        val socket = closedStreamSocket()
        val closed = CountDownLatch(1)
        doAnswer { closed.countDown(); null }.`when`(socket).close()
        val reports = ArrayDeque(listOf(
            byteArrayOf(2, 0, 4, 0, 0, 0),
            earReport(EarDetectionStatus.IN_EAR, EarDetectionStatus.IN_EAR),
            batteryReport(BatteryComponent.LEFT, BatteryComponent.RIGHT),
        ))
        `when`(socket.inputStream).thenReturn(object : InputStream() {
            override fun read(): Int = error("Use SDU reads")
            override fun read(buffer: ByteArray): Int {
                val packet = reports.removeFirstOrNull()
                if (packet != null) {
                    packet.copyInto(buffer)
                    return packet.size
                }
                if (!closed.await(8, TimeUnit.SECONDS)) throw IOException("Frame probe did not close the stalled socket")
                return -1
            }
        })
        with(controller) {
            runControlAttempt(context, device, field("sessionId").getLong(controller)) { socket }
        }
        assertEquals("frame_probe_no_reply", field("lastStatusFailure").get(controller))
        assertEquals(BluetoothProfile.STATE_CONNECTED, field("audioConnectionState").getInt(controller))
        assertEquals(audioGeneration, field("audioSessionId").getLong(controller))
    }

    @Test fun physicalDisconnectDuringRetryDelayStopsRecovery() = runBlocking {
        val context = field("mContext").get(controller) as ContextWrapper
        val generation = field("sessionId").getLong(controller)
        var attempts = 0
        val recovery = launch {
            with(controller) {
                recoverControlSession(context, device, generation) {
                    attempts++
                    closedStreamSocket()
                }
            }
        }
        delay(100)
        assertEquals(1, attempts)
        // This fixture has no registered Android receiver to unregister.
        field("mContext").set(controller, null)
        controller.disconnectedPod(context, device)
        recovery.join()
        assertTrue(recovery.isCancelled)
        assertEquals(1, attempts)
    }

    @Test fun cancellationDuringSocketCreationIsNotTreatedAsARetryableFailure() = runBlocking {
        var attempts = 0
        with(controller) {
            try {
                recoverControlSession(ContextWrapper(null), device, field("sessionId").getLong(controller)) {
                    attempts++
                    throw CancellationException("Cancelled by disconnect")
                }
                fail("Cancellation must escape recovery")
            } catch (_: CancellationException) { }
        }
        assertEquals(1, attempts)
    }

    private fun route(id: String, routeType: Int) = mock(MediaRoute2Info::class.java).apply {
        `when`(this.id).thenReturn(id)
        `when`(this.type).thenReturn(routeType)
        `when`(this.name).thenReturn("Test headset")
    }

    @Test fun automaticPauseOnlyAppliesWhenThisHeadsetIsTheSelectedMediaOutput() = synchronized(controller) {
        val context = mock(Context::class.java)
        val audio = mock(AudioManager::class.java)
        `when`(context.getSystemService(AudioManager::class.java)).thenReturn(audio)
        `when`(audio.isMusicActive).thenReturn(true)
        field("mContext").set(controller, context)
        MediaControl.mContext = context
        `when`(device.name).thenReturn("Test headset")
        val router = mock(MediaRouter2::class.java)
        val routing = mock(MediaRouter2.RoutingController::class.java)
        `when`(router.systemController).thenReturn(routing)
        field("mediaRouter").set(controller, router)
        val headset = route("headset", MediaRoute2Info.TYPE_BLUETOOTH_A2DP)
        val speaker = route("speaker", MediaRoute2Info.TYPE_BUILTIN_SPEAKER)
        val other = route("other", MediaRoute2Info.TYPE_BLUETOOTH_A2DP).apply {
            `when`(name).thenReturn("Other headphones")
        }
        settings(true, false)
        try {
            for (selected in listOf(speaker, other)) {
                `when`(routing.selectedRoutes).thenReturn(listOf(selected))
                wear(EarDetectionStatus.IN_EAR, EarDetectionStatus.IN_EAR)
                wear(EarDetectionStatus.OUT_OF_EAR, EarDetectionStatus.OUT_OF_EAR)
                assertFalse(field("pausedAudio").getBoolean(controller))
            }
            verify(audio, never()).dispatchMediaKeyEvent(any())
            `when`(routing.selectedRoutes).thenReturn(listOf(headset))
            wear(EarDetectionStatus.IN_EAR, EarDetectionStatus.IN_EAR)
            wear(EarDetectionStatus.OUT_OF_EAR, EarDetectionStatus.OUT_OF_EAR)
            assertTrue(field("pausedAudio").getBoolean(controller))
            verify(audio, times(2)).dispatchMediaKeyEvent(any())
        } finally { MediaControl.mContext = null }
    }

    @Test fun removingEarbudsCannotTakeOverAnotherSelectedOutput() {
        `when`(device.name).thenReturn("Test headset")
        val router = mock(MediaRouter2::class.java)
        val routing = mock(MediaRouter2.RoutingController::class.java)
        val speaker = route("speaker", MediaRoute2Info.TYPE_BUILTIN_SPEAKER)
        val headset = route("headset", MediaRoute2Info.TYPE_BLUETOOTH_A2DP)
        val other = route("other", MediaRoute2Info.TYPE_BLUETOOTH_A2DP).apply {
            `when`(name).thenReturn("Another headset")
        }
        `when`(router.systemController).thenReturn(routing)
        `when`(routing.selectedRoutes).thenReturn(listOf(other))
        field("mediaRouter").set(controller, router)
        controller.routes = listOf(speaker, headset, other)
        synchronized(controller) { wear(EarDetectionStatus.OUT_OF_EAR, EarDetectionStatus.OUT_OF_EAR) }
        Thread.sleep(650)
        verify(router, never()).transferTo(any())
        assertFalse(field("switchedToSpeaker").getBoolean(controller))
    }

    @Test fun userRouteChangeAfterAutomaticSpeakerSwitchRelinquishesOwnership() = synchronized(controller) {
        `when`(device.name).thenReturn("Test headset")
        val router = mock(MediaRouter2::class.java)
        val routing = mock(MediaRouter2.RoutingController::class.java)
        val headset = route("headset", MediaRoute2Info.TYPE_BLUETOOTH_A2DP)
        val other = route("other", MediaRoute2Info.TYPE_BLUETOOTH_A2DP).apply {
            `when`(name).thenReturn("Another headset")
        }
        `when`(router.systemController).thenReturn(routing)
        `when`(routing.selectedRoutes).thenReturn(listOf(other))
        field("mediaRouter").set(controller, router)
        field("switchedToSpeaker").setBoolean(controller, true)
        field("pausedAudio").setBoolean(controller, true)
        controller.routes = listOf(headset, other)
        wear(EarDetectionStatus.IN_EAR, EarDetectionStatus.IN_EAR)
        verify(router, never()).transferTo(any())
        assertFalse(field("switchedToSpeaker").getBoolean(controller))
        assertFalse(field("pausedAudio").getBoolean(controller))
    }

    @Test fun cachedRouteIdentitySurvivesAliasAndDisplayNameChanges() = synchronized(controller) {
        `when`(device.name).thenReturn("Test headset")
        val headset = route("stable-headset-id", MediaRoute2Info.TYPE_BLUETOOTH_A2DP)
        val finder = type.getDeclaredMethod("findHeadphoneRoute", List::class.java).apply { isAccessible = true }
        assertSame(headset, finder.invoke(controller, listOf(headset)))
        `when`(device.alias).thenReturn("New name")
        `when`(headset.name).thenReturn("New name")
        assertSame(headset, finder.invoke(controller, listOf(headset)))
        assertEquals("stable-headset-id", field("headphoneRouteId").get(controller))
    }

    @Test fun sameNamedRoutesAreNotGuessedWhenNoIdentityIsAvailable() = synchronized(controller) {
        `when`(device.name).thenReturn("Test headset")
        val first = route("first", MediaRoute2Info.TYPE_BLUETOOTH_A2DP)
        val second = route("second", MediaRoute2Info.TYPE_BLUETOOTH_A2DP)
        val finder = type.getDeclaredMethod("findHeadphoneRoute", List::class.java).apply { isAccessible = true }
        assertNull(finder.invoke(controller, listOf(first, second)))
        assertNull(field("headphoneRouteId").get(controller))
    }

    @Test fun headphoneRestoreWaitsForSuccessfulTransferCallback() = synchronized(controller) {
        wear(EarDetectionStatus.IN_EAR, EarDetectionStatus.IN_EAR)
        `when`(device.name).thenReturn("Test headset")
        val router = mock(MediaRouter2::class.java)
        val routing = mock(MediaRouter2.RoutingController::class.java)
        val speaker = route("speaker", MediaRoute2Info.TYPE_BUILTIN_SPEAKER)
        val headset = route("headset", MediaRoute2Info.TYPE_BLUETOOTH_A2DP)
        `when`(router.systemController).thenReturn(routing)
        `when`(routing.selectedRoutes).thenReturn(listOf(speaker))
        field("mediaRouter").set(controller, router)
        field("switchedToSpeaker").setBoolean(controller, true)
        controller.routes = listOf(speaker, headset)
        invokeRouteChanged()
        verify(router).transferTo(headset)
        assertTrue(field("switchedToSpeaker").getBoolean(controller))
        assertSame(headset, field("pendingAudioRoute").get(controller))
        `when`(routing.selectedRoutes).thenReturn(listOf(headset))
        invokeRouteChanged()
        assertFalse(field("switchedToSpeaker").getBoolean(controller))
        assertNull(field("pendingAudioRoute").get(controller))
        verify(router, times(1)).transferTo(headset)
    }

    @Test fun synchronousRouteFailureDoesNotLeaveAnUnfinishablePendingTransfer() = synchronized(controller) {
        wear(EarDetectionStatus.IN_EAR, EarDetectionStatus.IN_EAR)
        `when`(device.name).thenReturn("Test headset")
        val router = mock(MediaRouter2::class.java)
        val routing = mock(MediaRouter2.RoutingController::class.java)
        val speaker = route("speaker", MediaRoute2Info.TYPE_BUILTIN_SPEAKER)
        val headset = route("headset", MediaRoute2Info.TYPE_BLUETOOTH_A2DP)
        `when`(router.systemController).thenReturn(routing)
        `when`(routing.selectedRoutes).thenReturn(listOf(speaker))
        doThrow(IllegalArgumentException("Unavailable route")).`when`(router).transferTo(headset)
        field("mediaRouter").set(controller, router)
        field("switchedToSpeaker").setBoolean(controller, true)
        controller.routes = listOf(speaker, headset)
        invokeRouteChanged()
        assertNull(field("pendingAudioRoute").get(controller))
        assertEquals(1, field("routeRetryCount").getInt(controller))
        assertTrue((field("routeRetryJob").get(controller) as Job).isActive)
    }

    @Test fun failedHeadphoneTransferKeepsOwnershipAndSchedulesBoundedRetry() = synchronized(controller) {
        wear(EarDetectionStatus.IN_EAR, EarDetectionStatus.IN_EAR)
        val headset = route("headset", MediaRoute2Info.TYPE_BLUETOOTH_A2DP)
        field("switchedToSpeaker").setBoolean(controller, true)
        repeat(3) {
            field("pendingAudioRoute").set(controller, headset)
            type.getDeclaredMethod("handleAudioRouteFailure", MediaRoute2Info::class.java).apply {
                isAccessible = true
            }.invoke(controller, headset)
            assertNull(field("pendingAudioRoute").get(controller))
            assertTrue(field("switchedToSpeaker").getBoolean(controller))
            (field("routeRetryJob").get(controller) as Job?)?.cancel()
        }
        assertEquals(3, field("routeRetryCount").getInt(controller))
    }

    private fun invokeRouteChanged() {
        type.getDeclaredMethod("handleAudioRouteChanged").apply {
            isAccessible = true
        }.invoke(controller)
    }
    @Test fun rewearDuringCellularOrVoipCallCancelsAutomaticResume() {
        val context = mock(Context::class.java)
        val audio = mock(AudioManager::class.java)
        val router = mock(MediaRouter2::class.java)
        val routing = mock(MediaRouter2.RoutingController::class.java)
        val headset = route("headset", MediaRoute2Info.TYPE_BLUETOOTH_A2DP)
        `when`(context.getSystemService(AudioManager::class.java)).thenReturn(audio)
        `when`(router.systemController).thenReturn(routing)
        `when`(routing.selectedRoutes).thenReturn(listOf(headset))
        `when`(device.name).thenReturn("Test headset")
        field("mContext").set(controller, context)
        field("mediaRouter").set(controller, router)
        MediaControl.mContext = context
        settings(true, false)
        try {
            for (mode in listOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION)) {
                field("earDetectionStateValid").setBoolean(controller, false)
                field("pausedAudio").setBoolean(controller, true)
                `when`(audio.mode).thenReturn(mode)
                wear(EarDetectionStatus.IN_EAR, EarDetectionStatus.IN_EAR)
                assertFalse(field("pausedAudio").getBoolean(controller))
            }
            `when`(audio.mode).thenReturn(AudioManager.MODE_NORMAL)
            wear(EarDetectionStatus.OUT_OF_EAR, EarDetectionStatus.IN_EAR)
            wear(EarDetectionStatus.IN_EAR, EarDetectionStatus.IN_EAR)
            verify(audio, never()).dispatchMediaKeyEvent(any())
        } finally { MediaControl.mContext = null }
    }

    @Test fun incomingPhoneCallRelinquishesEarRemovalPauseOwnership() {
        val context = mock(Context::class.java)
        val phone = mock(TelephonyManager::class.java)
        `when`(context.getSystemService(TelephonyManager::class.java)).thenReturn(phone)
        `when`(phone.callState).thenReturn(TelephonyManager.CALL_STATE_RINGING)
        field("mContext").set(controller, context)
        field("pausedAudio").setBoolean(controller, true)
        val event = mock(Intent::class.java)
        `when`(event.action).thenReturn(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        (field("broadcastReceiver").get(controller) as BroadcastReceiver).onReceive(context, event)
        assertFalse(field("pausedAudio").getBoolean(controller))
    }

    @Test fun mediaRouteServiceFailureCannotEscapeToTheBluetoothProcessHandler() {
        val router = mock(MediaRouter2::class.java)
        field("mediaRouter").set(controller, router)
        field("switchedToSpeaker").setBoolean(controller, true)
        field("earDetection").setBoolean(controller, false)
        val executor = ArgumentCaptor.forClass(Executor::class.java)
        val callback = ArgumentCaptor.forClass(MediaRouter2.RouteCallback::class.java)
        val uncaught = AtomicReference<Throwable>()
        val signalled = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        try {
            type.getDeclaredMethod("startRoutesScan").apply { isAccessible = true }.invoke(controller)
            verify(router).registerRouteCallback(executor.capture(), callback.capture(), any())
            `when`(router.systemController).thenAnswer {
                entered.countDown()
                throw IllegalStateException("Synthetic route service failure")
            }
            Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.set(error); signalled.countDown() }
            executor.value.execute { callback.value.onRoutesUpdated(emptyList()) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            signalled.await(200, TimeUnit.MILLISECONDS)
            assertNull(uncaught.get())
            assertNotNull(field("mContext").get(controller))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
            field("switchedToSpeaker").setBoolean(controller, false)
        }
    }

    @Test fun socketReadExceptionHasItsOwnFailureAndRetainsTheAudioSession() = runBlocking {
        val context = field("mContext").get(controller) as ContextWrapper
        val socket = closedStreamSocket()
        `when`(socket.inputStream).thenThrow(IOException("Synthetic read failure"))
        field("lastStatusFailure").set(controller, "status_timeout") // Historical state must not mask this new cause.
        with(controller) { runControlAttempt(context, device, field("sessionId").getLong(controller)) { socket } }
        assertEquals("read_failed", field("lastStatusFailure").get(controller))
        assertSame(context, field("mContext").get(controller))
        verify(socket).close()
    }

    @Test fun reportCallbackExceptionIsNotMisreportedAsASocketReadFailure() = runBlocking {
        val context = field("mContext").get(controller) as ContextWrapper
        val socket = closedStreamSocket()
        val router = mock(MediaRouter2::class.java)
        field("mediaRouter").set(controller, router)
        `when`(router.systemController).thenThrow(IllegalStateException("Synthetic callback failure"))
        val reports = ArrayDeque(listOf(batteryReport(BatteryComponent.LEFT, BatteryComponent.RIGHT),
            earReport(EarDetectionStatus.IN_EAR, EarDetectionStatus.IN_EAR)))
        `when`(socket.inputStream).thenReturn(object : InputStream() {
            override fun read(): Int = error("Use SDU reads")
            override fun read(buffer: ByteArray): Int {
                val packet = reports.removeFirstOrNull() ?: return -1
                packet.copyInto(buffer)
                return packet.size
            }
        })
        with(controller) { runControlAttempt(context, device, field("sessionId").getLong(controller)) { socket } }
        assertEquals("packet_processing_failed", field("lastStatusFailure").get(controller))
        verify(socket).close()
    }

    private fun assertRouteTaskContained(start: () -> Job) {
        val uncaught = AtomicReference<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.set(error) }
            runBlocking { withTimeout(6000) { start().join() } }
            assertNull("A delayed route failure escaped into the Bluetooth host", uncaught.get())
        } finally { Thread.setDefaultUncaughtExceptionHandler(previous) }
    }

    @Test fun delayedSpeakerFailureKeepsTheControlChannelAndPauseOwnership() {
        val router = mock(MediaRouter2::class.java)
        val routing = mock(MediaRouter2.RoutingController::class.java)
        val headset = route("headset", MediaRoute2Info.TYPE_BLUETOOTH_A2DP)
        val speaker = route("speaker", MediaRoute2Info.TYPE_BUILTIN_SPEAKER)
        val controlSocket = mock(BluetoothSocket::class.java)
        val manager = AACPManager(controlSocket)
        val context = field("mContext").get(controller)
        val entered = AtomicBoolean()
        `when`(device.name).thenReturn("Test headset")
        `when`(router.systemController).thenReturn(routing)
        `when`(routing.selectedRoutes).thenReturn(listOf(headset))
        field("mediaRouter").set(controller, router)
        controller.routes = listOf(headset, speaker)
        try {
            assertRouteTaskContained {
                wear(EarDetectionStatus.OUT_OF_EAR, EarDetectionStatus.OUT_OF_EAR)
                val task = requireNotNull(pendingSwitch())
                field("aacpManager").set(controller, manager)
                field("socket").set(controller, controlSocket)
                field("pausedAudio").setBoolean(controller, true)
                `when`(router.systemController).thenAnswer {
                    entered.set(true)
                    throw IllegalStateException("Synthetic delayed speaker failure")
                }
                task
            }
            assertTrue(entered.get())
            assertTrue(field("pausedAudio").getBoolean(controller))
            assertSame(manager, field("aacpManager").get(controller))
            assertSame(context, field("mContext").get(controller))
            verify(controlSocket, never()).close()
            verify(router, never()).transferTo(any())
        } finally {
            doReturn(routing).`when`(router).systemController
            field("switchedToSpeaker").setBoolean(controller, false)
        }
    }

    @Test fun delayedHeadphoneRetryFailureRetainsOwnershipWithoutDisconnecting() {
        val router = mock(MediaRouter2::class.java)
        val routing = mock(MediaRouter2.RoutingController::class.java)
        val headset = route("headset", MediaRoute2Info.TYPE_BLUETOOTH_A2DP)
        val speaker = route("speaker", MediaRoute2Info.TYPE_BUILTIN_SPEAKER)
        val controlSocket = mock(BluetoothSocket::class.java)
        val entered = AtomicBoolean()
        `when`(device.name).thenReturn("Test headset")
        `when`(router.systemController).thenReturn(routing)
        `when`(routing.selectedRoutes).thenReturn(listOf(speaker))
        field("mediaRouter").set(controller, router)
        field("socket").set(controller, controlSocket)
        controller.routes = listOf(headset, speaker)
        wear(EarDetectionStatus.IN_EAR, EarDetectionStatus.IN_EAR)
        field("switchedToSpeaker").setBoolean(controller, true)
        field("pausedAudio").setBoolean(controller, true)
        field("pendingAudioRoute").set(controller, headset)
        try {
            assertRouteTaskContained {
                type.getDeclaredMethod("handleAudioRouteFailure", MediaRoute2Info::class.java)
                    .apply { isAccessible = true }.invoke(controller, headset)
                val task = field("routeRetryJob").get(controller) as Job
                `when`(router.systemController).thenAnswer {
                    entered.set(true)
                    throw IllegalStateException("Synthetic delayed retry failure")
                }
                task
            }
            assertTrue(entered.get())
            assertTrue(field("switchedToSpeaker").getBoolean(controller))
            assertTrue(field("pausedAudio").getBoolean(controller))
            verify(controlSocket, never()).close()
            verify(router, never()).transferTo(any())
        } finally {
            doReturn(routing).`when`(router).systemController
            field("switchedToSpeaker").setBoolean(controller, false)
        }
    }

    @Test fun transferTimeoutFailureCannotResumeUntilTheHeadsetRouteIsAvailableAgain() {
        val context = mock(Context::class.java)
        val audio = mock(AudioManager::class.java)
        val router = mock(MediaRouter2::class.java)
        val routing = mock(MediaRouter2.RoutingController::class.java)
        val headset = route("headset", MediaRoute2Info.TYPE_BLUETOOTH_A2DP)
        val speaker = route("speaker", MediaRoute2Info.TYPE_BUILTIN_SPEAKER)
        val controlSocket = mock(BluetoothSocket::class.java)
        val entered = AtomicBoolean()
        `when`(device.name).thenReturn("Test headset")
        `when`(context.getSystemService(AudioManager::class.java)).thenReturn(audio)
        `when`(audio.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(router.systemController).thenReturn(routing)
        `when`(routing.selectedRoutes).thenReturn(listOf(headset))
        field("mContext").set(controller, context)
        field("mediaRouter").set(controller, router)
        field("socket").set(controller, controlSocket)
        controller.routes = listOf(headset, speaker)
        MediaControl.mContext = context
        wear(EarDetectionStatus.IN_EAR, EarDetectionStatus.IN_EAR)
        field("pausedAudio").setBoolean(controller, true)
        field("switchedToSpeaker").setBoolean(controller, true)
        try {
            assertRouteTaskContained {
                type.getDeclaredMethod("requestAudioRoute", MediaRoute2Info::class.java)
                    .apply { isAccessible = true }.invoke(controller, speaker)
                val task = field("routeTransferTimeout").get(controller) as Job
                `when`(router.systemController).thenAnswer {
                    entered.set(true)
                    throw IllegalStateException("Synthetic route timeout failure")
                }
                task
            }
            assertTrue(entered.get())
            assertNull(field("pendingAudioRoute").get(controller))
            assertTrue(field("pausedAudio").getBoolean(controller))
            verify(audio, never()).dispatchMediaKeyEvent(any())
            verify(controlSocket, never()).close()
            doReturn(routing).`when`(router).systemController
            invokeRouteChanged()
            invokeRouteChanged()
            verify(audio, times(2)).dispatchMediaKeyEvent(any())
            assertFalse(field("pausedAudio").getBoolean(controller))
        } finally {
            doReturn(routing).`when`(router).systemController
            field("switchedToSpeaker").setBoolean(controller, false)
            MediaControl.mContext = null
        }
    }

    @Test fun ordinaryRemovalAndRewearPauseAndResumeOnlyOnce() {
        val context = mock(Context::class.java)
        val audio = mock(AudioManager::class.java)
        val router = mock(MediaRouter2::class.java)
        val routing = mock(MediaRouter2.RoutingController::class.java)
        val headset = route("headset", MediaRoute2Info.TYPE_BLUETOOTH_A2DP)
        `when`(device.name).thenReturn("Test headset")
        `when`(context.getSystemService(AudioManager::class.java)).thenReturn(audio)
        `when`(audio.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(router.systemController).thenReturn(routing)
        `when`(routing.selectedRoutes).thenReturn(listOf(headset))
        field("mContext").set(controller, context)
        field("mediaRouter").set(controller, router)
        settings(true, false)
        MediaControl.mContext = context
        val codes = mutableListOf<Int>()
        try {
            mockConstruction(KeyEvent::class.java) { _, construction -> codes.add(construction.arguments()[3] as Int) }.use {
                `when`(audio.isMusicActive).thenReturn(true)
                wear(EarDetectionStatus.IN_EAR, EarDetectionStatus.IN_EAR)
                wear(EarDetectionStatus.OUT_OF_EAR, EarDetectionStatus.IN_EAR)
                assertTrue(field("pausedAudio").getBoolean(controller))
                `when`(audio.isMusicActive).thenReturn(false)
                wear(EarDetectionStatus.IN_EAR, EarDetectionStatus.IN_EAR)
                wear(EarDetectionStatus.IN_EAR, EarDetectionStatus.IN_EAR)
                assertEquals(listOf(KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY), codes)
                assertFalse(field("pausedAudio").getBoolean(controller))
            }
        } finally { MediaControl.mContext = null }
    }

    @Test fun physicalDisconnectStillIsolatesTheNextSessionFromItsOldRouteTimeout() = runBlocking {
        val router = mock(MediaRouter2::class.java)
        val speaker = route("speaker", MediaRoute2Info.TYPE_BUILTIN_SPEAKER)
        val newContext = mock(Context::class.java)
        val newSocket = mock(BluetoothSocket::class.java)
        field("mediaRouter").set(controller, router)
        val audioGeneration = field("audioSessionId").getLong(controller)
        type.getDeclaredMethod("requestAudioRoute", MediaRoute2Info::class.java)
            .apply { isAccessible = true }.invoke(controller, speaker)
        val oldTimeout = field("routeTransferTimeout").get(controller) as Job
        // No receiver/router registration belongs to this fixture.
        field("mContext").set(controller, null)
        controller.disconnectedPod(ContextWrapper(null), device)
        assertTrue(oldTimeout.isCancelled)
        assertTrue(field("audioSessionId").getLong(controller) > audioGeneration)
        field("mContext").set(controller, newContext)
        field("socket").set(controller, newSocket)
        field("pendingAudioRoute").set(controller, speaker)
        field("pausedAudio").setBoolean(controller, true)
        try {
            withTimeout(1000) { oldTimeout.join() }
            assertSame(speaker, field("pendingAudioRoute").get(controller))
            assertSame(newContext, field("mContext").get(controller))
            assertTrue(field("pausedAudio").getBoolean(controller))
            verify(newSocket, never()).close()
            verify(router, times(1)).transferTo(speaker)
        } finally {
            field("pendingAudioRoute").set(controller, null)
            field("pausedAudio").setBoolean(controller, false)
        }
    }

}
