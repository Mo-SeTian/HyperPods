package moe.chenxy.hyperpods.utils

import moe.chenxy.hyperpods.utils.data.HyperPodsPrefsKey as Key
import org.junit.Assert.*
import org.junit.Test

class PodsSettingsTest {
    @Test fun timingOptionsMatchProtocolValuesAndHeadsetCapabilities() {
        for (key in listOf(Key.PRESS_SPEED, Key.HOLD_DURATION, Key.SWIPE_SPEED)) {
            for (value in 0..2) assertTrue(PodsSettings.validValue(key, value, AirPodsPro2USBC(), emptyMap()))
            for (value in listOf(-1, 3, 255)) assertFalse(PodsSettings.knownValue(key, value))
        }
        assertFalse(PodsSettings.supports(Key.PRESS_SPEED, AirPods2()))
        assertFalse(PodsSettings.supports(Key.SWIPE_SPEED, AirPodsPro1()))
        assertFalse(PodsSettings.supports(Key.CHIME_VOLUME, null))
        for (value in 0..100) assertTrue(PodsSettings.knownValue(Key.CHIME_VOLUME, value))
        assertFalse(PodsSettings.validValue(Key.CHIME_VOLUME, 101, AirPodsPro2USBC(), emptyMap()))
    }
    @Test fun unsupportedAndUnknownModelsDoNotExposeNoiseOrAdaptiveFeatures() {
        for (model in listOf(null, AirPods(), AirPods2())) {
            assertTrue(PodsSettings.allowedNoiseModes(model, emptyMap()).isEmpty())
            assertFalse(PodsSettings.supports(Key.CONVERSATION_AWARENESS, model))
            assertFalse(PodsSettings.supports(Key.PERSONLIZED_VOLUME, model))
        }
        assertEquals(setOf(1, 2, 3), PodsSettings.allowedNoiseModes(AirPodsPro1(), emptyMap()))
        assertFalse(PodsSettings.supports(Key.ADJUST_VOLUME_BY_SWIPER, AirPods4ANC()))
    }

    @Test fun newerHeadsetsRequireConfirmedPermissionForOffMode() {
        val model = AirPodsPro2USBC()
        assertEquals(setOf(2, 3, 4), PodsSettings.allowedNoiseModes(model, emptyMap()))
        assertFalse(PodsSettings.validValue(PodsSettings.NOISE_MODE, 1, model, mapOf(Key.ALLOW_OFF_OPTION to 2)))
        assertTrue(PodsSettings.validValue(PodsSettings.NOISE_MODE, 1, model, mapOf(Key.ALLOW_OFF_OPTION to 1)))
    }

    @Test fun invalidOrUnsupportedCommandsAreRejected() {
        val model = AirPodsPro2USBC()
        assertFalse(PodsSettings.validValue(Key.LOUD_SOUND_REDUCTION, 1, model, emptyMap()))
        assertFalse(PodsSettings.validValue(Key.PERSONLIZED_VOLUME, 0, model, emptyMap()))
        assertFalse(PodsSettings.validValue(Key.MICROPHONE_MODE, 3, model, emptyMap()))
        assertFalse(PodsSettings.validValue(Key.ADAPTIVE_AUDIO_LEVEL, 101, model, emptyMap()))
        assertTrue(PodsSettings.validValue(Key.ADAPTIVE_AUDIO_LEVEL, 0, model, emptyMap()))
        assertTrue(PodsSettings.validValue(Key.ADAPTIVE_AUDIO_LEVEL, 100, model, emptyMap()))
    }

    @Test fun sharedCycleRequiresTwoSupportedAndAllowedModes() {
        val model = AirPodsPro2USBC()
        assertTrue(PodsSettings.validValue(Key.LISTENING_MODE_BYTE, 6, model, emptyMap()))
        assertFalse(PodsSettings.validValue(Key.LISTENING_MODE_BYTE, 2, model, emptyMap()))
        assertFalse(PodsSettings.validValue(Key.LISTENING_MODE_BYTE, 7, model, emptyMap()))
        assertTrue(PodsSettings.validValue(Key.LISTENING_MODE_BYTE, 7, model, mapOf(Key.ALLOW_OFF_OPTION to 1)))
        assertFalse(PodsSettings.validValue(Key.LISTENING_MODE_BYTE, 14, AirPodsPro1(), emptyMap()))
    }

    @Test fun microphoneValuesAndUiIndicesHaveDifferentLeftRightOrder() {
        assertEquals(0, PodsSettings.microphoneIndex(0))
        assertEquals(1, PodsSettings.microphoneIndex(2))
        assertEquals(2, PodsSettings.microphoneIndex(1))
        assertEquals(2, PodsSettings.microphoneValue(1))
        assertEquals(1, PodsSettings.microphoneValue(2))
        assertNull(PodsSettings.microphoneIndex(null))
        assertNull(PodsSettings.microphoneValue(3))
    }
}
