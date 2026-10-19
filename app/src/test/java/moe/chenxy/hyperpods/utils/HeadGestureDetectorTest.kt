package moe.chenxy.hyperpods.utils

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class HeadGestureDetectorTest {
    private val detector = HeadGestureDetector()
    private fun motion(horizontal: Boolean, amplitude: Int = 1200, period: Int = 24, start: Long = 0): HeadGestureDetector.Gesture? {
        for (index in 0..150) {
            val value = (amplitude * sin(2 * PI * index / period)).toInt()
            val result = detector.process(HeadGestureDetector.Orientation(if (horizontal) value else 0,
                if (horizontal) 0 else value), start + index * 20L)
            if (result != null) return result
        }
        return null
    }

    @Test fun verticalOscillationIsANodAndHorizontalIsAShake() {
        assertEquals(HeadGestureDetector.Gesture.NOD, motion(false))
        detector.reset()
        assertEquals(HeadGestureDetector.Gesture.SHAKE, motion(true))
    }

    @Test fun slowAndFastDeliberateGesturesAreRecognized() {
        for (period in listOf(16, 24, 40)) {
            detector.reset()
            assertEquals("period=$period", HeadGestureDetector.Gesture.NOD, motion(false, period = period))
        }
    }

    @Test fun smallMotionStationaryAndOneDirectionTurnsNeverDecideACall() {
        assertNull(motion(false, amplitude = 150))
        detector.reset()
        for (index in 0..200) {
            assertNull(detector.process(HeadGestureDetector.Orientation(500, 500), index * 20L))
        }
        detector.reset()
        for (index in 0..100) assertNull(detector.process(HeadGestureDetector.Orientation(index * 50, 0), index * 20L))
    }

    @Test fun diagonalMovementIsNotMisclassifiedAsYesOrNo() {
        for (index in 0..200) {
            val value = (1200 * sin(2 * PI * index / 24)).toInt()
            assertNull(detector.process(HeadGestureDetector.Orientation(value, value), index * 20L))
        }
    }

    @Test fun missingPacketsAndCallResetDoNotCombineOldHalfGestures() {
        for (index in 0..20) {
            val value = (1200 * sin(2 * PI * index / 24)).toInt()
            assertNull(detector.process(HeadGestureDetector.Orientation(0, value), index * 20L))
        }
        for (index in 0..20) {
            val value = (1200 * sin(2 * PI * index / 24)).toInt()
            assertNull(detector.process(HeadGestureDetector.Orientation(0, value), 5000 + index * 20L))
        }
        detector.reset()
        assertNull(detector.process(HeadGestureDetector.Orientation(0, -1200), 6000))
    }

    @Test fun calibrationAndIntegerExtremesCannotGenerateAGesture() {
        for (value in listOf(6001, -6001, Int.MIN_VALUE, Int.MAX_VALUE))
            assertNull(detector.process(HeadGestureDetector.Orientation(value, value), 100))
    }

    @Test fun packetUsesSignedLittleEndianOrientationAtLibrePodsOffsets() {
        val packet = byteArrayOf(4, 0, 4, 0, 0x17, 0) + ByteArray(64)
        ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putShort(51, -1234).putShort(53, 2345)
        assertEquals(HeadGestureDetector.Orientation(-1234, 2345), HeadGestureDetector.orientation(packet))
        for (length in 0..69) assertNull(HeadGestureDetector.orientation(packet.copyOf(length)))
        assertNull(HeadGestureDetector.orientation(packet.copyOf().apply { this[4] = 4 }))
        assertNull(HeadGestureDetector.orientation(packet.copyOf().apply { this[5] = 1 }))
        ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putShort(51, 6001)
        assertNull(HeadGestureDetector.orientation(packet))
    }
}
