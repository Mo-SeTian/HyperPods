package moe.chenxy.hyperpods.pods

import android.app.BroadcastOptions
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.ContextWrapper
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.chenxy.hyperpods.utils.AACPManager
import moe.chenxy.hyperpods.utils.AirPods5
import moe.chenxy.hyperpods.utils.AppPreferences
import moe.chenxy.hyperpods.utils.PodsSettings
import moe.chenxy.hyperpods.utils.SinglePodAnc
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
        field("headGestures").setBoolean(controller, false)
        field("earDetection").setBoolean(controller, true)
        field("autoSwitchToSpeaker").setBoolean(controller, true)
        field("switchedToSpeaker").setBoolean(controller, false)
        AirPodsNotifications.BatteryNotification.reset()
        AirPodsNotifications.EarDetection.reset()
        field("batteryStateValid").set(controller, false)
        field("earDetectionStateValid").set(controller, false)
        field("conversationPhoneVolume").set(controller, true)
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
            if (construction.arguments().firstOrNull() == "chen.action.hyperpods.updatepodsnotification") updates.add(intent)
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
        assertEquals(PodsSettings.NOISE_MODE, field("lastSettingKey").get(controller))
        assertEquals("sent", field("lastSettingStatus").get(controller))
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
        val deadline = System.nanoTime() + 7_000_000_000L
        while (synchronized(controller) { pending().isNotEmpty() } && System.nanoTime() < deadline) Thread.sleep(20)
        assertFalse(field("pendingOffSelection").getBoolean(controller))
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x34, 1, 0, 0, 0) +
            manager.createDataPacket(manager.createRequestNotificationPacket()), output.toByteArray())
        field("mContext").set(controller, ContextWrapper(null))
        controller.setANCMode(1)
        assertTrue(field("pendingOffSelection").getBoolean(controller))
        assertEquals(32, output.size())
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

    @Test fun disconnectAbandonsTheOffPrerequisiteAndCannotSendALateModeCommand() {
        controller.setANCMode(1)
        field("mContext").set(controller, null)
        controller.disconnectedPod(ContextWrapper(null), device)
        receive(0x34, 1)
        assertFalse(field("pendingOffSelection").getBoolean(controller))
        assertTrue(pending().isEmpty())
        assertEquals(11, output.size())
    }

    @Test fun offPrerequisiteCanCompleteAfterItsSingleBoundedStatusRefresh() {
        controller.setANCMode(1)
        // Timer broadcasts use no-op stubs here; the protocol writer remains real.
        field("mContext").set(controller, null)
        Thread.sleep(3300)
        synchronized(controller) {
            assertEquals(21, output.size()) // Allow Off + one status subscription.
            assertTrue(pending().containsKey(Key.ALLOW_OFF_OPTION))
            receive(0x34, 1)
            assertTrue(pending().containsKey(PodsSettings.NOISE_MODE))
            assertEquals(32, output.size()) // Only a confirmed prerequisite sends real Off.
        }
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
            if (construction.arguments().firstOrNull() == "chen.action.hyperpods.updatepodsnotification") notifications.add(intent)
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
}
