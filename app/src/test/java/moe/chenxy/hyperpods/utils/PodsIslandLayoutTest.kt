package moe.chenxy.hyperpods.utils

import org.junit.Assert.*
import org.junit.Test

class PodsIslandLayoutTest {
    @Test fun musicCoexistenceRemovesOnlySurplusWidthAndKeepsTheCameraFixed() {
        val old = PodsIslandLayout(160, 100, 300, 280)
        val fixed = old.symmetric(false)!!
        assertEquals(PodsIslandLayout(100, 100, 240, 340), fixed)
        assertEquals(old.x + old.left, fixed.x + fixed.left)
        assertEquals(old.width - old.left - old.right, fixed.width - fixed.left - fixed.right)
        assertEquals(old.x + old.width, fixed.x + fixed.width)
        assertNull(fixed.symmetric(false)) // A second update cannot shrink it again.
    }

    @Test fun rtlKeepsThePhysicalCameraPositionWithoutMovingTheMusicBubble() {
        val old = PodsIslandLayout(160, 100, 300, 280)
        val fixed = old.symmetric(true)!!
        assertEquals(PodsIslandLayout(100, 100, 240, 280), fixed)
        assertEquals(old.x + old.right, fixed.x + fixed.right)
    }

    @Test fun eitherLongerSideCanBeTrimmedWithoutAssumingABatteryDigitCount() {
        assertEquals(PodsIslandLayout(80, 80, 200, 20), PodsIslandLayout(80, 100, 220, 20).symmetric(false))
        assertEquals(PodsIslandLayout(80, 80, 200, 40), PodsIslandLayout(80, 100, 220, 20).symmetric(true))
    }

    @Test fun incompleteAndAlreadySymmetricMeasurementsAreLeftToTheSystem() {
        for (value in listOf(PodsIslandLayout(0, 100, 140, 0), PodsIslandLayout(100, -1, 140, 0),
            PodsIslandLayout(100, 100, 240, 0), PodsIslandLayout(160, 100, 250, 0)))
            assertNull(value.symmetric(false))
    }

    @Test fun unequalBatteryDigitCountsMustNotBeCroppedToForceSymmetry() {
        val old = PodsIslandLayout(160, 100, 300, 280)
        assertNotNull(old.symmetric(false, contentWidth = 100))
        assertNull(old.symmetric(false, contentWidth = 110))
    }
}
