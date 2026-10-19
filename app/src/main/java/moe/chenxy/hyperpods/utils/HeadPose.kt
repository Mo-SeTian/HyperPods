/*
 * Relative pose calculation adapted from LibrePods v1.0.4 utils/HeadOrientation.kt.
 * Copyright (C) 2025 LibrePods contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Distributed without warranty under GPL version 3 or later.
 * See <https://www.gnu.org/licenses/>.
 */
package moe.chenxy.hyperpods.utils

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Reverse-engineered relative estimates, not calibrated physical Euler angles.
 * Motion at 51/53 is kept separate from pose at 45/47. */
class HeadPose {
    data class Sample(val pitch: Float, val yaw: Float, val calibrated: Boolean,
        val horizontal: Int, val vertical: Int)
    private val calibration = ArrayDeque<Pair<Int, Int>>()
    private var calibrated = false
    private var neutral2 = 0f
    private var neutral3 = 0f
    private var previous: Pair<Int, Int>? = null

    fun reset() {
        calibration.clear()
        calibrated = false
        neutral2 = 0f
        neutral3 = 0f
        previous = null
    }

    fun process(packet: ByteArray): Sample? {
        val motion = HeadGestureDetector.orientation(packet) ?: return null
        val data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val o2 = data.getShort(45).toInt()
        val o3 = data.getShort(47).toInt()
        if (previous?.let { abs(o2 - it.first) > 32768 || abs(o3 - it.second) > 32768 } == true) {
            // The reverse-engineered fields are not proven cyclic Euler angles.
            // Recalibrate on a signed-boundary discontinuity, rather than inventing a wrap formula.
            reset()
        }
        previous = o2 to o3
        if (!calibrated) {
            calibration.addLast(o2 to o3)
            while (calibration.maxOf { it.first } - calibration.minOf { it.first } > 640 ||
                calibration.maxOf { it.second } - calibration.minOf { it.second } > 640) calibration.removeFirst()
            if (calibration.size >= 10) {
                neutral2 = calibration.map { it.first }.average().toFloat()
                neutral3 = calibration.map { it.second }.average().toFloat()
                calibrated = true
                calibration.clear()
            } else return Sample(0f, 0f, false, motion.horizontal, motion.vertical)
        }
        val d2 = o2 - neutral2
        val d3 = o3 - neutral3
        return Sample((d2 + d3) / 2f / 32000f * 180f,
            (d2 - d3) / 2f / 32000f * 180f, calibrated,
            motion.horizontal, motion.vertical)
    }
}
