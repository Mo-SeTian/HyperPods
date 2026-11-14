package moe.chenxy.hyperpods.utils

import moe.chenxy.hyperpods.pods.NoiseControlMode
import org.junit.Assert.*
import org.junit.Test

class ListeningPositionTest {
    @Test fun adaptiveRangeIncludesBothNativeEndpoints() {
        for ((position, wire) in listOf(.2f to 0, .35f to 25, .5f to 50, .65f to 75, .8f to 100)) {
            assertEquals(NoiseControlMode.ADAPTIVE, ListeningPosition.modeAt(position, true))
            assertEquals(wire, ListeningPosition.wireStrength(ListeningPosition.adaptiveLevel(position)))
        }
    }

    @Test fun onlyPositionsOutsideTheRangeSelectFixedModes() {
        assertEquals(NoiseControlMode.TRANSPARENCY, ListeningPosition.modeAt(.199f, true))
        assertEquals(NoiseControlMode.NOISE_CANCELLATION, ListeningPosition.modeAt(.801f, true))
        assertEquals(NoiseControlMode.TRANSPARENCY, ListeningPosition.modeAt(0f, true))
        assertEquals(NoiseControlMode.NOISE_CANCELLATION, ListeningPosition.modeAt(1f, true))
    }

    @Test fun everyNativeStrengthRoundTripsWithoutLosingOnePercent() {
        for (wire in 0..100) {
            val level = ListeningPosition.levelFromWire(wire)
            val position = ListeningPosition.fromReport(NoiseControlMode.ADAPTIVE, level)
            assertEquals(wire, ListeningPosition.wireStrength(ListeningPosition.adaptiveLevel(position)))
        }
    }

    @Test fun fixedModesUseTheFullTrackEndpoints() {
        assertEquals(0f, ListeningPosition.fromReport(NoiseControlMode.TRANSPARENCY, .5f), 0f)
        assertEquals(1f, ListeningPosition.fromReport(NoiseControlMode.NOISE_CANCELLATION, .5f), 0f)
    }

    @Test fun olderModelsCannotEnterAdaptiveThroughTheRange() {
        assertEquals(NoiseControlMode.TRANSPARENCY, ListeningPosition.modeAt(.35f, false))
        assertEquals(NoiseControlMode.NOISE_CANCELLATION, ListeningPosition.modeAt(.65f, false))
    }
}
