package moe.chenxy.hyperpods.utils

import android.content.res.Resources
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.pods.BatteryComponent
import moe.chenxy.hyperpods.utils.data.BatteryParams

object BatteryReminderText {
    fun format(resources: Resources, params: BatteryParams, components: List<Int>): String {
        if (components.isEmpty()) return ""
        val batteries = LowBatteryReminder.components(params)
        val readings = components.mapNotNull { component ->
            val battery = batteries[component]?.takeIf { it.battery in 0..100 } ?: return@mapNotNull null
            val label = when (component) {
                BatteryComponent.LEFT -> R.string.dashboard_left
                BatteryComponent.RIGHT -> R.string.dashboard_right
                BatteryComponent.CASE -> R.string.dashboard_case
                else -> return@mapNotNull null
            }
            resources.getString(label) + " ${battery.battery}%"
        }
        if (readings.isEmpty()) return ""
        val critical = components.any { batteries[it]?.battery?.let { level -> level in 0..LowBatteryReminder.CRITICAL } == true }
        return resources.getString(if (critical) R.string.battery_reminder_critical_message else R.string.battery_reminder_low_message,
            readings.joinToString(" · "))
    }
}
