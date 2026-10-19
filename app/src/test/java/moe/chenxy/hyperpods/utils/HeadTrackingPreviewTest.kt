package moe.chenxy.hyperpods.utils

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class HeadTrackingPreviewTest {
    private val commands = mutableListOf<Boolean>()
    private var writesAccepted = true
    private val stream = HeadTrackingStream { commands.add(it); writesAccepted }
    private val reports = mutableListOf<HeadTrackingPreview.Snapshot>()
    private val preview = HeadTrackingPreview(stream) { reports.add(it) }
    private var at = 0L
    private val latest get() = reports.last()
    private fun start(id: String = "test", connected: Boolean = true, supported: Boolean = true,
        worn: Boolean = true, inCall: Boolean = false) = preview.start(id, at, connected, supported, worn, inCall)

    private fun packet(horizontal: Int = 0, vertical: Int = 0, o2: Int = 0, o3: Int = 0): ByteArray {
        val bytes = byteArrayOf(4, 0, 4, 0, 0x17, 0) + ByteArray(64)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(45, o2.toShort()); putShort(47, o3.toShort())
            putShort(51, horizontal.toShort()); putShort(53, vertical.toShort())
        }
        return bytes
    }

    private fun motion(horizontal: Boolean) {
        repeat(100) { index ->
            at += 20
            val value = (1200 * sin(2 * PI * index / 24)).toInt()
            preview.onPacket(if (horizontal) packet(horizontal = value) else packet(vertical = value), at)
        }
    }

    @Test fun poseDecodesSignedLittleEndianAndCalibratesRelativeToTenSamples() {
        val pose = HeadPose()
        repeat(9) { assertFalse(pose.process(packet(o2 = -1000, o3 = 2000))!!.calibrated) }
        assertTrue(pose.process(packet(o2 = -1000, o3 = 2000))!!.calibrated)
        val moved = pose.process(packet(horizontal = -123, vertical = 456, o2 = 15000, o3 = 2000))!!
        assertEquals(45f, moved.pitch, .001f)
        assertEquals(45f, moved.yaw, .001f)
        assertEquals(-123, moved.horizontal)
        assertEquals(456, moved.vertical)
        pose.reset()
        assertFalse(pose.process(packet())!!.calibrated)
    }

    @Test fun invalidOrWrongOpcodePacketsNeverCalibrateOrPreventNoDataTimeout() {
        start()
        repeat(20) {
            at += 100
            preview.onPacket(packet().apply { this[4] = 1 }, at)
            preview.onPacket(packet(horizontal = 6001), at)
            preview.onPacket(ByteArray(54), at)
        }
        at = 5000
        preview.command("test", "heartbeat", at)
        preview.tick(at)
        assertEquals("no_data", latest.state)
        assertEquals(0, latest.packets)
        assertEquals(listOf(true, false), commands)
    }

    @Test fun recognitionWorksWithoutACallAndDetectsBothAxes() {
        start()
        motion(false)
        assertTrue(latest.nods > 0)
        assertEquals(0, latest.shakes)
        assertEquals("NOD", latest.gesture)
        at += 1000
        motion(true)
        assertTrue(latest.shakes > 0)
        assertEquals("SHAKE", latest.gesture)
        assertEquals(listOf(true), commands) // No ANC/audio/call command is possible here.
        assertTrue(reports.zipWithNext().all { (a, b) -> b.sequence > a.sequence })
    }

    @Test fun packetCounterUsesFullRateButTelemetryIsThrottled() {
        start()
        repeat(1000) { at++; preview.onPacket(packet(), at) }
        assertEquals(1000, latest.packets)
        assertEquals(11, reports.size) // Start + 10 updates, not 1000 IPC messages.
    }

    @Test fun heartbeatLossExpiresEvenWithContinuingSensorData() {
        start()
        repeat(70) { at += 100; preview.onPacket(packet(), at) }
        assertFalse(preview.active)
        assertEquals("expired", latest.state)
        assertNull(latest.sample)
        assertEquals(listOf(true, false), commands)
        preview.command("test", "heartbeat", 8000)
        start() // Delayed duplicate cannot resurrect the old session.
        assertFalse(preview.active)
    }

    @Test fun renewedLeaseStillHasAHardSessionLimit() {
        start()
        repeat(120) {
            at += 1000
            preview.command("test", "heartbeat", at)
            preview.onPacket(packet(), at)
        }
        assertEquals("timeout", latest.state)
        assertFalse(preview.active)
    }

    @Test fun silenceAfterReceivingDataAlsoStopsTheStream() {
        start()
        at = 100
        preview.onPacket(packet(), at)
        preview.command("test", "heartbeat", 5000)
        preview.tick(5100)
        assertEquals("no_data", latest.state)
        assertNull(latest.sample)
    }

    @Test fun oldCommandsCannotStopRenewOrRecenterANewSession() {
        start("first")
        at = 100
        start("second")
        val before = reports.size
        preview.command("first", "stop", 200)
        preview.command("first", "center", 200)
        preview.command("first", "heartbeat", 7000)
        assertTrue(preview.active)
        assertEquals(before, reports.size)
        // Keep real samples alive so expiry is specifically due to the stale lease.
        for (time in 1100L..6100L step 1000) preview.onPacket(packet(), time)
        preview.tick(7100)
        assertEquals("expired", latest.state)
    }

    @Test fun recenterDiscardsCalibrationAndRecognitionHistoryButKeepsCounts() {
        start()
        motion(false)
        val count = latest.nods
        preview.command("test", "center", at)
        assertEquals("calibrating", latest.state)
        assertNull(latest.sample)
        assertEquals("", latest.gesture)
        at += 100
        preview.onPacket(packet(o2 = 3000), at)
        assertFalse(latest.sample!!.calibrated)
        assertEquals(count, latest.nods)
    }

    @Test fun realCallPreemptsPreviewAndLateCleanupCannotStopCallTracking() {
        start()
        assertTrue(stream.acquire(HeadTrackingStream.Owner.CALL))
        assertEquals("interrupted", latest.state)
        assertFalse(preview.active)
        assertEquals(listOf(true, false, true), commands)
        preview.command("test", "stop", 100)
        preview.stop("disconnected")
        assertEquals(listOf(true, false, true), commands)
        start("new")
        assertEquals("unavailable", latest.state)
        stream.release(HeadTrackingStream.Owner.CALL)
        assertEquals(listOf(true, false, true, false), commands)
    }

    @Test fun prerequisitesNeverSendStartAndFailedWritesDoNotOwnTheStream() {
        start("one", connected = false)
        assertEquals("disconnected", latest.state)
        start("two", supported = false)
        assertEquals("unsupported", latest.state)
        start("three", worn = false)
        assertEquals("not_worn", latest.state)
        start("four", inCall = true)
        assertEquals("interrupted", latest.state)
        assertTrue(commands.isEmpty())
        writesAccepted = false
        start("five")
        assertEquals("unavailable", latest.state)
        assertFalse(preview.active)
        writesAccepted = true
        assertTrue(stream.acquire(HeadTrackingStream.Owner.CALL))
    }

    @Test fun stopIsIdempotentAndNewSessionResetsResults() {
        start()
        motion(false)
        preview.command("test", "stop", at)
        preview.stop("disconnected")
        assertEquals(listOf(true, false), commands)
        assertEquals("stopped", latest.state)
        assertNull(latest.sample)
        start("again")
        assertEquals(0, latest.nods)
        assertEquals(0, latest.packets)
        assertEquals("", latest.gesture)
    }

    @Test fun failedStopIsNotReportedAsStoppedAndKeepsOwnershipUntilRecovery() {
        start()
        writesAccepted = false
        preview.command("test", "stop", 100)
        assertFalse(preview.active)
        assertEquals("stop_failed", latest.state)
        assertTrue(stream.stopPending)
        assertFalse(stream.acquire(HeadTrackingStream.Owner.CALL))
        writesAccepted = true
        assertTrue(stream.retryStop())
        preview.tick(1000)
        assertEquals("stopped", latest.state)
        assertFalse(stream.stopPending)
    }

    @Test fun movementDuringCalibrationNeedsTenNewStableSamples() {
        val pose = HeadPose()
        repeat(20) { assertFalse(pose.process(packet(o2 = it * 1000))!!.calibrated) }
        repeat(9) { assertFalse(pose.process(packet(o2 = 0))!!.calibrated) }
        assertTrue(pose.process(packet(o2 = 0))!!.calibrated)
    }

    @Test fun signedBoundaryJumpInvalidatesCalibrationRatherThanInventingAFullRotation() {
        val pose = HeadPose()
        repeat(10) { pose.process(packet(o2 = 32760, o3 = 32760)) }
        val sample = pose.process(packet(o2 = -32760, o3 = -32760))!!
        assertFalse(sample.calibrated)
        assertEquals(0f, sample.pitch, 0f)
        assertEquals(0f, sample.yaw, 0f)
    }
}
