package moe.chenxy.hyperpods.utils

import moe.chenxy.hyperpods.utils.data.BatteryParams

/** Collect newly triggered components, but use their latest readings when the short delay ends. */
class BatteryReminderBatch {
    private val pending = linkedSetOf<Int>()
    private var latest: BatteryParams? = null

    fun update(params: BatteryParams, settings: LowBatterySettings, alerts: List<Int>): Boolean {
        latest = params
        val active = LowBatteryReminder.lowComponents(params, settings).toSet()
        pending.retainAll(active)
        pending.addAll(alerts.filter { it in active })
        return pending.isNotEmpty()
    }

    fun take(): Pair<BatteryParams, List<Int>>? {
        val params = latest ?: return null
        val components = pending.toList()
        pending.clear()
        return if (components.isEmpty()) null else params to components
    }

    fun clear() {
        pending.clear()
        latest = null
    }
}
