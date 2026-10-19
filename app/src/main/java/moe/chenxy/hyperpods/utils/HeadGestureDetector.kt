/*
 * Adapted from LibrePods v1.0.4 utils/GestureDetector.kt.
 * Copyright (C) 2025 LibrePods contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software, distributed without any warranty; you may
 * redistribute it and/or modify it under the GNU General Public License,
 * version 3 or (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package moe.chenxy.hyperpods.utils

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** LibrePods' three-sample smoothing, extremes, speed and confidence recognition,
 * without its service/UI/audio dependencies or polling coroutine. Feed only during
 * a ringing-call or explicit preview window. All calls are serialized by the connection owner.
 */
class HeadGestureDetector {
    enum class Gesture { NOD, SHAKE }
    data class Orientation(val horizontal: Int, val vertical: Int)

    companion object {
        fun orientation(packet: ByteArray): Orientation? {
            if (packet.size < 70 || !packet.take(6).toByteArray()
                    .contentEquals(byteArrayOf(4, 0, 4, 0, 0x17, 0))) return null
            val data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
            return Orientation(data.getShort(51).toInt(), data.getShort(53).toInt())
                .takeIf { abs(it.horizontal) <= 6000 && abs(it.vertical) <= 6000 }
        }
    }

    private data class Extreme(val value: Double, val at: Long)
    private class Axis {
        val smoothing = ArrayDeque<Double>()
        val values = ArrayDeque<Double>()
        val extremes = ArrayDeque<Extreme>()
        var increasing: Boolean? = null

        fun add(value: Int, at: Long) {
            smoothing.addLast(value.toDouble())
            if (smoothing.size > 3) smoothing.removeFirst()
            values.addLast(smoothing.average())
            if (values.size > 100) values.removeFirst()
            while (extremes.isNotEmpty() && at - extremes.first().at > 3000) extremes.removeFirst()
            if (values.size < 4) return
            val recent = values.takeLast(4)
            val mean = recent.average()
            val variance = recent.map { (it - mean) * (it - mean) }.average()
            val threshold = max(50.0, min(150.0, variance / 3))
            val previous = recent[2]
            val current = recent[3]
            val rising = increasing ?: (current > previous)
            if (rising && current < previous - threshold || !rising && current > previous + threshold) {
                if (abs(previous) > 400) {
                    extremes.addLast(Extreme(previous, at))
                    if (extremes.size > 8) extremes.removeFirst()
                }
                increasing = !rising
            } else increasing = rising
        }

        fun confidence(other: Axis): Double {
            if (extremes.size < 3) return 0.0
            val intervals = extremes.zipWithNext { a, b -> (b.at - a.at).toDouble() }.takeLast(5)
            val average = intervals.average()
            val required = if (average < 300) 4 else 3
            if (extremes.size < required || average <= 0) return 0.0
            val recent = extremes.takeLast(required)
            // Do not treat repeated turns in one direction or mixed-axis movement
            // as yes/no. The upstream confidence formula alone can admit those.
            if (!recent.zipWithNext().all { (a, b) -> (a.value > 0) != (b.value > 0) }) return 0.0
            val amplitude = recent.map { abs(it.value) }.average()
            val axisAmplitude = values.takeLast(required * 2).map { abs(it) }.average()
            val otherAmplitude = other.values.takeLast(required * 2).map { abs(it) }.average()
            if (otherAmplitude.isFinite() && axisAmplitude <= otherAmplitude * 1.2) return 0.0
            val amplitudeFactor = min(1.0, amplitude / 600)
            val rhythm = max(0.0, 1 - min(1.0, intervals.map {
                val difference = it / average - 1
                difference * difference
            }.average() / 0.5))
            val isolation = min(1.0, amplitude / (otherAmplitude + 0.1) * 1.2)
            return amplitudeFactor * 0.4 + rhythm * 0.2 + 0.2 + isolation * 0.2
        }
    }

    private var horizontal = Axis()
    private var vertical = Axis()
    private var lastAt: Long? = null

    fun reset() {
        horizontal = Axis()
        vertical = Axis()
        lastAt = null
    }

    fun process(orientation: Orientation, at: Long): Gesture? {
        if (abs(orientation.horizontal.toLong()) > 6000 || abs(orientation.vertical.toLong()) > 6000) {
            reset()
            return null
        }
        // Never combine stale samples from a previous gesture, stalled stream or call.
        if (lastAt?.let { at <= it || at - it > 500 } == true) reset()
        lastAt = at
        horizontal.add(orientation.horizontal, at)
        vertical.add(orientation.vertical, at)
        val gesture = when {
            vertical.confidence(horizontal) >= 0.7 -> Gesture.NOD
            horizontal.confidence(vertical) >= 0.7 -> Gesture.SHAKE
            else -> null
        }
        if (gesture != null) reset()
        return gesture
    }
}
