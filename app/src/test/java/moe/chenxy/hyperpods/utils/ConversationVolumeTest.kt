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
}
