package moe.chenxy.hyperpods.utils

import android.Manifest
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.telephony.SubscriptionManager
import android.telephony.SubscriptionInfo
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.MockedStatic
import org.mockito.Mockito.*

@Suppress("DEPRECATION")
class HeadGestureCallsTest {
    private val context = mock(Context::class.java)
    private val phone = mock(TelephonyManager::class.java)
    private val telecom = mock(TelecomManager::class.java)
    private val subscriptions = mock(SubscriptionManager::class.java)
    private val firstSim = mock(TelephonyManager::class.java)
    private val secondSim = mock(TelephonyManager::class.java)
    private val socket = mock(BluetoothSocket::class.java)
    private val output = ByteArrayOutputStream()
    private val manager = AACPManager(socket)
    private val lock = Any()
    private lateinit var calls: HeadGestureCalls
    private lateinit var clock: MockedStatic<SystemClock>
    private var at = 0L

    @Before fun setUp() {
        `when`(context.getSystemService(TelephonyManager::class.java)).thenReturn(phone)
        `when`(context.getSystemService(TelecomManager::class.java)).thenReturn(telecom)
        `when`(context.getSystemService(SubscriptionManager::class.java)).thenReturn(subscriptions)
        val first = mock(SubscriptionInfo::class.java)
        val second = mock(SubscriptionInfo::class.java)
        `when`(first.subscriptionId).thenReturn(1)
        `when`(second.subscriptionId).thenReturn(2)
        `when`(subscriptions.activeSubscriptionInfoList).thenReturn(listOf(first, second))
        `when`(phone.createForSubscriptionId(1)).thenReturn(firstSim)
        `when`(phone.createForSubscriptionId(2)).thenReturn(secondSim)
        `when`(firstSim.callStateForSubscription).thenReturn(TelephonyManager.CALL_STATE_RINGING)
        `when`(context.checkSelfPermission(anyString())).thenReturn(PackageManager.PERMISSION_GRANTED)
        `when`(phone.callState).thenReturn(TelephonyManager.CALL_STATE_RINGING)
        `when`(telecom.endCall()).thenReturn(true)
        `when`(socket.isConnected).thenReturn(true)
        `when`(socket.outputStream).thenReturn(output)
        clock = mockStatic(SystemClock::class.java)
        clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { at }
        calls = HeadGestureCalls(context, lock) {}
    }

    @After fun tearDown() { synchronized(lock) { calls.close() }; clock.close() }

    private fun update(enabled: Boolean = true, supported: Boolean = true, worn: Boolean = true, current: AACPManager? = manager) =
        synchronized(lock) { calls.update(current, enabled, supported, worn) }

    private fun receiver(): BroadcastReceiver {
        val capture = ArgumentCaptor.forClass(BroadcastReceiver::class.java)
        verify(context).registerReceiver(capture.capture(), any(IntentFilter::class.java), eq(Context.RECEIVER_EXPORTED))
        return capture.value
    }

