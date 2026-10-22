package moe.chenxy.hyperpods.utils

import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.PodBatteryParams
import org.junit.Assert.*
import org.junit.Test

class PodsExpandedTextTest {
    private fun text(battery: BatteryParams, warning: String = "") =
        PodsIslandData.expandedText(battery, "左", "右", "充电盒", warning)

    private fun battery(leftInCase: Boolean = true, rightInCase: Boolean = false,
        case: PodBatteryParams? = PodBatteryParams(96, isConnected = true)) = BatteryParams(
        left = PodBatteryParams(100, isConnected = true, isInCase = leftInCase),
        right = PodBatteryParams(85, isConnected = true, isInCase = rightInCase), case = case)

    @Test fun caseBatteryReusesTheFooterInsteadOfAddingATopRow() {
        val withoutCase = text(battery(case = null))
        for ((left, right) in listOf(true to false, false to true, true to true)) {
            val withCase = text(battery(left, right))
            assertEquals(withoutCase.title, withCase.title)
            assertEquals(withoutCase.content, withCase.content)
            assertEquals("", withCase.content)
            assertEquals("充电盒：96%", withCase.hintContent)
            assertFalse(withCase.hintContent.contains('\n'))
        }
        assertEquals("HyperPods", withoutCase.hintContent)
    }

    @Test fun zeroAndFullCaseBatteryAndChargingStayOnOneFooterLine() {
        for (level in listOf(0, 100)) {
            val result = text(battery(case = PodBatteryParams(level, isConnected = true, isCharging = true)))
            assertEquals("充电盒：$level% ⚡", result.hintContent)
            assertEquals("", result.content)
        }
    }

    @Test fun absentStaleOrInvalidCaseReadingsDoNotLeakIntoTheCard() {
        val cases = listOf(battery(false, false), battery(case = null),
            battery(case = PodBatteryParams(96, isConnected = false)),
            battery(case = PodBatteryParams(255, isConnected = true)),
            battery(case = PodBatteryParams(-1, isConnected = true)))
        for (battery in cases) {
            val result = text(battery)
            assertEquals("HyperPods", result.hintContent)
            assertEquals("", result.content)
        }
    }

    @Test fun warningAndIndependentEarLevelsAreNotLostWhenTheCaseAppears() {
        val result = text(battery(), "右耳电量低")
        assertEquals("左：100% | 右：85%", result.title)
        assertEquals("右耳电量低", result.content)
        assertEquals("充电盒：96%", result.hintContent)
    }

    @Test fun unknownEarsAndPerEarChargingKeepTheirExistingMeaning() {
        val result = text(BatteryParams(left = null,
            right = PodBatteryParams(0, isConnected = true, isCharging = true)))
        assertEquals("左：-- | 右：0% ⚡", result.title)
        assertEquals("HyperPods", result.hintContent)
    }
}
