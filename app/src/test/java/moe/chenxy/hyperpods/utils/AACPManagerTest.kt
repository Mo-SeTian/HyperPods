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

    @Test fun renameUsesUtf8ByteLengthAndIncludesTheNullTerminator() {
        val name = "测试耳机"
        val result = manager.createRenamePacket(name)
        assertEquals(name.toByteArray().size, result[2].toInt())
        assertEquals(0, result.last().toInt())
        assertArrayEquals(name.toByteArray(), result.copyOfRange(4, result.size - 1))
        assertThrows(IllegalArgumentException::class.java) { manager.createRenamePacket(" ") }
        assertThrows(IllegalArgumentException::class.java) { manager.createRenamePacket("a".repeat(256)) }
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
