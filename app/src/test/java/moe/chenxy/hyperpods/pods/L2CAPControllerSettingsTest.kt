package moe.chenxy.hyperpods.pods

import android.app.BroadcastOptions
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.ContextWrapper
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.media.AudioManager
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.telephony.TelephonyManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.chenxy.hyperpods.utils.AACPManager
import moe.chenxy.hyperpods.utils.CustomEq
import moe.chenxy.hyperpods.utils.AirPods5
import moe.chenxy.hyperpods.utils.AppPreferences
import moe.chenxy.hyperpods.utils.PodsSettings
import moe.chenxy.hyperpods.utils.SinglePodAnc
import moe.chenxy.hyperpods.utils.MediaControl
import moe.chenxy.hyperpods.utils.LowBatterySettings
import moe.chenxy.hyperpods.utils.LowBatteryReminder
import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.EarDetectionParams
import moe.chenxy.hyperpods.utils.data.PodBatteryParams
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import moe.chenxy.hyperpods.utils.data.HyperPodsPrefsKey as Key
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

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
        field("customEqConnectionId").setLong(controller, 1L)
        field("headGestures").setBoolean(controller, false)
        field("earDetection").setBoolean(controller, true)
        field("autoSwitchToSpeaker").setBoolean(controller, true)
        field("switchedToSpeaker").setBoolean(controller, false)
        field("conversationAwarenessActive").setBoolean(controller, false)
        field("singlePodAncRestoreAttempts").setInt(controller, 0)
        AirPodsNotifications.BatteryNotification.reset()
        AirPodsNotifications.EarDetection.reset()
        field("batteryStateValid").set(controller, false)
        field("earDetectionStateValid").set(controller, false)
        field("conversationPhoneVolume").set(controller, true)
        field("conversationPauseMedia").setBoolean(controller, false)
        field("lowBatterySettings").set(controller, LowBatterySettings())
        field("lastStatusFailure").set(controller, "")
        field("lastSettingKey").set(controller, "")
        field("lastSettingStatus").set(controller, "")
        field("lastSettingsState").set(controller, null)
        field("lastDiagnostics").set(controller, null)
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
        type.getDeclaredMethod("resetConversationVolume").apply { isAccessible = true }.invoke(controller)
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

    private fun requestEq(eq: CustomEq, session: Long = field("customEqConnectionId").getLong(controller)) = synchronized(controller) {
        val intent = mock(Intent::class.java)
        `when`(intent.action).thenReturn(HyperPodsAction.ACTION_PODS_SETTINGS_CHANGED)
        `when`(intent.getStringExtra("key")).thenReturn(PodsSettings.CUSTOM_EQ)
        `when`(intent.hasExtra("session")).thenReturn(true)
        `when`(intent.getLongExtra("session", -1)).thenReturn(session)
        `when`(intent.getParcelableExtra("custom_eq", CustomEq::class.java)).thenReturn(eq)
        controller.handleUIEvent(intent)
    }

    @Test fun nativeEqIsOptimisticOnlyAfterWriteAndAReportCanOverrideIt() {
        controller.currentPodsInfo = controller.currentPodsInfo!!.copy(version3 = "9A348")
        val value = CustomEq(2, 65, 45, 60)
        requestEq(value)
        assertArrayEquals(byteArrayOf(4, 0, 4, 0) + value.toPacket(), output.toByteArray())
        assertEquals(value, manager.customEq)
        assertTrue(manager.customEqUnconfirmed)
        assertEquals("sent", field("lastSettingStatus").get(controller))
        assertTrue(pending().isEmpty())
        synchronized(controller) {
            manager.receivePacket(byteArrayOf(4, 0, 4, 0) + value.copy(state = 1).toPacket())
        }
        assertEquals(value.copy(state = 1), manager.customEq)
        assertFalse(manager.customEqUnconfirmed)
        assertEquals("reported", field("lastSettingStatus").get(controller))
    }

    @Test fun nativeEqAcceptsNumericAndTextGenerationNineOnSupportedModels() {
        for (firmware in listOf("9441861", "9A348")) {
            controller.currentPodsInfo = controller.currentPodsInfo!!.copy(version3 = firmware)
            output.reset()
            val value = CustomEq(2, 65, 45, 60)
            requestEq(value)
            assertArrayEquals(byteArrayOf(4, 0, 4, 0) + value.toPacket(), output.toByteArray())
            assertEquals("sent", field("lastSettingStatus").get(controller))
            assertTrue(manager.customEqUnconfirmed)
        }
    }

    @Test fun nativeEqRejectsOldSessionAndUnsupportedModel() {
        for (firmware in listOf("8454592", "8A358", "")) {
            controller.currentPodsInfo = controller.currentPodsInfo!!.copy(version3 = firmware)
            requestEq(CustomEq(2))
            assertEquals("invalid", field("lastSettingStatus").get(controller))
            assertEquals(0, output.size())
            assertNull(manager.customEq)
        }
        controller.currentPodsInfo = controller.currentPodsInfo!!.copy(modelNumber = "A2084", version3 = "9A348")
        requestEq(CustomEq(2))
        assertEquals(0, output.size())
        controller.currentPodsInfo = controller.currentPodsInfo!!.copy(modelNumber = "A3049")
        requestEq(CustomEq(2), field("customEqConnectionId").getLong(controller) - 1)
        assertEquals(0, output.size())
        assertNull(manager.customEq)
    }

    @Test fun reportedEqSupportAllowsUnknownFirmwareAndFailedWritesPreserveBands() {
        val value = CustomEq(1, 20, 45, 75)
        synchronized(controller) { manager.receivePacket(byteArrayOf(4, 0, 4, 0) + value.toPacket()) }
        requestEq(value.copy(state = 2))
        assertEquals(value.copy(state = 2), manager.customEq)
        assertEquals("sent", field("lastSettingStatus").get(controller))
        output.reset()
        val revision = manager.customEqRevision
        `when`(socket.isConnected).thenReturn(false)
        requestEq(value.copy(state = 2, low = 80))
        assertEquals("write_failed", field("lastSettingStatus").get(controller))
        assertEquals(value.copy(state = 2), manager.customEq)
        assertEquals(revision + 1, manager.customEqRevision)
        assertEquals(0, output.size())
    }


    @Test fun sleepToggleUsesLibrePodsOptimisticStatusAndLaterReportsWin() {
        request(Key.SLEEP_DETECTION, 1)
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x35, 1, 0, 0, 0), output.toByteArray())
        val id = AACPManager.Companion.ControlCommandIdentifiers.SLEEP_DETECTION_CONFIG
        assertEquals(1.toByte(), manager.getControlCommandStatus(id)!!.value[0])
        assertTrue(manager.isAwaitingReport(id))
        assertEquals("sent", field("lastSettingStatus").get(controller))
        assertFalse(pending().containsKey(Key.SLEEP_DETECTION))
        receive(0x35, 2)
        assertEquals(2.toByte(), manager.getControlCommandStatus(id)!!.value[0])
        assertFalse(manager.isAwaitingReport(id))
    }

    @Test fun sleepWriteFailureDoesNotInventANewValueAndUnsupportedModelDoesNotWrite() {
        val id = AACPManager.Companion.ControlCommandIdentifiers.SLEEP_DETECTION_CONFIG
        receive(0x35, 2)
        `when`(socket.isConnected).thenReturn(false)
        request(Key.SLEEP_DETECTION, 1)
        assertEquals(2.toByte(), manager.getControlCommandStatus(id)!!.value[0])
        assertEquals("write_failed", field("lastSettingStatus").get(controller))
        `when`(socket.isConnected).thenReturn(true)
        controller.currentPodsInfo = controller.currentPodsInfo!!.copy(modelNumber = "A2084")
        request(Key.SLEEP_DETECTION, 1)
        assertEquals("invalid", field("lastSettingStatus").get(controller))
        assertEquals(0, output.size())
    }

    @Test fun airPods5ModesUseExistingCommandsWithoutTheProOnlyOffPrerequisite() {
        for (number in listOf("A3531", "A3532", "A3533", "A3439", "A3440", "A3441")) {
            controller.currentPodsInfo = controller.currentPodsInfo!!.copy(modelNumber = number)
            for (mode in 1..4) {
                output.reset()
                controller.setANCMode(mode)
                assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x0d, mode.toByte(), 0, 0, 0), output.toByteArray())
                assertFalse(pending().containsKey(Key.ALLOW_OFF_OPTION))
                assertTrue(pending().containsKey(PodsSettings.NOISE_MODE))
                receive(0x0d, mode.toByte())
                assertTrue(pending().isEmpty())
            }
        }
    }

    @Test fun airPods5ListeningTogglesRemainOptimisticAndOnlyWirelessWritesSwipeCommands() {
        for (number in listOf("A3531", "A3439")) {
            controller.currentPodsInfo = controller.currentPodsInfo!!.copy(modelNumber = number)
            for (key in listOf(Key.PERSONLIZED_VOLUME, Key.CONVERSATION_AWARENESS)) {
                output.reset()
                val id = PodsSettings.identifiers.getValue(key)
                request(key, 1)
                assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, id.value, 1, 0, 0, 0), output.toByteArray())
                assertTrue(pending().isEmpty())
                assertEquals(1.toByte(), manager.getControlCommandStatus(id)!!.value[0])
            }
            output.reset()
            request(Key.ADJUST_VOLUME_BY_SWIPER, 1)
            if (number == "A3531") {
                assertEquals(0, output.size())
                assertEquals("invalid", field("lastSettingStatus").get(controller))
            } else {
                assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x25, 1, 0, 0, 0), output.toByteArray())
                receive(0x25, 1)
                assertTrue(pending().isEmpty())
            }
        }
    }

    @Test fun lateAirPods5IdentificationUpdatesTheIslandWithoutFreshBatteryOrRepeatedNotifications() {
        controller.currentBatteryParams = BatteryParams(PodBatteryParams(80, isConnected = true), PodBatteryParams())
        val callback = field("packetCallback").get(controller) as AACPManager.PacketCallback
        val info = controller.currentPodsInfo!!.copy(modelNumber = "A3531")
        val updates = mutableListOf<Intent>()
        mockConstruction(Intent::class.java) { intent, construction ->
            if (construction.arguments().firstOrNull() == HyperPodsAction.ACTION_UPDATE_NOTIFICATION) updates.add(intent)
        }.use {
            callback.onDeviceInformationReceived(info)
            callback.onDeviceInformationReceived(info)
            assertEquals(1, updates.size)
            verify(updates.single()).putExtra("modelNumber", "A3531")
            val fresh = org.mockito.ArgumentCaptor.forClass(IntArray::class.java)
            verify(updates.single()).putExtra(eq("freshComponents"), fresh.capture())
            assertTrue(fresh.value.isEmpty())
        }
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x39, 0, 0, 0, 0), output.toByteArray())
    }

    @Test fun airPods5InitializationDoesNotBlockUsableControlsOnMissingOptionalReports() {
        controller.currentPodsInfo = controller.currentPodsInfo!!.copy(modelNumber = "A3531")
        coreReports()
        receive(0x0d, 4)
        for ((key, id) in PodsSettings.identifiers) {
            if (key in PodsSettings.optionalReports) continue
            if (!PodsSettings.supports(key, AirPods5())) continue
            val value = when (key) {
                PodsSettings.NOISE_MODE -> 4
                Key.LISTENING_MODE_BYTE -> 6
                Key.MICROPHONE_MODE, Key.PRESS_SPEED, Key.HOLD_DURATION -> 0
                Key.CHIME_VOLUME, Key.ADAPTIVE_AUDIO_LEVEL -> 50
                else -> 2
            }
            receive(id.value, value.toByte())
        }
        assertTrue(initialReady())
        controller.currentPodsInfo = controller.currentPodsInfo!!.copy(modelNumber = "A3439")
        assertTrue("Missing swipe settings must not block battery and listening controls", initialReady())
        assertFalse(statusSyncComplete())
        receive(0x25, 1)
        receive(0x23, 1)
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 0x2b, 0))
        assertTrue(initialReady())
        assertTrue(statusSyncComplete())
    }

    @Test fun privatePolicySnapshotAppliesAllChoicesWithoutSendingEarbudCommands() = synchronized(controller) {
        val policy = AppPreferences.Snapshot(false, false, false, LowBatterySettings(false, 35, true, 15))
        val intent = mock(Intent::class.java)
        `when`(intent.action).thenReturn(HyperPodsAction.ACTION_APP_SETTINGS_CHANGED)
        `when`(intent.getStringExtra(AppPreferences.EXTRA)).thenReturn(policy.encode())
        controller.handleUIEvent(intent)
        assertFalse(field("earDetection").getBoolean(controller))
        assertFalse(field("autoSwitchToSpeaker").getBoolean(controller))
        assertFalse(field("conversationPhoneVolume").getBoolean(controller))
        assertEquals(policy.lowBattery, field("lowBatterySettings").get(controller))
        val revision = field("appSettingsRevision").getLong(controller)
        `when`(intent.getStringExtra(AppPreferences.EXTRA)).thenReturn("{}")
        controller.handleUIEvent(intent)
        assertEquals(revision, field("appSettingsRevision").getLong(controller))
        assertEquals(policy.lowBattery, field("lowBatterySettings").get(controller))
        assertEquals(0, output.size())
    }

    @Test fun staleColdStartReplyCannotOverwriteANewerEditOrAnotherSession() = synchronized(controller) {
        val apply = type.getDeclaredMethod("applyInitialPolicy", Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, AppPreferences.Snapshot::class.java).apply { isAccessible = true }
        val session = field("sessionId").getLong(controller)
        val revision = field("appSettingsRevision").getLong(controller)
        field("earDetection").setBoolean(controller, true)
        val disabled = AppPreferences.Snapshot(earDetection = false)
        apply.invoke(controller, session, revision - 1, disabled)
        assertTrue(field("earDetection").getBoolean(controller))
        apply.invoke(controller, session - 1, revision, disabled)
        assertTrue(field("earDetection").getBoolean(controller))
        apply.invoke(controller, session, revision, null)
        assertTrue(field("earDetection").getBoolean(controller))
        apply.invoke(controller, session, revision, disabled)
        assertFalse(field("earDetection").getBoolean(controller))
    }

    @Test fun reminderEditDoesNotReplayTheUnchangedWearPolicy() = synchronized(controller) {
        field("earDetection").setBoolean(controller, false)
        field("autoSwitchToSpeaker").setBoolean(controller, false)
        field("pausedAudio").setBoolean(controller, true)
        field("routeRetryCount").setInt(controller, 3)
        val intent = mock(Intent::class.java)
        `when`(intent.action).thenReturn(HyperPodsAction.ACTION_APP_SETTINGS_CHANGED)
        `when`(intent.getStringExtra(AppPreferences.EXTRA)).thenReturn(
            AppPreferences.Snapshot(false, false, true, LowBatterySettings(earsThreshold = 35)).encode())
        controller.handleUIEvent(intent)
        assertTrue(field("pausedAudio").getBoolean(controller))
        assertEquals(3, field("routeRetryCount").getInt(controller))
        assertEquals(0, output.size())
        field("pausedAudio").setBoolean(controller, false)
    }

    @Test fun selectingOffWritesBothCommandsWithoutWaitingForTheOptionEcho() {
        receive(0x0d, 4)
        receive(0x34, 2)
        controller.setANCMode(1)
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x34, 1, 0, 0, 0,
            4, 0, 4, 0, 9, 0, 0x0d, 1, 0, 0, 0), output.toByteArray())
        assertFalse(field("pendingOffSelection").getBoolean(controller))
        assertTrue(manager.isAwaitingReport(AACPManager.Companion.ControlCommandIdentifiers.ALLOW_OFF_OPTION))
        assertEquals(2, manager.reportedControlValue(AACPManager.Companion.ControlCommandIdentifiers.ALLOW_OFF_OPTION))
        assertFalse(pending().containsKey(Key.ALLOW_OFF_OPTION))
        assertTrue(pending().containsKey(PodsSettings.NOISE_MODE))
        assertEquals(PodsSettings.NOISE_MODE, field("lastSettingKey").get(controller))
        assertEquals("sent", field("lastSettingStatus").get(controller))
        assertEquals(4.toByte(), manager.getControlCommandStatus(AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE)!!.value[0])
        receive(0x0d, 1)
        assertTrue(pending().isEmpty())
        assertEquals(1, field("currentAnc").getInt(controller))
        assertEquals("confirmed", field("lastSettingStatus").get(controller))
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

    @Test fun missingModeConfirmationStillTimesOutWithoutPretendingOffSucceeded() {
        receive(0x0d, 4)
        controller.setANCMode(1)
        field("mContext").set(controller, null)
        val deadline = System.nanoTime() + 4_000_000_000L
        while (synchronized(controller) { pending().isNotEmpty() } && System.nanoTime() < deadline) Thread.sleep(20)
        assertFalse(field("pendingOffSelection").getBoolean(controller))
        assertEquals(22, output.size())
        assertEquals(4, field("currentAnc").getInt(controller))
        assertEquals(PodsSettings.NOISE_MODE, field("lastSettingKey").get(controller))
        assertEquals("timeout", field("lastSettingStatus").get(controller))
        field("mContext").set(controller, ContextWrapper(null))
        controller.setANCMode(1)
        assertTrue(pending().containsKey(PodsSettings.NOISE_MODE))
        assertEquals(44, output.size()) // Retry sends both commands while permission is still unconfirmed.
    }

    private fun initialReady(): Boolean = type.getDeclaredMethod("initialStatusReady")
        .apply { isAccessible = true }.invoke(controller) as Boolean

    private fun statusSyncComplete(): Boolean = type.getDeclaredMethod("statusSyncComplete")
        .apply { isAccessible = true }.invoke(controller) as Boolean

    private fun coreReports(singleEar: Boolean = false) {
        AirPodsNotifications.BatteryNotification.setBattery(if (singleEar)
            byteArrayOf(4, 0, 4, 0, 4, 0, 1, 4, 1, 80, 2, 0)
        else byteArrayOf(4, 0, 4, 0, 4, 0, 2, 4, 1, 80, 2, 0, 2, 1, 70, 2, 0))
        controller.currentEarDetectionParams = EarDetectionParams(EarDetectionStatus.IN_EAR,
            if (singleEar) EarDetectionStatus.IN_CASE else EarDetectionStatus.IN_EAR)
        field("batteryStateValid").setBoolean(controller, true)
        field("earDetectionStateValid").setBoolean(controller, true)
    }

    private fun startModeChangeOnReadyControlChannel(): Job {
        coreReports(singleEar = true)
        field("socket").set(controller, socket)
        field("connectionState").set(controller, "connected")
        field("audioConnectionState").setInt(controller, BluetoothProfile.STATE_CONNECTED)
        receive(0x0d, 4)
        assertTrue(initialReady())
        output.reset()
        controller.setANCMode(3)
        field("mContext").set(controller, null)
        val entry = pending().values.single()!!
        return entry.javaClass.getDeclaredField("timeout").apply { isAccessible = true }.get(entry) as Job
    }

    @Test fun unansweredModeAndStatusRequestCloseOnlyTheControlSocket() = runBlocking {
        val audioGeneration = field("audioSessionId").getLong(controller)
        val expiry = startModeChangeOnReadyControlChannel()
        withTimeout(7000) { expiry.join() }
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x0d, 3, 0, 0, 0,
            4, 0, 4, 0, 0x0f, 0, -1, -1, -1, -1), output.toByteArray())
        assertEquals("timeout", field("lastSettingStatus").get(controller))
        assertEquals("control_no_reply", field("lastStatusFailure").get(controller))
        assertEquals("failed", field("connectionState").get(controller))
        assertEquals(4, field("currentAnc").getInt(controller))
        assertEquals(BluetoothProfile.STATE_CONNECTED, field("audioConnectionState").getInt(controller))
        assertEquals(audioGeneration, field("audioSessionId").getLong(controller))
        assertTrue(pending().isEmpty())
        verify(socket).close()
    }

    private suspend fun awaitModeStatusProbe() {
        withTimeout(5000) {
            while (synchronized(controller) { output.size() == 11 }) delay(20)
        }
    }

    @Test fun anUnchangedModeReportDuringTheProbePreventsReopeningTheChannel() = runBlocking {
        val expiry = startModeChangeOnReadyControlChannel()
        awaitModeStatusProbe()
        receive(0x0d, 4)
        withTimeout(4000) { expiry.join() }
        assertEquals(21, output.size())
        assertEquals("timeout", field("lastSettingStatus").get(controller))
        assertEquals("connected", field("connectionState").get(controller))
        verify(socket, never()).close()
    }

    @Test fun lateModeConfirmationDuringStatusProbeKeepsTheChannel() = runBlocking {
        val expiry = startModeChangeOnReadyControlChannel()
        awaitModeStatusProbe()
        receive(0x0d, 3)
        withTimeout(1000) { expiry.join() }
        assertTrue(expiry.isCancelled)
        assertEquals("confirmed", field("lastSettingStatus").get(controller))
        assertEquals(3, field("currentAnc").getInt(controller))
        verify(socket, never()).close()
    }

    private fun receiveRemainingSettings() {
        for ((key, id) in PodsSettings.identifiers) {
            if (key in PodsSettings.optionalReports) continue // Reproduce older firmware with no sleep report.
            if (manager.getControlCommandStatus(id) != null) continue
            val value = when (key) {
                PodsSettings.NOISE_MODE -> 4
                Key.LISTENING_MODE_BYTE -> 6
                Key.MICROPHONE_MODE, Key.PRESS_SPEED, Key.HOLD_DURATION -> 0
                Key.SWIPE_SPEED -> 1
                Key.CHIME_VOLUME, Key.ADAPTIVE_AUDIO_LEVEL -> 50
                else -> 2
            }
            receive(id.value, value.toByte())
        }
    }

    @Test fun batteryAloneDoesNotStopRecoveryBeforeInformationAndSettingsArrive() {
        coreReports()
        field("earDetectionStateValid").setBoolean(controller, false)
        assertFalse(initialReady())
        field("earDetectionStateValid").set(controller, true)
        assertFalse(initialReady())
        receive(0x0d, 4)
        assertTrue("Missing ancillary settings must not block usable controls", initialReady())
        assertFalse("Continue bounded requests for unread settings", statusSyncComplete())
        receiveRemainingSettings()
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 0x2b, 0))
        assertTrue(initialReady())
        assertTrue(statusSyncComplete())
        controller.currentPodsInfo = null
        assertFalse(initialReady())
    }

    @Test fun cachedPendingBatteryStillRequestsRefreshButSingleEarCanFinishInitialization() {
        coreReports(singleEar = true)
        receive(0x0d, 4)
        receiveRemainingSettings()
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

    @Test fun disconnectAbandonsOffConfirmationAndLateOptionReportsCannotSendAnotherMode() {
        controller.setANCMode(1)
        field("mContext").set(controller, null)
        controller.disconnectedPod(ContextWrapper(null), device)
        receive(0x34, 1)
        assertFalse(field("pendingOffSelection").getBoolean(controller))
        assertTrue(pending().isEmpty())
        assertEquals(22, output.size())
    }

    @Test fun staleOptionReportCannotConfirmOrResendTheActualOffMode() {
        receive(0x0d, 4)
        controller.setANCMode(1)
        receive(0x34, 2)
        assertTrue(pending().containsKey(PodsSettings.NOISE_MODE))
        assertEquals(4, field("currentAnc").getInt(controller))
        assertEquals(22, output.size())
        receive(0x0d, 1)
        receive(0x34, 1)
        assertTrue(pending().isEmpty())
        assertEquals(1, field("currentAnc").getInt(controller))
        assertEquals(22, output.size())
    }

    @Test fun staleOptionEchoDuringTheContinuationDoesNotBlockTheExplicitOffAttempt() {
        receive(0x0d, 4)
        val context = mock(Context::class.java)
        val echoed = AtomicInteger()
        doAnswer {
            // A prior-state echo arrives after the local write but before the mode is queued.
            if (output.size() == 11 && echoed.compareAndSet(0, 1)) receive(0x34, 2)
            null
        }.`when`(context).sendBroadcast(any(Intent::class.java), isNull(), nullable(Bundle::class.java))
        field("mContext").set(controller, context)
        controller.setANCMode(1)
        assertEquals(1, echoed.get())
        assertEquals(22, output.size())
        assertTrue(pending().containsKey(PodsSettings.NOISE_MODE))
        receive(0x0d, 1)
        assertEquals(1, field("currentAnc").getInt(controller))
        assertTrue(pending().isEmpty())
    }

    @Test fun unconfirmedOptionEnableIsReissuedBeforeAnExplicitOffAttempt() {
        receive(0x0d, 4)
        receive(0x34, 2)
        request(Key.ALLOW_OFF_OPTION, 1)
        assertTrue(pending().isEmpty())
        assertTrue(manager.isAwaitingReport(AACPManager.Companion.ControlCommandIdentifiers.ALLOW_OFF_OPTION))
        output.reset()
        controller.setANCMode(1)
        assertEquals(22, output.size())
        assertTrue(pending().containsKey(PodsSettings.NOISE_MODE))
        assertEquals(4, field("currentAnc").getInt(controller))
    }

    @Test fun failedModeWriteAfterTheOptionWriteDoesNotSelectOffAndCanRetry() {
        receive(0x0d, 4)
        val failing = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray) {
                if (bytes.size > 6 && bytes[6] == 0x0d.toByte()) throw IOException("Mode write failed")
                super.write(bytes)
            }
        }
        `when`(socket.outputStream).thenReturn(failing)
        controller.setANCMode(1)
        assertEquals(11, failing.size())
        assertTrue(pending().isEmpty())
        assertFalse(field("pendingOffSelection").getBoolean(controller))
        assertEquals(4, field("currentAnc").getInt(controller))
        assertEquals(PodsSettings.NOISE_MODE, field("lastSettingKey").get(controller))
        assertEquals("write_failed", field("lastSettingStatus").get(controller))
        `when`(socket.outputStream).thenReturn(output)
        controller.setANCMode(1)
        assertEquals(22, output.size())
        receive(0x0d, 1)
        assertEquals(1, field("currentAnc").getInt(controller))
        assertTrue(pending().isEmpty())
    }

    @Test fun microphoneCanBeWrittenBeforeItsFirstReportAndLaterReportsAreAuthoritative() {
        request(Key.MICROPHONE_MODE, 2)
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 1, 2, 0, 0, 0), output.toByteArray())
        assertTrue(pending().isEmpty())
        assertEquals("sent", field("lastSettingStatus").get(controller))
        assertEquals(2.toByte(), manager.getControlCommandStatus(AACPManager.Companion.ControlCommandIdentifiers.MIC_MODE)!!.value[0])
        assertTrue(manager.isAwaitingReport(AACPManager.Companion.ControlCommandIdentifiers.MIC_MODE))
        receive(1, 1)
        assertEquals(1.toByte(), manager.getControlCommandStatus(AACPManager.Companion.ControlCommandIdentifiers.MIC_MODE)!!.value[0])
        assertFalse(manager.isAwaitingReport(AACPManager.Companion.ControlCommandIdentifiers.MIC_MODE))
        receive(1, 2)
        assertTrue(pending().isEmpty())
        assertEquals(2.toByte(), manager.getControlCommandStatus(AACPManager.Companion.ControlCommandIdentifiers.MIC_MODE)!!.value[0])
    }

    @Test fun timingRequestsUseTheirOwnWireRangesAndStillWaitForTheHeadset() {
        for (key in listOf(Key.PRESS_SPEED, Key.HOLD_DURATION, Key.SWIPE_SPEED)) {
            val id = PodsSettings.identifiers.getValue(key)
            val first = if (key == Key.SWIPE_SPEED) 1.toByte() else 0.toByte()
            val previous = (first + 1).toByte()
            receive(id.value, previous)
            request(key, first.toInt())
            assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, id.value, first, 0, 0, 0), output.toByteArray())
            assertTrue(pending().containsKey(key))
            assertEquals(previous, manager.getControlCommandStatus(id)!!.value[0])
            receive(id.value, first)
            assertTrue(pending().isEmpty())
            output.reset()
        }
    }

    @Test fun adaptiveStrengthKeepsTheLatestWriteWithoutAnEchoAndLaterReportsWin() = runBlocking {
        val id = AACPManager.Companion.ControlCommandIdentifiers.AUTO_ANC_STRENGTH
        receive(id.value, 50)
        request(Key.ADAPTIVE_AUDIO_LEVEL, 30)
        request(Key.ADAPTIVE_AUDIO_LEVEL, 70)
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, id.value, 30, 0, 0, 0,
            4, 0, 4, 0, 9, 0, id.value, 70, 0, 0, 0), output.toByteArray())
        assertTrue(pending().isEmpty())
        assertEquals(70.toByte(), manager.getControlCommandStatus(id)!!.value[0])
        assertEquals(50, manager.reportedControlValue(id))
        assertTrue(manager.isAwaitingReport(id))
        // Missing echoes must not turn successful writes into timeout failures.
        delay(3150)
        assertEquals("sent", field("lastSettingStatus").get(controller))
        assertEquals(70.toByte(), manager.getControlCommandStatus(id)!!.value[0])
        receive(id.value, 65)
        assertEquals(65.toByte(), manager.getControlCommandStatus(id)!!.value[0])
        assertEquals(65, manager.reportedControlValue(id))
        assertFalse(manager.isAwaitingReport(id))
        assertEquals("reported", field("lastSettingStatus").get(controller))
    }

    @Test fun failedAdaptiveStrengthWritePreservesTheHeadsetValueAndAllowsRetry() {
        val id = AACPManager.Companion.ControlCommandIdentifiers.AUTO_ANC_STRENGTH
        receive(id.value, 50)
        `when`(socket.isConnected).thenReturn(false)
        request(Key.ADAPTIVE_AUDIO_LEVEL, 70)
        assertEquals("write_failed", field("lastSettingStatus").get(controller))
        assertEquals(50.toByte(), manager.getControlCommandStatus(id)!!.value[0])
        assertFalse(manager.isAwaitingReport(id))
        assertTrue(pending().isEmpty())
        assertEquals(0, output.size())
        `when`(socket.isConnected).thenReturn(true)
        request(Key.ADAPTIVE_AUDIO_LEVEL, 70)
        assertEquals("sent", field("lastSettingStatus").get(controller))
        assertEquals(70.toByte(), manager.getControlCommandStatus(id)!!.value[0])
        assertTrue(manager.isAwaitingReport(id))
    }

    @Test fun chimeVolumePreservesTheSecondByteWithoutWaitingForAnEcho() {
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 9, 0, 0x1f, 80, 67, 0, 0))
        request(Key.CHIME_VOLUME, 35)
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x1f, 35, 67, 0, 0), output.toByteArray())
        assertTrue(pending().isEmpty())
        assertEquals(35.toByte(), manager.getControlCommandStatus(AACPManager.Companion.ControlCommandIdentifiers.CHIME_VOLUME)!!.value[0])
        assertEquals("sent", field("lastSettingStatus").get(controller))
        receive(0x1f, 35)
        assertTrue(pending().isEmpty())
    }

    @Test fun swipeIntervalRejectsZeroAndAcceptsTheLongestFirmwareValueThree() {
        request(Key.SWIPE_SPEED, 0)
        assertEquals(0, output.size())
        assertEquals("invalid", field("lastSettingStatus").get(controller))
        request(Key.SWIPE_SPEED, 3)
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x23, 3, 0, 0, 0), output.toByteArray())
        assertTrue(pending().containsKey(Key.SWIPE_SPEED))
        receive(0x23, 3)
        assertTrue(pending().isEmpty())
        assertEquals("confirmed", field("lastSettingStatus").get(controller))
    }

    @Test fun chimeVolumeRetainsZeroConfigurationWithoutReplacingItWithADefault() {
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 9, 0, 0x1f, 80, 0, 0, 0))
        request(Key.CHIME_VOLUME, 35)
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x1f, 35, 0, 0, 0), output.toByteArray())
    }

    @Test fun issueControlsPublishSuccessfulWritesButNeverPretendTheyWereReported() {
        for ((key, value) in listOf(Key.LISTENING_MODE_BYTE to 6, Key.ADJUST_VOLUME_BY_SWIPER to 1,
            Key.SINGLE_POD_ANC to 1)) {
            val id = PodsSettings.identifiers.getValue(key)
            request(key, value)
            assertFalse(pending().containsKey(key))
            assertEquals("sent", field("lastSettingStatus").get(controller))
            assertTrue(manager.isAwaitingReport(id))
            assertEquals(value.toByte(), manager.getControlCommandStatus(id)!!.value[0])
            receive(id.value, value.toByte())
            assertFalse(manager.isAwaitingReport(id))
            `when`(socket.isConnected).thenReturn(false)
            val other = if (key == Key.LISTENING_MODE_BYTE) 14 else 2
            request(key, other)
            assertEquals("write_failed", field("lastSettingStatus").get(controller))
            assertEquals(value.toByte(), manager.getControlCommandStatus(id)!!.value[0])
            `when`(socket.isConnected).thenReturn(true)
        }
    }

    private fun singlePodAncSession(model: String = "A3439", enabled: Boolean = true, mode: Byte = 2) {
        (field("singlePodAnc").get(controller) as SinglePodAnc).reset()
        controller.currentPodsInfo = controller.currentPodsInfo!!.copy(modelNumber = model)
        field("earDetection").setBoolean(controller, false)
        field("switchedToSpeaker").setBoolean(controller, false)
        AirPodsNotifications.BatteryNotification.setBattery(
            byteArrayOf(4, 0, 4, 0, 4, 0, 2, 4, 1, 80, 2, 0, 2, 1, 70, 2, 0))
        controller.currentBatteryParams = BatteryParams(
            PodBatteryParams(80, isConnected = true), PodBatteryParams(70, isConnected = true), PodBatteryParams())
        field("batteryStateValid").setBoolean(controller, true)
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 6, 0, 0, 0))
        receive(0x1b, if (enabled) 1 else 2)
        receive(0x0d, mode)
        output.reset()
    }

    @Test fun singlePodAncRetainsAnActiveNoiseModeWhenEitherEarIsRemoved() {
        for (wear in listOf(byteArrayOf(1, 0), byteArrayOf(0, 1))) {
            singlePodAncSession()
            manager.receivePacket(byteArrayOf(4, 0, 4, 0, 6, 0) + wear)
            assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x1b, 1, 0, 0, 0,
                4, 0, 4, 0, 9, 0, 0x0d, 2, 0, 0, 0), output.toByteArray())
            assertTrue(pending().containsKey(PodsSettings.NOISE_MODE))
            assertEquals("sent", field("lastSettingStatus").get(controller))
            receive(0x0d, 2)
            assertTrue(pending().isEmpty())
            assertEquals("confirmed", field("lastSettingStatus").get(controller))
        }
    }

    @Test fun singlePodAncHandlesModeFallbackBeforeOrAfterTheWearReportWithoutALoop() {
        singlePodAncSession()
        receive(0x0d, 3)
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 6, 0, 1, 0))
        assertEquals(22, output.size())
        receive(0x0d, 2)
        output.reset()
        // A physical mode change after successful restoration remains authoritative.
        receive(0x0d, 3)
        assertEquals(0, output.size())

        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 6, 0, 0, 0))
        receive(0x0d, 2)
        output.reset()
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 6, 0, 0, 1))
        receive(0x0d, 3)
        assertEquals(44, output.size())
        receive(0x0d, 3)
        assertEquals("Repeated fallback reports must not keep writing", 44, output.size())
        receive(0x0d, 2)
        assertTrue(pending().isEmpty())
    }

    @Test fun singlePodAncDoesNotTakeOverDisabledOtherModesOrOtherHeadsets() {
        for ((model, enabled, mode) in listOf(
            Triple("A3439", false, 2.toByte()), Triple("A3439", true, 3.toByte()),
            Triple("A3439", true, 4.toByte()), Triple("A3049", true, 2.toByte()))) {
            singlePodAncSession(model, enabled, mode)
            manager.receivePacket(byteArrayOf(4, 0, 4, 0, 6, 0, 1, 0))
            assertEquals("$model, enabled=$enabled, mode=$mode", 0, output.size())
        }
    }

    @Test fun singlePodAncRestorationWriteFailureIsNotReportedAsSuccess() {
        singlePodAncSession()
        `when`(socket.isConnected).thenReturn(false)
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 6, 0, 1, 0))
        assertEquals(0, output.size())
        assertEquals("write_failed", field("lastSettingStatus").get(controller))
        assertEquals(Key.SINGLE_POD_ANC, field("lastSettingKey").get(controller))
        assertTrue(pending().isEmpty())
    }

    @Test fun singlePodAncRespectsExplicitModeChangesAndDisablingDuringRestoration() {
        singlePodAncSession()
        controller.setANCMode(3)
        receive(0x0d, 3)
        output.reset()
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 6, 0, 1, 0))
        assertEquals(0, output.size())

        singlePodAncSession()
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 6, 0, 1, 0))
        assertTrue(pending().containsKey(PodsSettings.NOISE_MODE))
        request(Key.SINGLE_POD_ANC, 2)
        assertFalse(pending().containsKey(PodsSettings.NOISE_MODE))
        output.reset()
        receive(0x0d, 3)
        assertEquals(0, output.size())
    }

    @Test fun removingBothEarsCancelsTheSinglePodAncConfirmationWait() {
        singlePodAncSession()
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 6, 0, 1, 0))
        assertTrue(pending().containsKey(PodsSettings.NOISE_MODE))
        output.reset()
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 6, 0, 1, 1))
        assertFalse(pending().containsKey(PodsSettings.NOISE_MODE))
        receive(0x0d, 3)
        assertEquals(0, output.size())
    }

    @Test fun failedSinglePodAncRetryCancelsThePreviousConfirmationWait() {
        singlePodAncSession()
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 6, 0, 1, 0))
        assertTrue(pending().containsKey(PodsSettings.NOISE_MODE))
        `when`(socket.isConnected).thenReturn(false)
        output.reset()
        receive(0x0d, 3)
        assertEquals(0, output.size())
        assertTrue(pending().isEmpty())
        assertEquals("write_failed", field("lastSettingStatus").get(controller))
        assertEquals(Key.SINGLE_POD_ANC, field("lastSettingKey").get(controller))
    }

    @Test fun explicitModeSelectionCanReplaceAnAutomaticAncConfirmationWait() {
        singlePodAncSession()
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 6, 0, 1, 0))
        assertTrue(pending().containsKey(PodsSettings.NOISE_MODE))
        output.reset()
        controller.setANCMode(3)
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x0d, 3, 0, 0, 0), output.toByteArray())
        receive(0x0d, 3)
        assertTrue(pending().isEmpty())
        assertEquals("confirmed", field("lastSettingStatus").get(controller))
    }

    @Test fun conversationDoesNotGetOverriddenByAnAutomaticSingleAncRetry() {
        singlePodAncSession()
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 6, 0, 1, 0))
        field("conversationPhoneVolume").setBoolean(controller, false)
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 0x4b, 0, 2, 0, 0, 1))
        output.reset()
        receive(0x0d, 3)
        assertEquals(0, output.size())
        assertFalse(pending().containsKey(PodsSettings.NOISE_MODE))
    }

    @Test fun anOngoingConversationCannotArmSingleAncWhenAnEarIsRemoved() {
        singlePodAncSession()
        field("conversationPhoneVolume").setBoolean(controller, false)
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 0x4b, 0, 2, 0, 0, 1))
        output.reset()
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 6, 0, 1, 0))
        receive(0x0d, 3)
        assertEquals(0, output.size())
        assertTrue(pending().isEmpty())
    }

    @Test fun aReportedLongPressCancelsTheAutomaticSingleAncRequest() {
        singlePodAncSession()
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 6, 0, 1, 0))
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 0x19, 0, 8, 1))
        output.reset()
        receive(0x0d, 3)
        assertEquals(0, output.size())
        assertTrue(pending().isEmpty())
    }

    @Test fun disablingConversationAwarenessClearsItsAncRecoverySuppression() {
        singlePodAncSession()
        field("conversationPhoneVolume").setBoolean(controller, false)
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 0x4b, 0, 2, 0, 0, 1))
        request(Key.CONVERSATION_AWARENESS, 2)
        assertFalse(field("conversationAwarenessActive").getBoolean(controller))
        receive(0x0d, 2)
        output.reset()
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 6, 0, 1, 0))
        assertEquals(22, output.size())
        assertTrue(pending().containsKey(PodsSettings.NOISE_MODE))
    }

    @Test fun diagnosticPermissionKeepsTheReportedValueSeparateFromTheLocalRequest() {
        receive(0x1b, 2)
        request(Key.SINGLE_POD_ANC, 1)
        mockConstruction(Bundle::class.java).use {
            val snapshot = type.getDeclaredMethod("diagnosticsSnapshot").apply { isAccessible = true }.invoke(controller) as Bundle
            verify(snapshot).putInt("single_anc_reported", 2)
            verify(snapshot).putInt("single_anc_requested", 1)
            verify(snapshot).putStringArrayList("unconfirmed_settings", arrayListOf(Key.SINGLE_POD_ANC))
        }
    }

    @Test fun failedChimeWriteDoesNotReplaceTheVolumeOrItsConfiguration() {
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 9, 0, 0x1f, 80, 67, 0, 0))
        `when`(socket.isConnected).thenReturn(false)
        request(Key.CHIME_VOLUME, 35)
        assertArrayEquals(byteArrayOf(80, 67, 0, 0), manager.getControlCommandStatus(
            AACPManager.Companion.ControlCommandIdentifiers.CHIME_VOLUME)!!.value)
        assertEquals("write_failed", field("lastSettingStatus").get(controller))
    }

    @Test fun nativeStemSetupIsNotSentToModelsWithoutTheCapability() {
        val callback = field("packetCallback").get(controller) as AACPManager.PacketCallback
        callback.onDeviceInformationReceived(controller.currentPodsInfo!!.copy(modelNumber = "A2084"))
        assertEquals(0, output.size())
        callback.onDeviceInformationReceived(controller.currentPodsInfo!!.copy(modelNumber = "A3439"))
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x39, 0, 0, 0, 0), output.toByteArray())
    }

    @Test fun manualRecoveryDiscardsASilentControlSocketButKeepsTheAudioDeviceSession() {
        field("socket").set(controller, socket)
        val generation = field("sessionId").getLong(controller)
        val context = field("mContext").get(controller)
        val intent = mock(Intent::class.java)
        `when`(intent.action).thenReturn(HyperPodsAction.ACTION_PODS_STATUS_RETRY)
        controller.handleUIEvent(intent)
        assertEquals(0, output.size())
        assertEquals(generation + 1, field("sessionId").getLong(controller))
        assertSame(context, field("mContext").get(controller))
        assertSame(device, controller.mDevice)
        assertNull(field("aacpManager").get(controller))
        verify(socket, timeout(1000)).close()
        verify(socket, never()).connect()
        (field("connectionJob").get(controller) as Job?)?.cancel()
    }

    @Test fun malformedDeviceInformationCannotEraseTheCurrentModelOrAdvanceItsTimestamp() {
        val previous = controller.currentPodsInfo
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 0x1d, 0, 0, 65, 0))
        assertSame(previous, controller.currentPodsInfo)
        assertEquals(0L, manager.informationReceivedAt)
        assertEquals(0, manager.receivedPacketCount)
    }

    private fun finishInitialStatus(generation: Long = field("sessionId").getLong(controller)) {
        type.getDeclaredMethod("finishInitialStatus", AACPManager::class.java, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(controller, manager, generation)
    }

    @Test fun missingCoreStatusAtSyncTimeoutClosesOnlyTheControlSocket() {
        field("socket").set(controller, socket)
        field("connectionState").set(controller, "connected")
        finishInitialStatus()
        verify(socket).close()
        assertEquals("status_timeout", field("lastStatusFailure").get(controller))
        assertEquals("failed", field("connectionState").get(controller))
        assertNotNull(field("mContext").get(controller))
        assertSame(device, controller.mDevice)
    }

    @Test fun missingSettingReportAloneDoesNotCloseAWorkingCoreSession() {
        field("socket").set(controller, socket)
        coreReports()
        finishInitialStatus()
        verify(socket, never()).close()
        assertEquals("status_timeout", field("lastStatusFailure").get(controller))
    }

    @Test fun missingAncillaryReportsDoNotFailAnOtherwiseReadySession() {
        field("socket").set(controller, socket)
        coreReports()
        receive(0x0d, 4)
        finishInitialStatus()
        assertTrue(initialReady())
        assertFalse(statusSyncComplete())
        assertEquals("", field("lastStatusFailure").get(controller))
        verify(socket, never()).close()
    }

    @Test fun pendingSecondaryBatteryStillNeedsControlRecoveryWhenTheOtherEarHasPower() {
        field("mContext").set(controller, null)
        field("socket").set(controller, socket)
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 4, 0, 2, 4, 1, 80, 2, 0, 2, 1, 0, 3, 0))
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 6, 0, 0, 0))
        assertTrue(field("batteryStateValid").getBoolean(controller))
        assertEquals(-1, controller.currentBatteryParams.right!!.battery)
        assertFalse(initialReady())
        finishInitialStatus()
        verify(socket).close()
        assertEquals("status_timeout", field("lastStatusFailure").get(controller))
    }

    @Test fun aWornEarWithoutAnyBatteryRecordMustNotFinishInitialization() {
        field("mContext").set(controller, null)
        field("socket").set(controller, socket)
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 4, 0, 1, 4, 1, 80, 2, 0))
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 6, 0, 0, 0))
        receiveRemainingSettings()
        assertFalse(initialReady())
        finishInitialStatus()
        verify(socket).close()
    }

    @Test fun singleEarWithDisconnectedInCasePeerDoesNotReconnectForMissingCaseOrPeerBattery() {
        field("mContext").set(controller, null)
        field("socket").set(controller, socket)
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 4, 0, 1, 4, 1, 80, 2, 0))
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 6, 0, 0, 2))
        receiveRemainingSettings()
        assertTrue(initialReady())
        finishInitialStatus()
        verify(socket, never()).close()
        assertEquals("", field("lastStatusFailure").get(controller))
    }

    @Test fun pendingBatteryOfInCasePeerDoesNotCloseAWorkingSingleEarSession() {
        field("mContext").set(controller, null)
        field("socket").set(controller, socket)
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 4, 0, 2, 4, 1, 80, 2, 0, 2, 1, 0, 3, 0))
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 6, 0, 0, 2))
        receiveRemainingSettings()
        assertTrue(initialReady())
        finishInitialStatus()
        verify(socket, never()).close()
        assertEquals("", field("lastStatusFailure").get(controller))
    }

    @Test fun obsoleteTimeoutCannotCloseANewerSession() {
        field("socket").set(controller, socket)
        finishInitialStatus(field("sessionId").getLong(controller) - 1)
        verify(socket, never()).close()
        assertEquals("", field("lastStatusFailure").get(controller))
    }

    @Test fun reopeningUiAfterControlFailureStillPublishesThePhysicalDeviceSession() {
        field("aacpManager").set(controller, null)
        val actions = mutableListOf<Any?>()
        mockConstruction(Intent::class.java) { _, construction -> actions.add(construction.arguments().firstOrNull()) }.use {
            val init = mock(Intent::class.java)
            `when`(init.action).thenReturn(HyperPodsAction.ACTION_PODS_UI_INIT)
            controller.handleUIEvent(init)
        }
        assertTrue(actions.contains(HyperPodsAction.ACTION_PODS_CONNECTED))
        assertEquals(0, output.size())
    }

    @Test fun listeningTogglesUpdateWithoutRepliesAndCanBeToggledAgainImmediately() {
        for (key in listOf(Key.PERSONLIZED_VOLUME, Key.CONVERSATION_AWARENESS)) {
            val id = PodsSettings.identifiers.getValue(key)
            receive(id.value, 2)
            request(key, 1)
            assertTrue(pending().isEmpty())
            assertEquals(1.toByte(), manager.getControlCommandStatus(id)!!.value[0])
            assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, id.value, 1, 0, 0, 0), output.toByteArray())
            output.reset()
            request(key, 2)
            assertTrue(pending().isEmpty())
            assertEquals(2.toByte(), manager.getControlCommandStatus(id)!!.value[0])
            output.reset()
            // A later headset report replaces the locally requested state.
            receive(id.value, 1)
            assertEquals(1.toByte(), manager.getControlCommandStatus(id)!!.value[0])
        }
    }

    @Test fun listeningToggleWriteFailuresPreserveThePreviousState() {
        `when`(socket.isConnected).thenReturn(false)
        for (key in listOf(Key.PERSONLIZED_VOLUME, Key.CONVERSATION_AWARENESS)) {
            val id = PodsSettings.identifiers.getValue(key)
            receive(id.value, 2)
            request(key, 1)
            assertTrue(pending().isEmpty())
            assertEquals(2.toByte(), manager.getControlCommandStatus(id)!!.value[0])
        }
        assertEquals(0, output.size())
    }

    @Test fun matchingAuthoritativeReportClearsWriteFailureWithoutAConfirmationToast() {
        receive(0x26, 2)
        `when`(socket.isConnected).thenReturn(false)
        request(Key.PERSONLIZED_VOLUME, 1)
        assertEquals("write_failed", field("lastSettingStatus").get(controller))
        receive(0x26, 2)
        assertEquals("write_failed", field("lastSettingStatus").get(controller))
        receive(0x26, 1)
        assertEquals("reported", field("lastSettingStatus").get(controller))
    }

    @Test fun settingFeedbackDistinguishesSentReportedConfirmedAndWriteFailure() {
        request(Key.CONVERSATION_AWARENESS, 1)
        assertEquals("sent", field("lastSettingStatus").get(controller))
        receive(0x28, 2)
        assertEquals("reported", field("lastSettingStatus").get(controller))
        request(Key.PRESS_SPEED, 2)
        assertEquals("sent", field("lastSettingStatus").get(controller))
        receive(0x17, 2)
        assertEquals("confirmed", field("lastSettingStatus").get(controller))
        `when`(socket.isConnected).thenReturn(false)
        request(Key.PERSONLIZED_VOLUME, 1)
        assertEquals("write_failed", field("lastSettingStatus").get(controller))
    }

    @Test fun lowBatteryPolicyChangesStayOnThePhoneAndRejectInvalidThresholds() {
        val policy = mock(Intent::class.java)
        `when`(policy.action).thenReturn(HyperPodsAction.ACTION_PODS_SETTINGS_CHANGED)
        `when`(policy.getStringExtra("key")).thenReturn(Key.LOW_BATTERY_EARS)
        val config = LowBatterySettings(earsEnabled = false, caseThreshold = 30)
        `when`(policy.getParcelableExtra("lowBatterySettings", LowBatterySettings::class.java)).thenReturn(config)
        controller.handleUIEvent(policy)
        assertEquals(config, field("lowBatterySettings").get(controller))
        `when`(policy.getParcelableExtra("lowBatterySettings", LowBatterySettings::class.java))
            .thenReturn(config.copy(earsThreshold = 101))
        controller.handleUIEvent(policy)
        assertEquals(config, field("lowBatterySettings").get(controller))
        assertEquals(0, output.size())
        verify(socket, never()).connect()
    }

    @Test fun notificationUpdatesCarryOnlyTheComponentsInTheActualBatteryPacket() {
        val notifications = mutableListOf<Intent>()
        mockConstruction(Intent::class.java) { intent, construction ->
            if (construction.arguments().firstOrNull() == HyperPodsAction.ACTION_UPDATE_NOTIFICATION) notifications.add(intent)
        }.use {
            val left = byteArrayOf(4, 0, 4, 0, 4, 0, 1, 4, 1, 18, 2, 0)
            val right = byteArrayOf(4, 0, 4, 0, 4, 0, 1, 2, 1, 19, 2, 0)
            manager.receivePacket(left)
            manager.receivePacket(right)
            val leftFresh = org.mockito.ArgumentCaptor.forClass(IntArray::class.java)
            val rightFresh = org.mockito.ArgumentCaptor.forClass(IntArray::class.java)
            verify(notifications[0]).putExtra(eq("freshComponents"), leftFresh.capture())
            verify(notifications[1]).putExtra(eq("freshComponents"), rightFresh.capture())
            assertArrayEquals(intArrayOf(4), leftFresh.value)
            assertArrayEquals(intArrayOf(2), rightFresh.value)
            // Left is still available in the display snapshot, but cannot trigger on a right-only report.
            val decisions = LowBatteryReminder.evaluate(controller.currentBatteryParams, rightFresh.value, LowBatterySettings()) { 0 }
            assertEquals(listOf(2), decisions.filter { decision -> decision.alert }.map { decision -> decision.component })
        }
    }

    private fun selectedHeadset(): MediaRouter2.RoutingController {
        val router = mock(MediaRouter2::class.java)
        val routing = mock(MediaRouter2.RoutingController::class.java)
        val headset = mock(MediaRoute2Info::class.java)
        `when`(device.name).thenReturn("Test headset")
        `when`(headset.id).thenReturn("headset")
        `when`(headset.name).thenReturn("Test headset")
        `when`(headset.type).thenReturn(MediaRoute2Info.TYPE_BLUETOOTH_A2DP)
        `when`(router.systemController).thenReturn(routing)
        `when`(routing.selectedRoutes).thenReturn(listOf(headset))
        field("mediaRouter").set(controller, router)
        return routing
    }

    private class ConversationPlayer {
        val context = mock(Context::class.java)
        val audio = mock(AudioManager::class.java)
        val sessions = mock(MediaSessionManager::class.java)
        val player = mock(MediaController::class.java)
        val controls = mock(MediaController.TransportControls::class.java)
        val token = mock(MediaSession.Token::class.java)
        var playing = true
        var state = PlaybackState.STATE_PLAYING
        var volume = 10
        var callback: MediaController.Callback? = null

        init {
            `when`(context.getSystemService(AudioManager::class.java)).thenReturn(audio)
            `when`(context.getSystemService(MediaSessionManager::class.java)).thenReturn(sessions)
            `when`(audio.isMusicActive).thenAnswer { playing }
            `when`(audio.mode).thenReturn(AudioManager.MODE_NORMAL)
            `when`(audio.getStreamVolume(AudioManager.STREAM_MUSIC)).thenAnswer { volume }
            doAnswer { volume = it.getArgument(1); null }.`when`(audio).setStreamVolume(eq(AudioManager.STREAM_MUSIC), anyInt(), eq(0))
            `when`(sessions.getActiveSessions(null)).thenReturn(listOf(player))
            `when`(sessions.mediaKeyEventSession).thenReturn(token)
            `when`(player.sessionToken).thenReturn(token)
            `when`(player.transportControls).thenReturn(controls)
            `when`(player.playbackState).thenAnswer { playbackState() }
            val info = mock(MediaController.PlaybackInfo::class.java)
            `when`(info.playbackType).thenReturn(MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL)
            `when`(player.playbackInfo).thenReturn(info)
            doAnswer { callback = it.getArgument(0); null }.`when`(player).registerCallback(any(), any())
            doAnswer {
                playing = false
                state = PlaybackState.STATE_PAUSED
                callback?.onPlaybackStateChanged(playbackState())
                null
            }.`when`(controls).pause()
        }

        fun playbackState() = mock(PlaybackState::class.java).also { `when`(it.state).thenReturn(state) }
    }

    private fun conversationPlayer(pause: Boolean = true, duck: Boolean = true): ConversationPlayer {
        selectedHeadset()
        val player = ConversationPlayer()
        field("mContext").set(controller, player.context)
        field("aacpManager").set(controller, manager)
        field("earDetectionStateValid").setBoolean(controller, true)
        controller.currentEarDetectionParams = EarDetectionParams(0, 0)
        val policy = mock(Intent::class.java)
        `when`(policy.action).thenReturn(HyperPodsAction.ACTION_APP_SETTINGS_CHANGED)
        `when`(policy.getStringExtra(AppPreferences.EXTRA)).thenReturn(
            AppPreferences.Snapshot(conversationVolume = duck, conversationPauseMedia = pause).encode())
        controller.handleUIEvent(policy)
        receive(0x28, 1)
        output.reset()
        return player
    }

    private fun conversationStatus(status: Int) = manager.receivePacket(
        byteArrayOf(4, 0, 4, 0, 0x4b, 0, 2, 0, 0, status.toByte()))

    @Test fun conversationPauseTakesPriorityOverDuckingAndDoesNotSendListeningModeCommands() = synchronized(controller) {
        val player = conversationPlayer()
        conversationStatus(1)
        conversationStatus(2)
        conversationStatus(3)
        assertEquals(10, player.volume)
        verify(player.controls, times(1)).pause()
        assertEquals(0, output.size())
        verify(socket, never()).connect()
    }

    @Test fun allConversationEndReportsResumeOnlyOnceAndWorkWithoutPhoneDucking() = synchronized(controller) {
        for (end in 6..9) {
            val player = conversationPlayer(duck = false)
            conversationStatus(1)
            conversationStatus(end)
            conversationStatus(end)
            verify(player.controls, times(1)).pause()
            verify(player.controls, times(1)).play()
            verify(player.player).unregisterCallback(any())
            assertEquals(10, player.volume)
            assertNull(field("conversationMedia").get(controller))
        }
    }

    @Test fun conversationResumeWaitsForTheAudioPipelineToCatchUpWithPause() = runBlocking {
        val (player, pendingResume) = synchronized(controller) {
            val player = conversationPlayer()
            conversationStatus(1)
            player.playing = true // Session already acknowledged Pause; AudioFlinger has not caught up.
            conversationStatus(6)
            verify(player.controls, never()).play()
            player to requireNotNull(field("conversationRestoreJob").get(controller) as Job?)
        }
        delay(80)
        synchronized(controller) { player.playing = false }
        withTimeout(1000) { pendingResume.join() }
        verify(player.controls).play()
    }

    @Test fun aNewUtteranceBeforeDelayedResumeKeepsTheOriginalPauseOwnership() = synchronized(controller) {
        val player = conversationPlayer()
        conversationStatus(1)
        player.playing = true
        conversationStatus(6)
        val pendingResume = requireNotNull(field("conversationRestoreJob").get(controller) as Job?)
        conversationStatus(1)
        assertTrue(pendingResume.isCancelled)
        player.playing = false
        conversationStatus(6)
        verify(player.controls, times(1)).pause()
        verify(player.controls, times(1)).play()
    }

    @Test fun disabledPausePreservesTheExistingVolumeChoiceAndNeverPausesMedia() = synchronized(controller) {
        for (duck in listOf(false, true)) {
            val player = conversationPlayer(pause = false, duck = duck)
            conversationStatus(1)
            assertEquals(if (duck) 2 else 10, player.volume)
            conversationStatus(6)
            type.getDeclaredMethod("resetConversationVolume").apply { isAccessible = true }.invoke(controller)
            assertEquals(10, player.volume)
            verifyNoInteractions(player.controls)
        }
    }

    @Test fun conversationPauseRequiresEnabledHeadsetAwarenessAndASelectedWornHeadset() = synchronized(controller) {
        val player = conversationPlayer()
        receive(0x28, 2)
        conversationStatus(1)
        conversationStatus(6)
        receive(0x28, 1)
        field("earDetectionStateValid").setBoolean(controller, false)
        conversationStatus(1)
        conversationStatus(6)
        field("earDetectionStateValid").setBoolean(controller, true)
        val routing = selectedHeadset()
        `when`(routing.selectedRoutes).thenReturn(emptyList())
        conversationStatus(1)
        conversationStatus(6)
        verifyNoInteractions(player.controls)
    }

    @Test fun anAlreadyPausedPlayerOrManualPlaybackDuringSpeechCannotBeAutoStarted() = synchronized(controller) {
        val player = conversationPlayer()
        player.playing = false
        player.state = PlaybackState.STATE_PAUSED
        conversationStatus(1)
        player.playing = true // Explicit user playback during this conversation wins.
        player.state = PlaybackState.STATE_PLAYING
        conversationStatus(2)
        conversationStatus(6)
        verifyNoInteractions(player.controls)
    }

    @Test fun manuallyResumingAndPausingAgainRevokesConversationResumeOwnership() = synchronized(controller) {
        val player = conversationPlayer()
        conversationStatus(1)
        val callback = requireNotNull(player.callback)
        player.state = PlaybackState.STATE_PLAYING
        callback.onPlaybackStateChanged(player.playbackState())
        player.state = PlaybackState.STATE_PAUSED
        callback.onPlaybackStateChanged(player.playbackState())
        conversationStatus(6)
        verify(player.controls).pause()
        verify(player.controls, never()).play()
    }

    @Test fun changingPlayersOrStartingACallPreventsConversationResume() = synchronized(controller) {
        for (call in listOf(false, true)) {
            val player = conversationPlayer()
            conversationStatus(1)
            if (call) `when`(player.audio.mode).thenReturn(AudioManager.MODE_IN_COMMUNICATION)
            else `when`(player.sessions.mediaKeyEventSession).thenReturn(mock(MediaSession.Token::class.java))
            conversationStatus(6)
            verify(player.controls).pause()
            verify(player.controls, never()).play()
        }
    }

    @Test fun anIncomingCallRevokesResumeEvenIfTheCallEndsBeforeTheConversationReport() = synchronized(controller) {
        val player = conversationPlayer()
        val telephony = mock(TelephonyManager::class.java)
        `when`(player.context.getSystemService(TelephonyManager::class.java)).thenReturn(telephony)
        `when`(telephony.callState).thenReturn(TelephonyManager.CALL_STATE_IDLE)
        conversationStatus(1)
        `when`(telephony.callState).thenReturn(TelephonyManager.CALL_STATE_RINGING)
        val call = mock(Intent::class.java)
        `when`(call.action).thenReturn(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        (field("broadcastReceiver").get(controller) as BroadcastReceiver).onReceive(player.context, call)
        `when`(telephony.callState).thenReturn(TelephonyManager.CALL_STATE_IDLE)
        conversationStatus(6)
        verify(player.controls).pause()
        verify(player.controls, never()).play()
        assertNull(field("conversationMedia").get(controller))
    }

    @Test fun destroyedOrUnconfirmedOrRemotePlayersAreNeverAutoStarted() = synchronized(controller) {
        for (case in listOf("destroyed", "unconfirmed", "remote")) {
            val player = conversationPlayer()
            if (case == "unconfirmed") doNothing().`when`(player.controls).pause()
            if (case == "remote") `when`(player.player.playbackInfo.playbackType)
                .thenReturn(MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE)
            conversationStatus(1)
            if (case == "destroyed") requireNotNull(player.callback).onSessionDestroyed()
            conversationStatus(6)
            verify(player.controls, never()).play()
            if (case == "remote") verify(player.controls, never()).pause()
        }
    }

    @Test fun anUnreadableSessionCanPauseButCannotTriggerAGlobalPlay() = synchronized(controller) {
        val player = conversationPlayer()
        moe.chenxy.hyperpods.utils.MediaControl.mContext = player.context
        `when`(player.sessions.getActiveSessions(null)).thenThrow(SecurityException("Synthetic denied session"))
        conversationStatus(1)
        conversationStatus(2)
        conversationStatus(6)
        verify(player.audio, times(2)).dispatchMediaKeyEvent(any()) // One Pause key down/up only.
        verifyNoInteractions(player.controls)
    }

    @Test fun lostOutputOrRemovedEarCannotResumeEvenAfterTheOutputOrWearReturns() = synchronized(controller) {
        for (removeEar in listOf(false, true)) {
            val player = conversationPlayer()
            conversationStatus(1)
            if (removeEar) {
                val wear = type.getDeclaredMethod("handleInEarStatusChanged", List::class.java).apply { isAccessible = true }
                wear.invoke(controller, listOf(1.toByte(), 0.toByte()))
                wear.invoke(controller, listOf(0.toByte(), 0.toByte()))
            } else {
                val router = field("mediaRouter").get(controller) as MediaRouter2
                val routing = router.systemController
                val selected = routing.selectedRoutes
                `when`(routing.selectedRoutes).thenReturn(emptyList())
                conversationStatus(2)
                `when`(routing.selectedRoutes).thenReturn(selected)
            }
            conversationStatus(6)
            verify(player.controls).pause()
            verify(player.controls, never()).play()
        }
    }

    @Test fun resetAndDisconnectReleaseThePausedPlayerWithoutPlayingIt() = synchronized(controller) {
        for (disconnect in listOf(false, true)) {
            val player = conversationPlayer()
            conversationStatus(1)
            if (disconnect) controller.disconnectedPod(player.context, device)
            else type.getDeclaredMethod("resetControlState").apply { isAccessible = true }.invoke(controller)
            conversationStatus(6)
            verify(player.controls, never()).play()
            verify(player.player).unregisterCallback(any())
            assertNull(field("conversationMedia").get(controller))
        }
    }

    @Test fun switchingPauseModeDuringSpeechRestoresTheRetainedVolumeChoice() = synchronized(controller) {
        val player = conversationPlayer(pause = false)
        conversationStatus(1)
        assertEquals(2, player.volume)
        val update = type.getDeclaredMethod("updateConversationPauseMedia", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        update.invoke(controller, true)
        assertEquals(10, player.volume)
        verify(player.controls).pause()
        update.invoke(controller, false)
        verify(player.controls).play()
        assertTrue(field("conversationPhoneVolume").getBoolean(controller))
        assertEquals(1.toByte(), manager.getControlCommandStatus(AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG)!!.value[0])
        assertEquals(0, output.size())
    }

    @Test fun conversationVolumeNeverDucksOrRestoresOntoAnotherOutput() = synchronized(controller) {
        val context = mock(Context::class.java)
        val audio = mock(AudioManager::class.java)
        var volume = 10
        `when`(context.getSystemService(AudioManager::class.java)).thenReturn(audio)
        `when`(audio.isMusicActive).thenReturn(true)
        `when`(audio.getStreamVolume(AudioManager.STREAM_MUSIC)).thenAnswer { volume }
        doAnswer { volume = it.getArgument(1); null }.`when`(audio).setStreamVolume(eq(AudioManager.STREAM_MUSIC), anyInt(), eq(0))
        field("mContext").set(controller, context)
        val routing = selectedHeadset()
        val headset = routing.selectedRoutes
        receive(0x28, 1)
        val speech = byteArrayOf(4, 0, 4, 0, 0x4b, 0, 2, 0, 0, 1)
        val end = speech.copyOf().apply { this[9] = 6 }
        for (routeType in listOf(MediaRoute2Info.TYPE_BUILTIN_SPEAKER, MediaRoute2Info.TYPE_BLUETOOTH_A2DP)) {
            val other = mock(MediaRoute2Info::class.java)
            `when`(other.id).thenReturn("other")
            `when`(other.name).thenReturn("Other output")
            `when`(other.type).thenReturn(routeType)
            `when`(routing.selectedRoutes).thenReturn(listOf(other))
            manager.receivePacket(speech)
            assertEquals(10, volume)
            `when`(routing.selectedRoutes).thenReturn(headset)
            manager.receivePacket(speech)
            assertEquals(2, volume)
            `when`(routing.selectedRoutes).thenReturn(listOf(other))
            volume = 2 // A different output can have exactly the same volume as the ducked headset.
            manager.receivePacket(end)
            type.getDeclaredMethod("resetConversationVolume").apply { isAccessible = true }.invoke(controller)
            assertEquals(2, volume)
            `when`(routing.selectedRoutes).thenReturn(headset)
            volume = 10
        }
        manager.receivePacket(speech)
        assertEquals(2, volume)
        manager.receivePacket(end)
        type.getDeclaredMethod("resetConversationVolume").apply { isAccessible = true }.invoke(controller)
        assertEquals(10, volume)
    }

    @Test fun disablingPhoneDuckingRestoresVolumeAndDoesNotDisableHeadsetConversationAwareness() = synchronized(controller) {
        selectedHeadset()
        val context = mock(Context::class.java)
        val audio = mock(AudioManager::class.java)
        var volume = 10
        `when`(context.getSystemService(AudioManager::class.java)).thenReturn(audio)
        `when`(audio.isMusicActive).thenReturn(true)
        `when`(audio.getStreamVolume(AudioManager.STREAM_MUSIC)).thenAnswer { volume }
        doAnswer { volume = it.getArgument(1); null }.`when`(audio).setStreamVolume(eq(AudioManager.STREAM_MUSIC), anyInt(), eq(0))
        field("mContext").set(controller, context)
        receive(0x28, 1)
        val speech = byteArrayOf(4, 0, 4, 0, 0x4b, 0, 2, 0, 0, 1)
        manager.receivePacket(speech)
        assertEquals(2, volume)
        val policy = mock(Intent::class.java)
        `when`(policy.action).thenReturn(HyperPodsAction.ACTION_PODS_SETTINGS_CHANGED)
        `when`(policy.getStringExtra("key")).thenReturn(Key.CONVERSATION_PHONE_VOLUME)
        `when`(policy.hasExtra("enabled")).thenReturn(true)
        `when`(policy.getBooleanExtra("enabled", true)).thenReturn(false)
        controller.handleUIEvent(policy)
        assertEquals(10, volume)
        assertNull(field("conversationTimeout").get(controller))
        assertNull(field("conversationRestoreJob").get(controller))
        manager.receivePacket(speech)
        assertEquals(10, volume)
        assertEquals(1.toByte(), manager.getControlCommandStatus(AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG)!!.value[0])
        assertEquals(0, output.size())
        verify(socket, never()).connect()
    }

    @Test fun fadeStopsOnRouteChangeEvenBeforeARouterCallbackArrives() {
        val volume = AtomicInteger(10)
        val fade = synchronized(controller) {
            val context = mock(Context::class.java)
            val audio = mock(AudioManager::class.java)
            `when`(context.getSystemService(AudioManager::class.java)).thenReturn(audio)
            `when`(audio.isMusicActive).thenReturn(true)
            `when`(audio.getStreamVolume(AudioManager.STREAM_MUSIC)).thenAnswer { volume.get() }
            doAnswer { volume.set(it.getArgument(1)); null }.`when`(audio).setStreamVolume(eq(AudioManager.STREAM_MUSIC), anyInt(), eq(0))
            field("mContext").set(controller, context)
            val routing = selectedHeadset()
            receive(0x28, 1)
            val speech = byteArrayOf(4, 0, 4, 0, 0x4b, 0, 2, 0, 0, 1)
            manager.receivePacket(speech)
            manager.receivePacket(speech.copyOf().apply { this[9] = 6 })
            val job = field("conversationRestoreJob").get(controller) as Job
            `when`(routing.selectedRoutes).thenReturn(emptyList())
            job
        }
        runBlocking { withTimeout(2000) { fade.join() } }
        assertEquals(2, volume.get())
        assertNull(field("conversationRouteId").get(controller))
    }

    @Test fun duplicateEndEventsReuseTheFadeAndNewSpeechCancelsIt() = synchronized(controller) {
        selectedHeadset()
        val context = mock(Context::class.java)
        val audio = mock(AudioManager::class.java)
        var volume = 10
        `when`(context.getSystemService(AudioManager::class.java)).thenReturn(audio)
        `when`(audio.isMusicActive).thenReturn(true)
        `when`(audio.getStreamVolume(AudioManager.STREAM_MUSIC)).thenAnswer { volume }
        doAnswer { volume = it.getArgument(1); null }.`when`(audio).setStreamVolume(eq(AudioManager.STREAM_MUSIC), anyInt(), eq(0))
        field("mContext").set(controller, context)
        receive(0x28, 1)
        val speech = byteArrayOf(4, 0, 4, 0, 0x4b, 0, 2, 0, 0, 1)
        val end = speech.copyOf().apply { this[9] = 6 }
        manager.receivePacket(speech)
        manager.receivePacket(end)
        val fade = field("conversationRestoreJob").get(controller) as Job
        assertTrue(fade.isActive)
        manager.receivePacket(end)
        assertSame(fade, field("conversationRestoreJob").get(controller))
        manager.receivePacket(speech)
        assertTrue(fade.isCancelled)
        assertNull(field("conversationRestoreJob").get(controller))
        assertEquals(2, volume)
        manager.receivePacket(end)
        val nextFade = field("conversationRestoreJob").get(controller) as Job
        request(Key.CONVERSATION_AWARENESS, 2)
        assertTrue(nextFade.isCancelled)
        assertEquals(10, volume)
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
        receive(0x17, 0)
        request(Key.PRESS_SPEED, 2)
        // JVM Android stubs cannot deliver broadcasts from the timer thread.
        field("mContext").set(controller, null)
        val deadline = System.nanoTime() + 4_000_000_000L
        while (synchronized(controller) { pending().isNotEmpty() } && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(pending().isEmpty())
        assertEquals(0.toByte(), manager.getControlCommandStatus(AACPManager.Companion.ControlCommandIdentifiers.DOUBLE_CLICK_INTERVAL)!!.value[0])
        assertEquals("timeout", field("lastSettingStatus").get(controller))
        receive(0x17, 2)
        assertEquals("reported", field("lastSettingStatus").get(controller))
        assertEquals(2.toByte(), manager.getControlCommandStatus(AACPManager.Companion.ControlCommandIdentifiers.DOUBLE_CLICK_INTERVAL)!!.value[0])
        field("mContext").set(controller, ContextWrapper(null))
        request(Key.PRESS_SPEED, 1)
        assertTrue(pending().containsKey(Key.PRESS_SPEED))
    }

    @Test fun disconnectCancelsOutstandingSettings() {
        request(Key.PRESS_SPEED, 2)
        val entry = pending().values.single()!!
        val timeout = entry.javaClass.getDeclaredField("timeout").apply { isAccessible = true }.get(entry) as Job
        field("mContext").set(controller, null)
        controller.disconnectedPod(ContextWrapper(null), device)
        assertTrue(timeout.isCancelled)
        assertTrue(pending().isEmpty())
    }
    @Test fun coreReportsRefreshOnlyTheSubscriptionEvenWhenFullRecoveryWasRequested() {
        coreReports()
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 0x2b, 0))
        assertFalse(statusSyncComplete())
        output.reset()
        val sent = type.getDeclaredMethod("requestHeadsetStatus", AACPManager::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(controller, manager, true) as Boolean
        assertTrue(sent)
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 0x0f, 0, -1, -1, -1, -1), output.toByteArray())
        verify(socket, never()).close()
    }

    @Test fun missingCoreDataStillAllowsFullHandshakeRecovery() {
        val sent = type.getDeclaredMethod("requestHeadsetStatus", AACPManager::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(controller, manager, true) as Boolean
        assertTrue(sent)
        assertEquals(40, output.size())
        assertArrayEquals(manager.createHandshakePacket(), output.toByteArray().copyOfRange(0, 16))
    }

    @Test fun manualAndPageRefreshDoNotResetAReadySessionForMissingAncillarySettings() {
        coreReports()
        receive(0x0d, 4)
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 0x2b, 0))
        assertTrue(initialReady())
        assertFalse(statusSyncComplete())
        field("socket").set(controller, socket)
        val generation = field("sessionId").getLong(controller)
        for (action in listOf(HyperPodsAction.ACTION_PODS_STATUS_RETRY, HyperPodsAction.ACTION_PODS_UI_INIT)) {
            output.reset()
            val intent = mock(Intent::class.java)
            `when`(intent.action).thenReturn(action)
            controller.handleUIEvent(intent)
            assertArrayEquals(byteArrayOf(4, 0, 4, 0, 0x0f, 0, -1, -1, -1, -1), output.toByteArray())
            assertEquals(generation, field("sessionId").getLong(controller))
            assertSame(manager, field("aacpManager").get(controller))
            verify(socket, never()).close()
        }
    }

    @Test fun audioTransitionsAreRecordedBeforeControlSetupWithoutStoringDeviceData() {
        field("mContext").set(controller, null)
        val context = mock(Context::class.java)
        mockConstruction(android.os.Bundle::class.java).use { bundles ->
            controller.onAudioConnectionState(context, device, 1, 2, true)
            val event = bundles.constructed().single()
            verify(event).putString("kind", "audio")
            verify(event).putString("detail", "1,2")
            verify(event, never()).putString(eq("mac"), anyString())
            verify(event, never()).putString(eq("device_name"), anyString())
        }
        mockConstruction(android.os.Bundle::class.java).use { bundles ->
            controller.onAudioConnectionState(context, device, 2, 0, false)
            controller.onAudioConnectionState(context, device, 2, 2, true)
            assertTrue(bundles.constructed().isEmpty())
        }
    }

    @Test fun diagnosticBroadcastFailureCannotInterruptAnAudioStateEvent() {
        val context = mock(Context::class.java)
        doThrow(SecurityException("Synthetic diagnostics failure")).`when`(context)
            .sendBroadcast(any(Intent::class.java), isNull(), nullable(android.os.Bundle::class.java))
        field("mContext").set(controller, context)
        controller.onAudioConnectionState(context, device, 2, 3, true)
        verify(context).sendBroadcast(any(Intent::class.java), isNull(), nullable(android.os.Bundle::class.java))
        assertEquals(3, field("audioConnectionState").getInt(controller))
        assertSame(manager, field("aacpManager").get(controller))
        assertSame(context, field("mContext").get(controller))
    }

    private fun unsolicitedCoreReports() {
        field("mContext").set(controller, null)
        controller.currentPodsInfo = null
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 4, 0, 2, 4, 1, 80, 2, 0, 2, 1, 70, 2, 0))
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 6, 0, 0, 0))
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 0x1d, 0, 0) +
            "protocol\u0000Test headset\u0000A3049\u0000".toByteArray())
        assertNotNull(controller.currentPodsInfo)
        assertEquals(AACPManager.InitializationStage.STATUS, manager.initializationStage)
        assertFalse(manager.featureFlagsSent)
        field("mContext").set(controller, ContextWrapper(null))
        output.reset()
    }

    private fun refreshStatus(full: Boolean = false): Boolean =
        type.getDeclaredMethod("requestHeadsetStatus", AACPManager::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(controller, manager, full) as Boolean

    @Test fun unsolicitedCoreReportsDoNotSkipTheFirstFeatureConfiguration() {
        unsolicitedCoreReports()
        val generation = field("sessionId").getLong(controller)
        val intent = mock(Intent::class.java)
        `when`(intent.action).thenReturn(HyperPodsAction.ACTION_PODS_UI_INIT)
        controller.handleUIEvent(intent)
        assertTrue(manager.featureFlagsSent)
        assertArrayEquals(manager.createDataPacket(manager.createSetFeatureFlagsPacket()) +
            manager.createDataPacket(manager.createRequestNotificationPacket()), output.toByteArray())
        output.reset()
        controller.handleUIEvent(intent)
        assertArrayEquals(manager.createDataPacket(manager.createRequestNotificationPacket()), output.toByteArray())
        assertEquals(generation, field("sessionId").getLong(controller))
        verify(socket, never()).close()
    }

    @Test fun completeReportsStillRequireTheInitialFeatureConfigurationBeforeStoppingRecovery() {
        unsolicitedCoreReports()
        receiveRemainingSettings()
        assertTrue(initialReady())
        assertFalse(statusSyncComplete())
        output.reset()
        assertTrue(refreshStatus(full = true))
        assertTrue(statusSyncComplete())
        assertArrayEquals(manager.createDataPacket(manager.createSetFeatureFlagsPacket()) +
            manager.createDataPacket(manager.createRequestNotificationPacket()), output.toByteArray())
    }

    @Test fun rejectedFeatureWriteRemainsEligibleForTheNextBoundedRefresh() {
        unsolicitedCoreReports()
        receiveRemainingSettings()
        `when`(socket.outputStream).thenThrow(IOException("Synthetic feature write failure"))
        assertFalse(refreshStatus(full = true))
        assertFalse(manager.featureFlagsSent)
        assertFalse(statusSyncComplete())
        doReturn(output).`when`(socket).outputStream
        assertTrue(refreshStatus(full = true))
        assertTrue(statusSyncComplete())
    }

    @Test fun explicitRetryForMissingListeningModeRebuildsOnlyTheControlChannel() {
        unsolicitedCoreReports()
        manager.sendSetFeatureFlagsPacket()
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 0x2b, 0))
        assertFalse(initialReady())
        val context = mock(Context::class.java)
        val audio = mock(AudioManager::class.java)
        val router = mock(MediaRouter2::class.java)
        `when`(context.getSystemService(AudioManager::class.java)).thenReturn(audio)
        field("mContext").set(controller, context)
        field("mediaRouter").set(controller, router)
        field("socket").set(controller, socket)
        field("pausedAudio").setBoolean(controller, true)
        val generation = field("sessionId").getLong(controller)
        val audioGeneration = field("audioSessionId").getLong(controller)
        val actions = mutableListOf<Any?>()
        try {
            mockConstruction(Intent::class.java) { _, construction -> actions.add(construction.arguments().firstOrNull()) }.use {
                val intent = mock(Intent::class.java)
                `when`(intent.action).thenReturn(HyperPodsAction.ACTION_PODS_STATUS_RETRY)
                controller.handleUIEvent(intent)
            }
            (field("connectionJob").get(controller) as Job?)?.cancel()
            assertEquals(generation + 1, field("sessionId").getLong(controller))
            assertEquals(audioGeneration, field("audioSessionId").getLong(controller))
            assertNull(field("aacpManager").get(controller))
            assertSame(context, field("mContext").get(controller))
            assertSame(device, controller.mDevice)
            assertTrue(field("pausedAudio").getBoolean(controller))
            verify(socket, timeout(1000)).close()
            verify(audio, never()).dispatchMediaKeyEvent(any())
            verify(router, never()).transferTo(any())
            assertFalse(actions.contains(HyperPodsAction.ACTION_PODS_DISCONNECTED))
            assertFalse(actions.contains(HyperPodsAction.ACTION_CANCEL_NOTIFICATION))
        } finally {
            (field("connectionJob").get(controller) as Job?)?.cancel()
            field("pausedAudio").setBoolean(controller, false)
        }
    }

    private suspend fun recoverWhileTransferringAudio(toSpeaker: Boolean) {
        val context = mock(Context::class.java)
        val audio = mock(AudioManager::class.java)
        val router = mock(MediaRouter2::class.java)
        val routing = mock(MediaRouter2.RoutingController::class.java)
        fun route(id: String, type: Int) = mock(MediaRoute2Info::class.java).apply {
            `when`(this.id).thenReturn(id)
            `when`(this.type).thenReturn(type)
            `when`(this.name).thenReturn("Test headset")
        }
        val headset = route("headset", MediaRoute2Info.TYPE_BLUETOOTH_A2DP)
        val speaker = route("speaker", MediaRoute2Info.TYPE_BUILTIN_SPEAKER)
        val target = if (toSpeaker) speaker else headset
        `when`(device.name).thenReturn("Test headset")
        `when`(context.getSystemService(AudioManager::class.java)).thenReturn(audio)
        `when`(audio.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(router.systemController).thenReturn(routing)
        `when`(routing.selectedRoutes).thenReturn(listOf(if (toSpeaker) headset else speaker))
        field("mContext").set(controller, context)
        field("mediaRouter").set(controller, router)
        field("socket").set(controller, socket)
        field("pausedAudio").setBoolean(controller, true)
        field("switchedToSpeaker").setBoolean(controller, true)
        controller.routes = listOf(headset, speaker)
        coreReports()
        controller.currentEarDetectionParams = EarDetectionParams(EarDetectionStatus.OUT_OF_EAR, EarDetectionStatus.OUT_OF_EAR)
        manager.sendSetFeatureFlagsPacket()
        assertFalse(initialReady()) // Missing listening mode still permits explicit control recovery.
        val generation = field("sessionId").getLong(controller)
        val audioGeneration = field("audioSessionId").getLong(controller)
        try {
            type.getDeclaredMethod("requestAudioRoute", MediaRoute2Info::class.java)
                .apply { isAccessible = true }.invoke(controller, target)
            val timeout = field("routeTransferTimeout").get(controller) as Job
            val retry = mock(Intent::class.java)
            `when`(retry.action).thenReturn(HyperPodsAction.ACTION_PODS_STATUS_RETRY)
            controller.handleUIEvent(retry)
            (field("connectionJob").get(controller) as Job?)?.cancel()
            withTimeout(5000) { timeout.join() }
            assertEquals(generation + 1, field("sessionId").getLong(controller))
            assertEquals(audioGeneration, field("audioSessionId").getLong(controller))
            assertNull(field("pendingAudioRoute").get(controller))
            assertSame(context, field("mContext").get(controller))
            assertTrue(field("pausedAudio").getBoolean(controller))
            verify(socket, timeout(1000)).close() // Only the replaced control socket.
            verify(audio, never()).dispatchMediaKeyEvent(any())

            // A fresh wear report may restore the route, but cannot play on the phone speaker.
            type.getDeclaredMethod("handleInEarStatusChanged", List::class.java).apply { isAccessible = true }
                .invoke(controller, listOf(EarDetectionStatus.IN_EAR, EarDetectionStatus.IN_EAR))
            if (!toSpeaker) {
                verify(audio, never()).dispatchMediaKeyEvent(any())
                verify(router, times(2)).transferTo(headset)
                `when`(routing.selectedRoutes).thenReturn(listOf(headset))
            }
            repeat(2) { type.getDeclaredMethod("handleAudioRouteChanged").apply { isAccessible = true }.invoke(controller) }
            verify(audio, times(2)).dispatchMediaKeyEvent(any()) // One Play down/up pair, never duplicate.
            assertNull(field("pendingAudioRoute").get(controller))
            assertFalse(field("pausedAudio").getBoolean(controller))
        } finally {
            (field("connectionJob").get(controller) as Job?)?.cancel()
            MediaControl.mContext = null
        }
    }

    @Test fun controlRecoveryKeepsSpeakerTransferTimeoutAndLaterRewearResume() = runBlocking {
        recoverWhileTransferringAudio(toSpeaker = true)
    }

    @Test fun controlRecoveryKeepsHeadphoneTransferTimeoutAndWaitsForTheSelectedRoute() = runBlocking {
        recoverWhileTransferringAudio(toSpeaker = false)
    }

    @Test fun scheduledHeadphoneRetrySurvivesControlRecoveryAndResumesOnlyAfterTransfer() = runBlocking {
        val context = mock(Context::class.java)
        val audio = mock(AudioManager::class.java)
        val router = mock(MediaRouter2::class.java)
        val routing = mock(MediaRouter2.RoutingController::class.java)
        val headset = mock(MediaRoute2Info::class.java)
        val speaker = mock(MediaRoute2Info::class.java)
        `when`(device.name).thenReturn("Test headset")
        `when`(headset.id).thenReturn("headset")
        `when`(headset.name).thenReturn("Test headset")
        `when`(headset.type).thenReturn(MediaRoute2Info.TYPE_BLUETOOTH_A2DP)
        `when`(speaker.id).thenReturn("speaker")
        `when`(speaker.type).thenReturn(MediaRoute2Info.TYPE_BUILTIN_SPEAKER)
        `when`(context.getSystemService(AudioManager::class.java)).thenReturn(audio)
        `when`(audio.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(router.systemController).thenReturn(routing)
        `when`(routing.selectedRoutes).thenReturn(listOf(speaker))
        field("mContext").set(controller, context)
        field("mediaRouter").set(controller, router)
        field("socket").set(controller, socket)
        field("switchedToSpeaker").setBoolean(controller, true)
        field("pausedAudio").setBoolean(controller, true)
        controller.routes = listOf(headset, speaker)
        try {
            val audioGeneration = field("audioSessionId").getLong(controller)
            val retryJob = synchronized(controller) {
                coreReports()
                field("pendingAudioRoute").set(controller, headset)
                type.getDeclaredMethod("handleAudioRouteFailure", MediaRoute2Info::class.java)
                    .apply { isAccessible = true }.invoke(controller, headset)
                val task = field("routeRetryJob").get(controller) as Job
                val intent = mock(Intent::class.java)
                `when`(intent.action).thenReturn(HyperPodsAction.ACTION_PODS_STATUS_RETRY)
                controller.handleUIEvent(intent)
                (field("connectionJob").get(controller) as Job?)?.cancel()
                coreReports()
                task
            }
            withTimeout(2000) { retryJob.join() }
            assertEquals(audioGeneration, field("audioSessionId").getLong(controller))
            verify(router).transferTo(headset)
            verify(audio, never()).dispatchMediaKeyEvent(any())
            `when`(routing.selectedRoutes).thenReturn(listOf(headset))
            repeat(2) { type.getDeclaredMethod("handleAudioRouteChanged").apply { isAccessible = true }.invoke(controller) }
            verify(audio, times(2)).dispatchMediaKeyEvent(any())
            assertFalse(field("pausedAudio").getBoolean(controller))
        } finally {
            (field("connectionJob").get(controller) as Job?)?.cancel()
            MediaControl.mContext = null
        }
    }

}
