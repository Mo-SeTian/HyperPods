package moe.chenxy.hyperpods.utils

import android.view.Gravity
import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.PodBatteryParams
import org.json.JSONObject

object PodsIslandData {
    const val LEFT_ICON = "miui.focus.pic_left_pod"
    const val RIGHT_ICON = "miui.focus.pic_right_pod"

    fun displayName(alias: String?, name: String?): String =
        alias?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() } ?: "AirPods"

    fun modelForIcons(modelNumber: String?): AirPodsBase? =
        AirPodsModels.getModelByModelNumber(modelNumber.orEmpty())

    fun contentGravity(icon: String?, gravity: Int): Int? {
        val horizontal = when (icon) {
            LEFT_ICON -> Gravity.END
            RIGHT_ICON -> Gravity.START
            else -> return null
        }
        return (gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK.inv()) or horizontal
    }

    fun batteryText(battery: PodBatteryParams?): String =
        if (battery?.isConnected == true && battery.battery in 0..100) "${battery.battery}%" else "--"

    data class ExpandedText(val title: String, val content: String, val hintContent: String)

    fun expandedText(battery: BatteryParams, leftLabel: String, rightLabel: String,
        caseLabel: String, warning: String = ""): ExpandedText {
        fun ear(label: String, value: PodBatteryParams?) = "$label：${batteryText(value)}" +
            (if (value?.isCharging == true) " ⚡" else "")
        val case = battery.case
        val caseText = if (case?.isConnected == true && case.battery in 0..100 &&
            (battery.left?.isInCase == true || battery.right?.isInCase == true))
            "$caseLabel：${case.battery}%" + (if (case.isCharging) " ⚡" else "")
        else "HyperPods"
        // Reuse the footer's existing subtitle instead of growing the top module
        // when the case appears. Keep the system template and window size native.
        return ExpandedText("${ear(leftLabel, battery.left)} | ${ear(rightLabel, battery.right)}",
            warning, caseText)
    }

    fun build(battery: BatteryParams): JSONObject {
        fun ear(type: Int, icon: String, value: PodBatteryParams?) = JSONObject()
            .put("type", type)
            .put("picInfo", JSONObject().put("type", 1).put("pic", icon))
            .put("textInfo", JSONObject().put("title", batteryText(value)))

        // HyperOS 4 IslandTemplateFactory accepts type 1 on the left only.
        // Right type 2 is text followed by an icon (IslandImageTextView2Holder).
        return JSONObject()
            .put("islandProperty", 1)
            .put("islandTimeout", 3600)
            .put("maxSize", false)
            .put("bigIslandArea", JSONObject()
                .put("imageTextInfoLeft", ear(1, LEFT_ICON, battery.left))
                .put("imageTextInfoRight", ear(2, RIGHT_ICON, battery.right)))
            .put("smallIslandArea", JSONObject().put("picInfo", JSONObject()
                .put("type", 1).put("pic", "miui.focus.pic_mark_v2")))
    }
}
