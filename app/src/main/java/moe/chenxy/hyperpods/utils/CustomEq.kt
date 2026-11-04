package moe.chenxy.hyperpods.utils

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Native three-band wire format adapted from LibrePods 89356c0, data/CustomEq.kt.
 * https://github.com/librepods-org/librepods (GPL-3.0). Values are not Hz or dB. */
@Parcelize
data class CustomEq(val state: Int = RECOMMENDED, val low: Int = 50, val mid: Int = 50, val high: Int = 50) : Parcelable {
    init {
        require(state == RECOMMENDED || state == CUSTOM)
        require(low in 0..100 && mid in 0..100 && high in 0..100)
    }

    fun toPacket(): ByteArray = byteArrayOf(OPCODE, 0, 5, 0, 1, state.toByte(), low.toByte(), mid.toByte(), high.toByte())

    companion object {
        const val OPCODE: Byte = 0x63
        const val RECOMMENDED = 1
        const val CUSTOM = 2

        /** Ignore request/unknown variants instead of inventing a default report. */
        fun fromPacket(packet: ByteArray): CustomEq? {
            if (packet.size != 13 || !packet.take(6).toByteArray().contentEquals(byteArrayOf(4, 0, 4, 0, OPCODE, 0)) ||
                packet[6] != 5.toByte() || packet[7] != 0.toByte() || packet[8] != 1.toByte()) return null
            val values = packet.slice(9..12).map { it.toInt() and 0xff }
            if (values[0] !in RECOMMENDED..CUSTOM || values.drop(1).any { it !in 0..100 }) return null
            return CustomEq(values[0], values[1], values[2], values[3])
        }

        fun supportsModel(model: AirPodsBase?): Boolean = model is AirPods4 || model is AirPods4ANC ||
            model is AirPods5 || model is AirPods5WCC || model is AirPodsPro2Lightning ||
            model is AirPodsPro2USBC || model is AirPodsPro3

        // LibrePods android/rewrite (AppleSettingsScreen.kt, 3d4d270, line 464)
        // requires version3 to start with generation 9 or newer. Numeric AACP
        // identifiers such as 9441861 use their first digit, not the whole number.
        // Also accept human-readable versions such as 9A348 and future 10A100.
        // A report or an earlier allowed write keeps this connection's known EQ
        // state usable while metadata refreshes. New connections start without it.
        fun available(model: AirPodsBase?, firmware: String, hasEqState: Boolean): Boolean {
            if (hasEqState) return true
            if (!supportsModel(model)) return false
            val version = firmware.trim()
            val generation = if (version.isNotEmpty() && version.all { it in '0'..'9' }) {
                version.first().digitToInt()
            } else {
                Regex("^(\\d+)[A-Za-z]\\d+[A-Za-z]?$").matchEntire(version)
                    ?.groupValues?.get(1)?.toIntOrNull()
            }
            return generation != null && generation >= 9
        }
    }
}

/** The connection identity prevents a delayed slider write reaching another session. */
@Parcelize
data class CustomEqState(val value: CustomEq? = null, val unconfirmed: Boolean = false,
    val revision: Long = 0, val session: Long = 0) : Parcelable
