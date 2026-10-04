package moe.chenxy.hyperpods.utils

import moe.chenxy.hyperpods.pods.BatteryComponent as C
import moe.chenxy.hyperpods.pods.BatteryStatus as S
import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.PodBatteryParams
import org.junit.Assert.*
import org.junit.Test

class LowBatteryReminderTest {
    private val settings = LowBatterySettings()
    private val states = mutableMapOf<Int, Int>()
    private fun battery(level: Int, status: Int = S.NOT_CHARGING, available: Boolean = true) =
        PodBatteryParams(level, status == S.CHARGING, available, status)
    private fun evaluate(params: BatteryParams, fresh: IntArray = intArrayOf(C.LEFT, C.RIGHT, C.CASE),
        config: LowBatterySettings = settings) = LowBatteryReminder.evaluate(params, fresh, config) { states[it] ?: 0 }
    private fun apply(params: BatteryParams, fresh: IntArray = intArrayOf(C.LEFT, C.RIGHT, C.CASE),
        config: LowBatterySettings = settings): List<Int> {
        val decisions = evaluate(params, fresh, config)
        decisions.forEach { states[it.component] = it.next }
        return decisions.filter { it.alert }.map { it.component }
    }

    @Test fun eachEarIsComparedIndependentlyRatherThanAveraged() {
        val params = BatteryParams(battery(18), battery(80))
        assertEquals(listOf(C.LEFT), apply(params))
        assertEquals(listOf(C.LEFT), LowBatteryReminder.lowComponents(params, settings))
    }

    @Test fun simultaneousLowComponentsAreReturnedAsOneCombinedBatch() {
        assertEquals(listOf(C.LEFT, C.RIGHT, C.CASE), apply(BatteryParams(battery(20), battery(19), battery(15))))
    }

    @Test fun lowReadingsDoNotRepeatButTenPercentTriggersTheSecondLevel() {
        assertEquals(listOf(C.LEFT), apply(BatteryParams(left = battery(20))))
        for (level in listOf(20, 19, 19, 18, 11)) assertTrue(apply(BatteryParams(left = battery(level))).isEmpty())
        assertEquals(listOf(C.LEFT), apply(BatteryParams(left = battery(10))))
        for (level in listOf(10, 9, 5, 0)) assertTrue(apply(BatteryParams(left = battery(level))).isEmpty())
    }

    @Test fun firstReportAlreadyCriticalProducesOneWarningNotTwo() {
        assertEquals(listOf(C.LEFT), apply(BatteryParams(left = battery(0))))
        assertEquals(3, states[C.LEFT])
        assertTrue(apply(BatteryParams(left = battery(0))).isEmpty())
    }

    @Test fun aTenPercentThresholdCollapsesBothLevelsIntoOneWarning() {
        val config = settings.copy(earsThreshold = 10)
        assertTrue(apply(BatteryParams(left = battery(11)), config = config).isEmpty())
        assertEquals(listOf(C.LEFT), apply(BatteryParams(left = battery(10)), config = config))
        assertTrue(apply(BatteryParams(left = battery(9)), config = config).isEmpty())
    }

    @Test fun chargingDoesNotAlertButARecoveredFreshReadingRearmsTheNextCycle() {
        apply(BatteryParams(left = battery(20)))
        assertTrue(apply(BatteryParams(left = battery(21, S.CHARGING))).isEmpty())
        assertTrue(apply(BatteryParams(left = battery(24, S.CHARGING))).isEmpty())
        assertTrue(apply(BatteryParams(left = battery(20))).isEmpty())
        assertTrue(apply(BatteryParams(left = battery(25, S.CHARGING))).isEmpty())
        assertEquals(listOf(C.LEFT), apply(BatteryParams(left = battery(20))))
    }

