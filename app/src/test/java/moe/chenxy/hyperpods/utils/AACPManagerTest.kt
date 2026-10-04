package moe.chenxy.hyperpods.utils

import android.bluetooth.BluetoothSocket
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.io.ByteArrayOutputStream

class AACPManagerTest {
    private val manager = AACPManager(mock(BluetoothSocket::class.java))
    private val callback = mock(AACPManager.PacketCallback::class.java).also { manager.setPacketCallback(it) }

    private fun packet(opcode: Byte, size: Int) = ByteArray(size).apply {
        this[0] = 4
        this[2] = 4
        this[4] = opcode
    }

    @Test fun variableLengthBatteryReportsReachTheController() {
        for (count in 1..3) {
            val report = packet(4, 7 + 5 * count).apply { this[6] = count.toByte() }
            manager.receivePacket(report)
            verify(callback).onBatteryInfoReceived(report)
        }
        verifyNoMoreInteractions(callback)
    }

    @Test fun batteryCountMustMatchTheCompleteFrame() {
        for (count in listOf(0, 2, 3, 4, 255)) {
            manager.receivePacket(packet(4, 12).apply { this[6] = count.toByte() })
        }
        manager.receivePacket(packet(4, 6))
        verifyNoInteractions(callback)
    }

    @Test fun truncatedControlPacketsAreRejectedBeforeCopyingTheirValue() {
        for (size in 6..10) {
            assertThrows(IllegalArgumentException::class.java) {
                AACPManager.ControlCommand.fromByteArray(packet(AACPManager.Companion.Opcodes.CONTROL_COMMAND, size))
            }
        }
        val complete = packet(AACPManager.Companion.Opcodes.CONTROL_COMMAND, 11).apply {
            this[6] = 13
            this[7] = 2
        }
        assertArrayEquals(byteArrayOf(2), AACPManager.ControlCommand.fromByteArray(complete).value)
    }

    @Test fun malformedNotificationsDoNotPreventTheNextValidPacket() {
        listOf(AACPManager.Companion.Opcodes.CONTROL_COMMAND,
            AACPManager.Companion.Opcodes.EAR_DETECTION,
            AACPManager.Companion.Opcodes.CONVERSATION_AWARENESS,
            AACPManager.Companion.Opcodes.CONNECTED_DEVICES,
            AACPManager.Companion.Opcodes.SMART_ROUTING_RESP,
            AACPManager.Companion.Opcodes.INFORMATION).forEach {
            manager.receivePacket(packet(it, 6))
        }
        verifyNoInteractions(callback)
        val complete = packet(AACPManager.Companion.Opcodes.EAR_DETECTION, 8)
        manager.receivePacket(complete)
        verify(callback).onEarDetectionReceived(complete)
    }

    @Test fun connectedDeviceCountIsUnsignedAndRequiresCompleteRecords() {
        val empty = packet(AACPManager.Companion.Opcodes.CONNECTED_DEVICES, 9)
        assertTrue(manager.parseConnectedDevicesResponse(empty).isEmpty())
        empty[8] = 0xff.toByte()
        assertThrows(IllegalArgumentException::class.java) { manager.parseConnectedDevicesResponse(empty) }
        assertThrows(IllegalArgumentException::class.java) {
            manager.parseConnectedDevicesResponse(packet(AACPManager.Companion.Opcodes.CONNECTED_DEVICES, 8))
        }
    }

