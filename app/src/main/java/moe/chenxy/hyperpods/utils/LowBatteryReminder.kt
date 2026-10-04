package moe.chenxy.hyperpods.utils

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import moe.chenxy.hyperpods.pods.BatteryComponent
import moe.chenxy.hyperpods.pods.BatteryStatus
import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.PodBatteryParams

@Parcelize
data class LowBatterySettings(
    val earsEnabled: Boolean = true,
    val earsThreshold: Int = 20,
    val caseEnabled: Boolean = true,
    val caseThreshold: Int = 20,
) : Parcelable {
    val valid: Boolean get() = earsThreshold in LowBatteryReminder.thresholds && caseThreshold in LowBatteryReminder.thresholds
    fun enabled(component: Int): Boolean = if (component == BatteryComponent.CASE) caseEnabled else earsEnabled
    fun threshold(component: Int): Int = if (component == BatteryComponent.CASE) caseThreshold else earsThreshold
}

/** Decisions use fresh reports only; a retained NEED_AGAIN value is never an alert or a recharge. */
object LowBatteryReminder {
    val thresholds = (10..50 step 5).toList()
    const val CRITICAL = 10
    private const val LOW_SENT = 1
    private const val CRITICAL_SENT = 2
    data class Decision(val component: Int, val previous: Int, val next: Int, val alert: Boolean)

    fun components(params: BatteryParams): Map<Int, PodBatteryParams?> = linkedMapOf(
        BatteryComponent.LEFT to params.left, BatteryComponent.RIGHT to params.right, BatteryComponent.CASE to params.case)

    fun lowComponents(params: BatteryParams, settings: LowBatterySettings): List<Int> =
        components(params).filter { (component, battery) -> settings.valid && settings.enabled(component) &&
            battery?.isConnected == true && battery.rawStatus == BatteryStatus.NOT_CHARGING && !battery.isCharging &&
            battery.battery in 0..settings.threshold(component) }.keys.toList()

    fun evaluate(params: BatteryParams, fresh: IntArray, settings: LowBatterySettings,
        previousState: (Int) -> Int): List<Decision> {
        if (!settings.valid) return emptyList()
        return components(params).mapNotNull { (component, battery) ->
            if (component !in fresh || battery?.isConnected != true || battery.battery !in 0..100 ||
                battery.rawStatus !in listOf(BatteryStatus.CHARGING, BatteryStatus.NOT_CHARGING)) return@mapNotNull null
            val previous = previousState(component) and 3
            var next = previous
            val threshold = settings.threshold(component)
            // Rearm each level independently, including while reminders are disabled or charging.
            if (battery.battery >= threshold + 5) next = next and LOW_SENT.inv()
            if (battery.battery >= CRITICAL + 5) next = next and CRITICAL_SENT.inv()
            var alert = false
            if (settings.enabled(component) && battery.rawStatus == BatteryStatus.NOT_CHARGING && !battery.isCharging) {
                if (battery.battery <= threshold && next and LOW_SENT == 0) {
                    next = next or LOW_SENT
                    alert = true
                }
                if (battery.battery <= CRITICAL && next and CRITICAL_SENT == 0) {
                    next = next or CRITICAL_SENT
                    alert = true
                }
            }
            Decision(component, previous, next, alert)
        }
    }
}