    private fun phoneChanged() {
        val intent = mock(Intent::class.java)
        `when`(intent.action).thenReturn(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        receiver().onReceive(context, intent)
    }

    private fun motion(horizontal: Boolean = false) = synchronized(lock) {
        repeat(100) { index ->
            val value = (1200 * sin(2 * PI * index / 24)).toInt().toShort()
            val packet = byteArrayOf(4, 0, 4, 0, 0x17, 0) + ByteArray(64)
            ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(if (horizontal) 51 else 53, value)
            at += 20
            calls.onPacket(packet)
        }
    }

    @Test fun nodAnswersOnceAndDuplicateRingingUpdatesNeverRestartTracking() {
        update()
        assertArrayEquals(manager.createDataPacket(manager.createAlternateStartHeadTrackingPacket()), output.toByteArray())
        motion()
        verify(telecom, times(1)).acceptRingingCall()
        verify(telecom, never()).endCall()
        assertEquals("answer_sent", calls.state)
        phoneChanged()
        motion()
        update()
        verify(telecom, times(1)).acceptRingingCall()
        assertArrayEquals(manager.createDataPacket(manager.createAlternateStartHeadTrackingPacket()) +
            manager.createDataPacket(manager.createAlternateStopHeadTrackingPacket()), output.toByteArray())
    }

    @Test fun shakeRejectsRingingCallButDoesNotAnswer() {
        update()
        motion(horizontal = true)
        verify(telecom).endCall()
        verify(telecom, never()).acceptRingingCall()
        assertEquals("reject_sent", calls.state)
    }

    @Test fun lateShakeNeverEndsAnAlreadyAnsweredCallEvenBeforeBroadcastArrives() {
        update()
        `when`(phone.callState).thenReturn(TelephonyManager.CALL_STATE_OFFHOOK)
        motion(horizontal = true)
        verifyNoInteractions(telecom)
        assertEquals("waiting", calls.state)
    }

    @Test fun endingCallStopsStreamAndNextCallGetsAFreshDetector() {
        update()
        `when`(phone.callState).thenReturn(TelephonyManager.CALL_STATE_IDLE)
        phoneChanged()
        assertEquals("waiting", calls.state)
        motion()
        verifyNoInteractions(telecom)
        `when`(phone.callState).thenReturn(TelephonyManager.CALL_STATE_RINGING)
        phoneChanged()
        motion()
        verify(telecom).acceptRingingCall()
    }

    @Test fun disabledUnsupportedOrDisconnectedDoesNotRegisterOrSendCommands() {
        update(enabled = false)
        assertEquals("disabled", calls.state)
        update(supported = false)
        assertEquals("unsupported", calls.state)
        update(current = null)
        assertEquals("disconnected", calls.state)
        assertEquals(0, output.size())
        verify(context, never()).registerReceiver(any(BroadcastReceiver::class.java), any(IntentFilter::class.java), anyInt())
    }

    @Test fun wearIsRequiredEvenWhenAutomaticEarDetectionSettingIsDisabled() {
        update(worn = false)
        assertEquals("not_worn", calls.state)
        assertEquals(0, output.size())
        motion()
        verifyNoInteractions(telecom)
        update(worn = true)
        assertEquals("starting", calls.state)
        update(worn = false)
        val bytes = output.size()
        motion()
        assertEquals(bytes, output.size())
        verifyNoInteractions(telecom)
    }

    @Test fun missingPermissionsDegradesWithoutStartingTrackingOrThrowing() {
        `when`(context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)).thenReturn(PackageManager.PERMISSION_DENIED)
        update()
        assertEquals("unavailable", calls.state)
        assertEquals(0, output.size())
    }

