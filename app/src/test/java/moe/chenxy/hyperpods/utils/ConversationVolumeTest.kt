package moe.chenxy.hyperpods.utils

import org.junit.Assert.*
import org.junit.Test

class ConversationVolumeTest {
    private var volume = 10
    private var playing = true
    private val controller = ConversationVolume({ volume }, { volume = it }, { playing })

    @Test fun repeatedSpeechEventsDoNotLowerVolumeAgain() {
        for (status in listOf(1, 1, 2, 3)) controller.onStatus(status)
        assertEquals(2, volume)
        controller.onStatus(6)
        assertEquals(10, volume)
        controller.onStatus(9)
        assertEquals(10, volume)
    }

    @Test fun everyEndStateRestoresVolume() {
        for (status in 6..9) {
            controller.onStatus(1)
            controller.onStatus(status)
            assertEquals(10, volume)
        }
    }

    @Test fun manualVolumeChangesArePreserved() {
        controller.onStatus(1)
        volume = 4
        controller.onStatus(6)
        assertEquals(4, volume)
        controller.onStatus(1)
        controller.reset()
        assertEquals(4, volume)
    }

    @Test fun disablingOrDisconnectingRestoresOnce() {
        controller.onStatus(1)
        controller.reset()
        controller.reset()
        assertEquals(10, volume)
    }

    @Test fun idleMediaAndUnknownEventsDoNotChangeVolume() {
        playing = false
        controller.onStatus(1)
        assertEquals(10, volume)
        playing = true
        controller.onStatus(5)
        assertEquals(10, volume)
    }

    @Test fun smoothRestoreUsesGradualStepsAndFinishesAtTheOriginalVolume() {
        controller.onStatus(1)
        controller.onStatus(6, smoothRestore = true)
        assertEquals(2, volume)
        repeat(7) {
            assertTrue(controller.restoreStep())
            assertEquals(3 + it, volume)
        }
        assertFalse(controller.restoreStep())
        assertEquals(10, volume)
        assertFalse(controller.restoreStep())
    }

    @Test fun aNewConversationDuringRestorationKeepsTheOriginalTarget() {
        controller.onStatus(1)
        controller.onStatus(6, smoothRestore = true)
        repeat(3) { controller.restoreStep() }
        assertEquals(5, volume)
        controller.onStatus(1, smoothRestore = true)
        assertEquals(2, volume)
        assertFalse(controller.restoreStep())
        controller.onStatus(6, smoothRestore = true)
        repeat(10) { controller.restoreStep() }
        assertEquals(10, volume)
    }

    @Test fun aManualChangeDuringTheFadeStopsAutomaticRestoration() {
        controller.onStatus(1)
        controller.onStatus(6, smoothRestore = true)
        controller.restoreStep()
        volume = 5
        assertFalse(controller.restoreStep())
        controller.reset()
        assertEquals(5, volume)
    }

    @Test fun disablingDuringTheFadeRestoresImmediatelyOnce() {
        controller.onStatus(1)
        controller.onStatus(6, smoothRestore = true)
        controller.restoreStep()
        controller.reset()
        assertEquals(10, volume)
        assertFalse(controller.restoreStep())
    }

    @Test fun aRejectedVolumeStepCannotLeaveAnEndlessRestorationLoop() {
        var reject = false
        val limited = ConversationVolume({ volume }, { if (!reject) volume = it }, { true })
        limited.onStatus(1)
        limited.onStatus(6, smoothRestore = true)
        reject = true
        assertFalse(limited.restoreStep())
        assertFalse(limited.restoreStep())
    }

    @Test fun highResolutionVolumeRestoresInAtMostTenSteps() {
        volume = 150
        controller.onStatus(1)
        controller.onStatus(6, smoothRestore = true)
        var steps = 0
        do { steps++ } while (controller.restoreStep() && steps <= 10)
        assertTrue(steps <= 10)
        assertEquals(150, volume)
    }
}
