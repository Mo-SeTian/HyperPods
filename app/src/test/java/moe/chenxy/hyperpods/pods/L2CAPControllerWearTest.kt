package moe.chenxy.hyperpods.pods

import android.bluetooth.BluetoothDevice
import android.app.BroadcastOptions
import android.content.ContextWrapper
import android.content.Intent
import android.bluetooth.BluetoothSocket
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.PodBatteryParams
import moe.chenxy.hyperpods.utils.AACPManager
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
        broadcasts = mockStatic(BroadcastOptions::class.java)
        val options = mock(BroadcastOptions::class.java, RETURNS_SELF)
        broadcasts.`when`<BroadcastOptions> { BroadcastOptions.makeBasic() }.thenReturn(options)
        device = mock(BluetoothDevice::class.java)
        field("mDevice").set(controller, device)
        field("mContext").set(controller, ContextWrapper(null))
        field("earDetectionStateValid").setBoolean(controller, false)
        field("batteryStateValid").setBoolean(controller, false)
        field("switchedToSpeaker").setBoolean(controller, false)
        field("pausedAudio").setBoolean(controller, false)
        field("pendingAudioRoute").set(controller, null)
        field("routeRetryCount").setInt(controller, 0)
        field("headphoneRouteId").set(controller, null)
        field("aacpManager").set(controller, null)
        field("socket").set(controller, null)
        field("lastDiagnostics").set(controller, null)
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

    @Test fun controlStreamEndClosesOnlyTheControlSocketAndKeepsTheUiSession() = runBlocking {
        val context = field("mContext").get(controller) as ContextWrapper
        val socket = closedStreamSocket()
        with(controller) { runControlAttempt(context, device, field("sessionId").getLong(controller)) { socket } }
        verify(socket).close()
        assertNull(field("socket").get(controller))
        assertNull(field("aacpManager").get(controller))
        assertSame(context, field("mContext").get(controller))
        assertEquals("failed", field("connectionState").get(controller))
        assertEquals("status_session_ended", field("lastStatusFailure").get(controller))
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
}
