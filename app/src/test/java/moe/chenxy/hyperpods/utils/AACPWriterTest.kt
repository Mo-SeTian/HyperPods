package moe.chenxy.hyperpods.utils

import android.bluetooth.BluetoothSocket
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class AACPWriterTest {
    @Test fun writesAreOrderedCopiedAndNeverHoldTheCallersLock() {
        val socket = mock(BluetoothSocket::class.java)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val output = ByteArrayOutputStream()
        `when`(socket.isConnected).thenReturn(true)
        `when`(socket.outputStream).thenReturn(object : OutputStream() {
            override fun write(b: Int) = error("Use complete packets")
            override fun write(bytes: ByteArray) {
                entered.countDown()
                if (!release.await(2, TimeUnit.SECONDS)) throw IOException("Synthetic timeout")
                output.write(bytes)
                completed.countDown()
            }
        })
        AACPWriter(socket) { fail("Unexpected write failure") }.use { writer ->
            val lock = Any()
            val first = byteArrayOf(1, 2)
            synchronized(lock) {
                assertTrue(writer.send(first))
                assertTrue(writer.send(byteArrayOf(3)))
                first[0] = 9
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val acquired = CountDownLatch(1)
            Thread { synchronized(lock) { acquired.countDown() } }.start()
            assertTrue(acquired.await(1, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
        }
    }

    @Test fun blockedWriteTimesOutClosesOnlyItsSocketAndDropsQueuedPackets() {
        val socket = mock(BluetoothSocket::class.java)
        val entered = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val writes = AtomicInteger()
        val failures = AtomicInteger()
        `when`(socket.isConnected).thenReturn(true)
        `when`(socket.outputStream).thenReturn(object : OutputStream() {
            override fun write(b: Int) {
                writes.incrementAndGet()
                entered.countDown()
                try { CountDownLatch(1).await() } catch (_: InterruptedException) { throw IOException("Interrupted") }
            }
        })
        AACPWriter(socket, timeoutMs = 100) { failures.incrementAndGet(); failed.countDown() }.use { writer ->
            assertTrue(writer.send(byteArrayOf(1)))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(writer.send(byteArrayOf(2)))
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            assertFalse(writer.send(byteArrayOf(3)))
            assertEquals(1, failures.get())
            assertEquals(1, writes.get())
            verify(socket, timeout(1000).atLeastOnce()).close()
        }
    }
}
