package moe.chenxy.hyperpods.utils

import kotlinx.coroutines.Job
import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.PodBatteryParams
import moe.chenxy.hyperpods.utils.miuiStrongToast.MiuiStrongToastUtil
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Verify deferred-toast cancellation without calling HyperOS services on the JVM. */
class CaseBatteryToastTest {
    private val toast = MiuiStrongToastUtil
    private fun field(name: String) = toast.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun battery(inCase: Boolean, caseConnected: Boolean = true, level: Int = 80) = BatteryParams(
        PodBatteryParams(isConnected = true, isInCase = inCase), PodBatteryParams(),
        PodBatteryParams(battery = level, isConnected = caseConnected))

    @After fun cleanUp() = toast.cancelCaseBatteryToast()

    @Test fun removalFromCaseCancelsTheDeferredCaseToast() {
        val job = Job()
        field("caseToastJob").set(toast, job)
        toast.updateCaseBatteryState(battery(false))
        assertTrue(job.isCancelled)
        assertNull(field("caseToastJob").get(toast))
    }

    @Test fun unavailableCaseCancelsTheDeferredCaseToast() {
        val job = Job()
        field("caseToastJob").set(toast, job)
        toast.updateCaseBatteryState(battery(true, false))
        assertTrue(job.isCancelled)
    }

    @Test fun laterBatteryUpdatesReplaceTheSnapshotWithoutCancellingAVisibleCase() {
        val job = Job()
        field("caseToastJob").set(toast, job)
        toast.updateCaseBatteryState(battery(true))
        val updated = battery(true, level = 0)
        toast.updateCaseBatteryState(updated)
        assertSame(updated, field("latestBattery").get(toast))
        assertTrue(job.isActive)
    }

    @Test fun disconnectCancelsTheJobAndForgetsItsBatterySnapshot() {
        toast.updateCaseBatteryState(battery(true))
        val job = Job()
        field("caseToastJob").set(toast, job)
        toast.cancelCaseBatteryToast()
        assertTrue(job.isCancelled)
        assertNull(field("caseToastJob").get(toast))
        assertNull(field("latestBattery").get(toast))
    }
}
