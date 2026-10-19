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

/** Reverse-engineered relative estimates, not calibrated physical Euler angles.
 * Motion at 51/53 is kept separate from pose at 45/47. */
class HeadPose {
    data class Sample(val pitch: Float, val yaw: Float, val calibrated: Boolean,
        val horizontal: Int, val vertical: Int)
    private var samples = 0
    private var sum2 = 0L
    private var sum3 = 0L
    private var neutral2 = 0f
    private var neutral3 = 0f

    fun reset() {
        samples = 0
        sum2 = 0
        sum3 = 0
        neutral2 = 0f
        neutral3 = 0f
    }

    fun process(packet: ByteArray): Sample? {
        val motion = HeadGestureDetector.orientation(packet) ?: return null
        val data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val o2 = data.getShort(45).toInt()
        val o3 = data.getShort(47).toInt()
        if (samples < 10) {
            sum2 += o2
            sum3 += o3
            samples++
            neutral2 = sum2.toFloat() / samples
            neutral3 = sum3.toFloat() / samples
        }
        val d2 = o2 - neutral2
        val d3 = o3 - neutral3
        return Sample((d2 + d3) / 2f / 32000f * 180f,
            (d2 - d3) / 2f / 32000f * 180f, samples >= 10,
            motion.horizontal, motion.vertical)
    }
}
