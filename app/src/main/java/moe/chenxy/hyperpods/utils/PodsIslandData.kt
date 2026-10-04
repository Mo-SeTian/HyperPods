package moe.chenxy.hyperpods.utils

import android.view.Gravity
import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.PodBatteryParams
import org.json.JSONObject

object PodsIslandData {
    const val LEFT_ICON = "miui.focus.pic_left_pod"
    const val RIGHT_ICON = "miui.focus.pic_right_pod"

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