    @Test fun criticalLevelHasItsOwnFivePointRecoveryMargin() {
        apply(BatteryParams(left = battery(10)))
        apply(BatteryParams(left = battery(14, S.CHARGING)))
        assertTrue(apply(BatteryParams(left = battery(10))).isEmpty())
        apply(BatteryParams(left = battery(15, S.CHARGING)))
        assertEquals(listOf(C.LEFT), apply(BatteryParams(left = battery(10))))
        assertTrue(apply(BatteryParams(left = battery(19))).isEmpty())
    }

    @Test fun unknownDisconnectedPendingAndOutOfRangeReadingsDoNotAlertOrRearm() {
        states[C.LEFT] = 3
        for (item in listOf(battery(-1), battery(101), battery(90, S.NEED_AGAIN), battery(90, S.DISCONNECTED),
            battery(10, S.NEED_AGAIN), battery(10, S.DISCONNECTED), battery(10, available = false))) {
            assertTrue(evaluate(BatteryParams(left = item)).isEmpty())
            assertTrue(LowBatteryReminder.lowComponents(BatteryParams(left = item), settings).isEmpty())
            assertEquals(3, states[C.LEFT])
        }
    }

    @Test fun partialReportsNeverTriggerOrRearmOtherCachedComponents() {
        val params = BatteryParams(battery(10), battery(10), battery(10))
        assertEquals(listOf(C.RIGHT), apply(params, intArrayOf(C.RIGHT)))
        assertNull(states[C.LEFT])
        assertNull(states[C.CASE])
        states[C.LEFT] = 3
        assertTrue(evaluate(BatteryParams(left = battery(90)), intArrayOf(C.RIGHT)).isEmpty())
        assertEquals(3, states[C.LEFT])
    }

    @Test fun wearRenameAndSettingsUpdatesWithNoFreshComponentsDoNotAlert() {
        assertTrue(evaluate(BatteryParams(battery(10), battery(10), battery(10)), intArrayOf()).isEmpty())
    }

    @Test fun earsAndCaseHaveIndependentSwitchesAndThresholds() {
        val params = BatteryParams(battery(25), battery(25), battery(25))
        val caseOnly = settings.copy(earsEnabled = false, caseThreshold = 30)
        assertEquals(listOf(C.CASE), apply(params, config = caseOnly))
        val earsOnly = settings.copy(earsThreshold = 30, caseEnabled = false)
        assertEquals(listOf(C.LEFT, C.RIGHT), apply(params, config = earsOnly))
    }

    @Test fun aNewConnectionDoesNotRequireAHighToLowTransitionBeforeTheFirstReminder() {
        assertEquals(listOf(C.CASE), apply(BatteryParams(case = battery(18)), intArrayOf(C.CASE)))
        assertTrue(apply(BatteryParams(case = battery(18)), intArrayOf(C.CASE)).isEmpty())
    }

    @Test fun chargingCanRearmWhileRemindersAreDisabledWithoutProducingAlerts() {
        states[C.LEFT] = 3
        assertTrue(apply(BatteryParams(left = battery(60, S.CHARGING)), config = settings.copy(earsEnabled = false)).isEmpty())
        assertEquals(0, states[C.LEFT])
        assertEquals(listOf(C.LEFT), apply(BatteryParams(left = battery(19))))
    }

    @Test fun unavailableOfflineCaseDoesNotReuseItsPreviousLowReading() {
        assertTrue(evaluate(BatteryParams(case = battery(10, S.DISCONNECTED))).isEmpty())
        assertTrue(evaluate(BatteryParams(case = battery(10, S.NEED_AGAIN))).isEmpty())
    }

    @Test fun invalidSettingsAreRejectedAndDoNotProduceNotifications() {
        for (value in listOf(-1, 0, 5, 21, 55, 100)) {
            val config = settings.copy(earsThreshold = value)
            assertFalse(config.valid)
            assertTrue(evaluate(BatteryParams(left = battery(10)), config = config).isEmpty())
        }
        for (value in LowBatteryReminder.thresholds) assertTrue(settings.copy(caseThreshold = value).valid)
    }
}