    @Test fun neitherAnswerNorModifyPermissionIsNotEnoughToControlCalls() {
        `when`(context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS)).thenReturn(PackageManager.PERMISSION_DENIED)
        `when`(context.checkSelfPermission(Manifest.permission.MODIFY_PHONE_STATE)).thenReturn(PackageManager.PERMISSION_DENIED)
        update()
        assertEquals("unavailable", calls.state)
        assertEquals(0, output.size())
    }

    @Test fun existingBluetoothPrivilegedPermissionCanReplaceAnswerPhoneCalls() {
        `when`(context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS)).thenReturn(PackageManager.PERMISSION_DENIED)
        update()
        motion()
        verify(telecom).acceptRingingCall()
    }

    @Test fun disablingOrClosingUnregistersAndIgnoresStaleCallbacks() {
        update()
        val stale = receiver()
        update(enabled = false)
        verify(context).unregisterReceiver(stale)
        val bytes = output.size()
        val intent = mock(Intent::class.java)
        `when`(intent.action).thenReturn(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        stale.onReceive(context, intent)
        motion()
        assertEquals(bytes, output.size())
        verifyNoInteractions(telecom)
    }

    @Test fun rejectedWritesAndPlatformCallFailuresDoNotCrashBluetooth() {
        `when`(socket.isConnected).thenReturn(false)
        update()
        assertEquals("write_failed", calls.state)
        `when`(socket.isConnected).thenReturn(true)
        update(enabled = false)
        update()
        doThrow(SecurityException()).`when`(telecom).acceptRingingCall()
        motion()
        assertEquals("call_failed", calls.state)
    }

    @Test fun activeOrIdleCallsNeverStartTracking() {
        for (state in listOf(TelephonyManager.CALL_STATE_IDLE, TelephonyManager.CALL_STATE_OFFHOOK)) {
            `when`(phone.callState).thenReturn(state)
            update()
            motion(horizontal = true)
            assertEquals("waiting", calls.state)
        }
        assertEquals(0, output.size())
        verifyNoInteractions(telecom)
    }

    @Test fun incomingCallOnSecondSimIsRecognized() {
        `when`(firstSim.callStateForSubscription).thenReturn(TelephonyManager.CALL_STATE_IDLE)
        `when`(secondSim.callStateForSubscription).thenReturn(TelephonyManager.CALL_STATE_RINGING)
        update()
        motion()
        verify(telecom).acceptRingingCall()
    }

    @Test fun voipRingingWithoutACellularIncomingCallNeverStartsTracking() {
        `when`(firstSim.callStateForSubscription).thenReturn(TelephonyManager.CALL_STATE_IDLE)
        `when`(secondSim.callStateForSubscription).thenReturn(TelephonyManager.CALL_STATE_IDLE)
        update()
        motion(horizontal = true)
        assertEquals("waiting", calls.state)
        assertEquals(0, output.size())
        verifyNoInteractions(telecom)
    }

    @Test fun missingPoseDataStopsTrackingAndDoesNotKeepRetryingTheSameCall() {
        val expired = CountDownLatch(1)
        calls = HeadGestureCalls(context, lock) { if (calls.state == "no_data") expired.countDown() }
        update()
        assertTrue("Missing-data window must expire", expired.await(10, TimeUnit.SECONDS))
        synchronized(lock) {
            assertEquals("no_data", calls.state)
            assertArrayEquals(manager.createDataPacket(manager.createAlternateStartHeadTrackingPacket()) +
                manager.createDataPacket(manager.createAlternateStopHeadTrackingPacket()), output.toByteArray())
            update()
            motion()
            verifyNoInteractions(telecom)
        }
    }

    @Test fun previewWorksWithCallToggleDisabledAndNoCallPermissions() {
        val stream = HeadTrackingStream { start -> manager.sendDataPacket(if (start)
            manager.createAlternateStartHeadTrackingPacket() else manager.createAlternateStopHeadTrackingPacket()) }
        calls = HeadGestureCalls(context, lock, stream) {}
        `when`(context.checkSelfPermission(anyString())).thenReturn(PackageManager.PERMISSION_DENIED)
        update(enabled = false)
        val reports = mutableListOf<HeadTrackingPreview.Snapshot>()
        val preview = HeadTrackingPreview(stream) { reports.add(it) }
        preview.start("test", 0, true, true, true, false)
        repeat(100) { index ->
            val packet = byteArrayOf(4, 0, 4, 0, 0x17, 0) + ByteArray(64)
            ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(53, (1200 * sin(2 * PI * index / 24)).toInt().toShort())
            preview.onPacket(packet, (index + 1) * 20L)
            calls.onPacket(packet)
        }
        assertTrue(reports.last().nods > 0)
        verifyNoInteractions(telecom)
        preview.stop("stopped")
    }

    @Test fun incomingCallTakesOverPreviewWithoutBeingStoppedByItsCleanup() {
        val stream = HeadTrackingStream { start -> manager.sendDataPacket(if (start)
            manager.createAlternateStartHeadTrackingPacket() else manager.createAlternateStopHeadTrackingPacket()) }
        calls = HeadGestureCalls(context, lock, stream) {}
        val preview = HeadTrackingPreview(stream) {}
        preview.start("test", 0, true, true, true, false)
        update()
        assertFalse(preview.active)
        preview.command("test", "stop", 100)
        motion()
        verify(telecom).acceptRingingCall()
    }
}
