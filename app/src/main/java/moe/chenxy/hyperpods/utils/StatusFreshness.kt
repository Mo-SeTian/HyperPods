package moe.chenxy.hyperpods.utils

import moe.chenxy.hyperpods.pods.BatteryStatus
import moe.chenxy.hyperpods.utils.data.PodBatteryParams

/** Reports are event-driven. Age alone must not turn a valid value into a failure. */
object StatusFreshness {
    enum class State { UNKNOWN, REPORTED, RETAINED }

    fun battery(params: PodBatteryParams?, at: Long, connection: String?): State = when {
        params?.isConnected != true || params.battery !in 0..100 || at <= 0 -> State.UNKNOWN
        params.rawStatus == BatteryStatus.NEED_AGAIN || connection == "failed" -> State.RETAINED
        else -> State.REPORTED
    }

    fun wear(valid: Boolean, at: Long, connection: String?): State = when {
        !valid || at <= 0 -> State.UNKNOWN
        connection == "failed" -> State.RETAINED
        else -> State.REPORTED
    }
}