    @Test fun incompleteAudioAndInformationPayloadsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            manager.parseAudioSourceResponse(packet(AACPManager.Companion.Opcodes.AUDIO_SOURCE, 12))
        }
        assertThrows(IllegalArgumentException::class.java) {
            manager.parseInformationPacket(packet(AACPManager.Companion.Opcodes.INFORMATION, 7))
        }
    }

    @Test fun renameMatchesTheIndependentAsciiProtocolFrame() {
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 0x1a, 0, 1, 4, 0, 0x44, 0x65, 0x6d, 0x6f),
            manager.createDataPacket(manager.createRenamePacket("Demo")))
    }

    @Test fun handshakeWritesOneRawConnectFrameWithoutMessageWrapping() {
        val socket = mock(BluetoothSocket::class.java)
        val output = ByteArrayOutputStream()
        `when`(socket.isConnected).thenReturn(true)
        `when`(socket.outputStream).thenReturn(output)
        assertTrue(AACPManager(socket).sendHandshake())
        assertArrayEquals(byteArrayOf(0, 0, 4, 0, 1, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0), output.toByteArray())
    }

    @Test fun initializationRetriesTheMissingStepAndAdvancesOnlyAfterReplies() {
        val socket = mock(BluetoothSocket::class.java)
        val output = ByteArrayOutputStream()
        `when`(socket.isConnected).thenReturn(true)
        `when`(socket.outputStream).thenReturn(output)
        val session = AACPManager(socket)
        assertTrue(session.requestInitialStatus())
        assertArrayEquals(session.createHandshakePacket(), output.toByteArray())
        output.reset()
        // A lost initial handshake must be retried, not followed by an ineffective subscription.
        session.requestInitialStatus()
        assertArrayEquals(session.createHandshakePacket(), output.toByteArray())
        output.reset()
        session.receivePacket(ByteArray(18).apply { this[0] = 1; this[2] = 4 })
        assertEquals(AACPManager.InitializationStage.FEATURES, session.initializationStage)
        assertArrayEquals(session.createDataPacket(session.createSetFeatureFlagsPacket()), output.toByteArray())
        output.reset()
        session.requestInitialStatus()
        assertArrayEquals(session.createDataPacket(session.createSetFeatureFlagsPacket()), output.toByteArray())
        output.reset()
        session.receivePacket(packet(0x2b, 14))
        assertEquals(AACPManager.InitializationStage.STATUS, session.initializationStage)
        assertArrayEquals(session.createDataPacket(session.createRequestNotificationPacket()), output.toByteArray())
        output.reset()
        session.requestInitialStatus()
        assertArrayEquals(session.createDataPacket(session.createRequestNotificationPacket()), output.toByteArray())
        assertEquals(2, session.receivedPacketCount)
    }

    @Test fun shortAcknowledgementAndMalformedStateDoNotAdvanceInitialization() {
        manager.receivePacket(byteArrayOf(1, 0, 4, 0))
        manager.receivePacket(packet(0x09, 7))
        assertEquals(AACPManager.InitializationStage.HANDSHAKE, manager.initializationStage)
        assertEquals(0, manager.receivedPacketCount)
        manager.receivePacket(packet(0x09, 11).apply { this[6] = 0x0d; this[7] = 4 })
        assertEquals(AACPManager.InitializationStage.STATUS, manager.initializationStage)
        assertEquals(1, manager.receivedPacketCount)
    }

    @Test fun renameUsesUtf8ByteLengthAfterTheFixedField() {
        val name = "测试耳机"
        val result = manager.createRenamePacket(name)
        assertEquals(1, result[2].toInt())
        assertEquals(name.toByteArray().size, result[3].toInt() and 0xff)
        assertEquals(0, result[4].toInt())
        assertArrayEquals(name.toByteArray(), result.copyOfRange(5, result.size))
        assertThrows(IllegalArgumentException::class.java) { manager.createRenamePacket(" ") }
        assertThrows(IllegalArgumentException::class.java) { manager.createRenamePacket("a".repeat(256)) }
        assertThrows(IllegalArgumentException::class.java) { manager.createRenamePacket("A\u0000B") }
        assertEquals(255, manager.createRenamePacket("a".repeat(255))[3].toInt() and 0xff)
        assertEquals(4, manager.createRenamePacket("🎧")[3].toInt() and 0xff)
    }

    @Test fun otherSettingsStillRequireReportsForEverySendOverload() {
        val socket = mock(BluetoothSocket::class.java)
        `when`(socket.isConnected).thenReturn(true)
        `when`(socket.outputStream).thenReturn(ByteArrayOutputStream())
        val sender = AACPManager(socket)
        assertTrue(sender.sendControlCommand(0x25, true))
        assertTrue(sender.sendControlCommand(0x25, 2))
        assertTrue(sender.sendControlCommand(0x25, 1.toByte()))
        assertTrue(sender.sendControlCommand(0x25, byteArrayOf(2)))
        assertTrue(sender.controlCommandStatusList.isEmpty())
    }

    @Test fun listeningTogglesUpdateLocallyForEverySendOverload() {
        val socket = mock(BluetoothSocket::class.java)
        `when`(socket.isConnected).thenReturn(true)
        `when`(socket.outputStream).thenReturn(ByteArrayOutputStream())
        val sender = AACPManager(socket)
        for (id in listOf(AACPManager.Companion.ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG,
            AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG)) {
            assertTrue(sender.sendControlCommand(id.value, true))
            assertEquals(1.toByte(), sender.getControlCommandStatus(id)!!.value[0])
            assertTrue(sender.sendControlCommand(id.value, 2))
            assertEquals(2.toByte(), sender.getControlCommandStatus(id)!!.value[0])
            assertTrue(sender.sendControlCommand(id.value, 1.toByte()))
            assertEquals(1.toByte(), sender.getControlCommandStatus(id)!!.value[0])
            assertTrue(sender.sendControlCommand(id.value, byteArrayOf(2)))
            assertEquals(2.toByte(), sender.getControlCommandStatus(id)!!.value[0])
        }
    }

    @Test fun failedWritePreservesTheLastReceivedSetting() {
        manager.receivePacket(packet(0x09, 11).apply { this[6] = 0x26; this[7] = 2 })
        assertFalse(manager.sendControlCommand(0x26, true))
        assertEquals(2.toByte(), manager.getControlCommandStatus(AACPManager.Companion.ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG)!!.value[0])
    }

    @Test fun receivedControlCommandNotifiesListenerExactlyOnce() {
        val listener = mock(AACPManager.ControlCommandListener::class.java)
        manager.registerControlCommandListener(AACPManager.Companion.ControlCommandIdentifiers.MIC_MODE, listener)
        manager.receivePacket(packet(0x09, 11).apply { this[6] = 1; this[7] = 2 })
        verify(listener, times(1)).onControlCommandReceived(AACPManager.ControlCommand(1, byteArrayOf(2)))
        assertEquals(2.toByte(), manager.getControlCommandStatus(AACPManager.Companion.ControlCommandIdentifiers.MIC_MODE)!!.value[0])
    }

    @Test fun shortOutboundRawCommandsAreWrittenWithoutIndexingAnOpcode() {
        val socket = mock(BluetoothSocket::class.java)
        val output = ByteArrayOutputStream()
        `when`(socket.isConnected).thenReturn(true)
        `when`(socket.outputStream).thenReturn(output)
        val sender = AACPManager(socket)
        val raw = byteArrayOf(0x52, 0x1b, 0, 1)
        assertTrue(sender.sendPacket(raw))
        assertArrayEquals(raw, output.toByteArray())
        assertFalse(sender.sendPacket(byteArrayOf()))
    }
}
