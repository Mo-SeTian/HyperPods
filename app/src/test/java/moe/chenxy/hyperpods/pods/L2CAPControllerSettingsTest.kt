package moe.chenxy.hyperpods.pods

import android.app.BroadcastOptions
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.ContextWrapper
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import kotlinx.coroutines.Job
import moe.chenxy.hyperpods.utils.AACPManager
import moe.chenxy.hyperpods.utils.AppPreferences
import moe.chenxy.hyperpods.utils.PodsSettings
import moe.chenxy.hyperpods.utils.LowBatterySettings
import moe.chenxy.hyperpods.utils.LowBatteryReminder
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

    private fun receiveRemainingSettings() {
        for ((key, id) in PodsSettings.identifiers) {
            if (manager.getControlCommandStatus(id) != null) continue
            val value = when (key) {
                PodsSettings.NOISE_MODE -> 4
                Key.LISTENING_MODE_BYTE -> 6
                Key.MICROPHONE_MODE, Key.PRESS_SPEED, Key.HOLD_DURATION, Key.SWIPE_SPEED -> 0
                Key.CHIME_VOLUME, Key.ADAPTIVE_AUDIO_LEVEL -> 50
                else -> 2
            }
            receive(id.value, value.toByte())
        }
    }

    @Test fun batteryAloneDoesNotStopRecoveryBeforeInformationAndSettingsArrive() {
        field("batteryStateValid").set(controller, true)
        assertFalse(initialReady())
        field("earDetectionStateValid").set(controller, true)
        assertFalse(initialReady())
        receive(0x0d, 4)
        assertFalse("Listening mode alone must not hide missing settings", initialReady())
        receiveRemainingSettings()
        assertTrue(initialReady())
        controller.currentPodsInfo = null
        assertFalse(initialReady())
    }

    @Test fun cachedPendingBatteryStillRequestsRefreshButSingleEarCanFinishInitialization() {
        field("batteryStateValid").set(controller, true)
        field("earDetectionStateValid").set(controller, true)
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

    @Test fun timingRequestsUseZeroBasedValuesAndStillWaitForTheHeadset() {
        for (key in listOf(Key.PRESS_SPEED, Key.HOLD_DURATION, Key.SWIPE_SPEED)) {
            val id = PodsSettings.identifiers.getValue(key)
            receive(id.value, 1)
            request(key, 0)
            assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, id.value, 0, 0, 0, 0), output.toByteArray())
            assertTrue(pending().containsKey(key))
            assertEquals(1.toByte(), manager.getControlCommandStatus(id)!!.value[0])
            receive(id.value, 0)
            assertTrue(pending().isEmpty())
            output.reset()
        }
    }

    @Test fun chimeVolumePreservesTheSecondByteAndNeedsConfirmation() {
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 9, 0, 0x1f, 80, 67, 0, 0))
        request(Key.CHIME_VOLUME, 35)
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x1f, 35, 67, 0, 0), output.toByteArray())
        assertTrue(pending().containsKey(Key.CHIME_VOLUME))
        receive(0x1f, 35)
        assertTrue(pending().isEmpty())
    }

    @Test fun chimeVolumeRetainsZeroConfigurationWithoutReplacingItWithADefault() {
        manager.receivePacket(byteArrayOf(4, 0, 4, 0, 9, 0, 0x1f, 80, 0, 0, 0))
        request(Key.CHIME_VOLUME, 35)
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x1f, 35, 0, 0, 0), output.toByteArray())
    }

    @Test fun manualRecoveryRestartsHandshakeBeforeSubscriptionWithoutOpeningAnotherSocket() {
        val intent = mock(Intent::class.java)
        `when`(intent.action).thenReturn(HyperPodsAction.ACTION_PODS_STATUS_RETRY)
        controller.handleUIEvent(intent)
        assertArrayEquals(manager.createHandshakePacket(), output.toByteArray())
        verify(socket, never()).connect()
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
        request(Key.MICROPHONE_MODE, 2)
        assertEquals("sent", field("lastSettingStatus").get(controller))
        receive(1, 2)
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

    @Test fun disablingPhoneDuckingRestoresVolumeAndDoesNotDisableHeadsetConversationAwareness() = synchronized(controller) {
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

    @Test fun duplicateEndEventsReuseTheFadeAndNewSpeechCancelsIt() = synchronized(controller) {
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
        receive(1, 0)
        request(Key.MICROPHONE_MODE, 2)
        // JVM Android stubs cannot deliver broadcasts from the timer thread.
        field("mContext").set(controller, null)
        val deadline = System.nanoTime() + 4_000_000_000L
        while (synchronized(controller) { pending().isNotEmpty() } && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(pending().isEmpty())
        assertEquals(0.toByte(), manager.getControlCommandStatus(AACPManager.Companion.ControlCommandIdentifiers.MIC_MODE)!!.value[0])
        assertEquals("timeout", field("lastSettingStatus").get(controller))
        receive(1, 2)
        assertEquals("reported", field("lastSettingStatus").get(controller))
        assertEquals(2.toByte(), manager.getControlCommandStatus(AACPManager.Companion.ControlCommandIdentifiers.MIC_MODE)!!.value[0])
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
