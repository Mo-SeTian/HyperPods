package moe.chenxy.hyperpods.utils

import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.PodBatteryParams
import org.junit.Assert.*
import org.junit.Test

class PodsIslandDataTest {
    @Test fun distinctEarLevelsUseSupportedLeftAndRightModules() {
        val island = PodsIslandData.build(BatteryParams(
            left = PodBatteryParams(85, isConnected = true),
            right = PodBatteryParams(60, isConnected = true),
            case = PodBatteryParams(99, isConnected = true, isInCase = true)
        ))
        val big = island.getJSONObject("bigIslandArea")
        val left = big.getJSONObject("imageTextInfoLeft")
        val right = big.getJSONObject("imageTextInfoRight")
        // Match the firmware's IslandTemplateFactory and ImageTextView2Holder.
        assertEquals(1, left.getInt("type"))
        assertEquals(2, right.getInt("type"))
        assertEquals("85%", left.getJSONObject("textInfo").getString("title"))
        assertEquals("60%", right.getJSONObject("textInfo").getString("title"))
        assertEquals(PodsIslandData.LEFT_ICON, left.getJSONObject("picInfo").getString("pic"))
        assertEquals(PodsIslandData.RIGHT_ICON, right.getJSONObject("picInfo").getString("pic"))
        assertFalse(island.getBoolean("maxSize"))
        assertFalse(island.toString().contains("99%"))
    }

    @Test fun unknownEarKeepsItsSideWithoutInventingZero() {
        val big = PodsIslandData.build(BatteryParams(
            left = null, right = PodBatteryParams(0, isConnected = true)
        )).getJSONObject("bigIslandArea")
        assertEquals("--", big.getJSONObject("imageTextInfoLeft").getJSONObject("textInfo").getString("title"))
        assertEquals("0%", big.getJSONObject("imageTextInfoRight").getJSONObject("textInfo").getString("title"))
        assertEquals("--", PodsIslandData.batteryText(PodBatteryParams(255, isConnected = true)))
        assertEquals("--", PodsIslandData.batteryText(PodBatteryParams(80)))
    }
}
