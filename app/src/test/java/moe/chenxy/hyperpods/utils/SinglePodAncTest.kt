package moe.chenxy.hyperpods.utils

import org.junit.Assert.*
import org.junit.Test

class SinglePodAncTest {
    private fun active(): SinglePodAnc = SinglePodAnc().apply {
        assertFalse(onWear(2, true, 100))
        assertFalse(onMode(2, true, 100))
    }

    @Test fun preservesOnlyTheTwoToOneTransitionWithTheOptionEnabled() {
        assertTrue(active().onWear(1, true, 200))
        assertFalse(active().onWear(1, false, 200))
        assertFalse(active().onWear(0, true, 200))
        val session = active()
        assertFalse(session.onWear(0, true, 200))
        assertFalse(session.onWear(1, true, 300))
        assertFalse(session.onWear(1, true, 400))
        for (mode in listOf(1, 3, 4)) {
            val otherMode = SinglePodAnc()
            otherMode.onWear(2, true, 100)
            otherMode.onMode(mode, true, 100)
            assertFalse(otherMode.onWear(1, true, 200))
        }
    }

    @Test fun acceptsOnlyARecentFallbackBeforeTheWearReport() {
        val recent = active()
        recent.onMode(3, true, 200)
        assertTrue(recent.onWear(1, true, 1200))
        val stale = active()
        stale.onMode(3, true, 200)
        assertFalse(stale.onWear(1, true, 1201))
        val reversedClock = active()
        reversedClock.onMode(3, true, 200)
        assertFalse(reversedClock.onWear(1, true, 199))
    }

    @Test fun retriesALateFallbackOnceAndStopsAfterAnAncReport() {
        val session = active()
        assertTrue(session.onWear(1, true, 200))
        assertTrue(session.onMode(3, true, 1200))
        assertFalse(session.onMode(3, true, 1200))
        assertFalse(session.onMode(2, true, 1200))
        assertFalse(session.onMode(3, true, 1201))
        val stale = active()
        stale.onWear(1, true, 200)
        assertFalse(stale.onMode(3, true, 1201))
    }

    @Test fun explicitModeSelectionAndOptionChangesCancelRestoration() {
        val session = active()
        session.selectMode(3)
        // A stale ANC report must not replace an outstanding explicit choice.
        session.onMode(2, true, 150)
        assertFalse(session.onWear(1, true, 200))
        assertFalse(session.onMode(3, true, 201))
        assertFalse(session.onMode(2, true, 202))
        assertFalse(session.onMode(3, true, 203))
        val disabled = active()
        disabled.onWear(1, true, 200)
        assertFalse(disabled.onMode(3, false, 201))
        assertFalse(disabled.onMode(3, true, 202))
    }

    @Test fun duplicateWearReportsFailureAndResetCannotReplayARestoration() {
        val session = active()
        assertTrue(session.onWear(1, true, 200))
        assertFalse(session.onWear(1, true, 201))
        assertFalse(session.onMode(3, true, 202))
        val failed = active()
        failed.onWear(1, true, 200)
        failed.requestFailed()
        assertFalse(failed.onMode(3, true, 201))
        val reset = active()
        reset.onWear(1, true, 200)
        reset.reset()
        assertFalse(reset.onMode(3, true, 201))
        assertFalse(reset.onWear(1, true, 202))
        val cancelled = active()
        cancelled.onWear(1, true, 200)
        cancelled.cancel()
        assertFalse(cancelled.onMode(3, true, 201))
    }
}
