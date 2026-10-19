package moe.chenxy.hyperpods.utils

import android.bluetooth.BluetoothSocket
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Socket writes must never run on a receiver thread or under the controller lock.
 * A stuck write closes only this control socket, unblocking its reader and recovery loop.
 * true means queued, not acknowledged by the headset; any later failure ends the session. */
internal class AACPWriter(private val socket: BluetoothSocket, private val timeoutMs: Long = 3000,
    private val onFailure: () -> Unit) : Closeable {
    private val closed = AtomicBoolean()
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32),
        { task -> Thread(task, "HyperPods-AACP-writer").apply { isDaemon = true } })
    private val watchdog = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "HyperPods-AACP-write-timeout").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    fun send(packet: ByteArray): Boolean {
        if (packet.isEmpty() || closed.get() || !socket.isConnected) return false
        val copy = packet.copyOf()
        return try {
            worker.execute {
                if (!closed.get()) {
                    val timeout = try {
                        watchdog.schedule({ fail() }, timeoutMs, TimeUnit.MILLISECONDS)
                    } catch (_: RejectedExecutionException) { return@execute }
                    try {
                        val output = socket.outputStream ?: error("Missing control output")
                        output.write(copy)
                        output.flush()
                    } catch (_: Exception) {
                        fail()
                    } finally {
                        timeout.cancel(false)
                    }
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            // Queue exhaustion is a failed session, not permission to drop commands silently.
            if (!closed.get()) runCatching { watchdog.execute { fail() } }
            false
        }
    }

    private fun fail() {
        if (!closed.compareAndSet(false, true)) return
        worker.shutdownNow()
        // Record the cause before close wakes the reader. This callback must not block.
        try { onFailure() } finally {
            runCatching { socket.close() }
            watchdog.shutdownNow()
        }
    }

    override fun close() {
        closed.set(true)
        worker.shutdownNow()
        watchdog.shutdownNow()
        runCatching { socket.close() }
    }
}
