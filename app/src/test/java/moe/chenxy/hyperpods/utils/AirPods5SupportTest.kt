package moe.chenxy.hyperpods.utils

import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.utils.data.HyperPodsPrefsKey as Key
import org.junit.Assert.*
import org.junit.Test

class AirPods5SupportTest {
    @Test fun wirelessArtworkMatchesLibrePodsInsteadOfDuplicatingProAssets() {
        val hashes = mapOf(
            "buds" to "a33bdef8f19daaa397976837e95db6f529d2523bb0c07aab971de9f2637a458a",
            "left" to "ef6aaf7c80c38ceb4250fcfc860785b69849550b907158d12bcc83737307349e",
            "right" to "82f005398272a763e7f03322eb4f830c4c8d8a9fa763f66f5d5fc12c6034b57b",
            "case" to "781652acc41d187aba12c141d1da77e7f07b1a1c50a769ba400ff7337eb34b1b",
        )
        hashes.forEach { (part, expected) ->
            val image = java.io.File("src/main/res/drawable/airpods_4_$part.png").readBytes()
            val actual = java.security.MessageDigest.getInstance("SHA-256").digest(image)
                .joinToString("") { "%02x".format(it) }
            assertEquals("AirPods 4/5 WCC artwork: $part", expected, actual)
        }
    }
    private val standard = listOf("A3531", "A3532", "A3533")
    private val wireless = listOf("A3439", "A3440", "A3441")
    private fun model(number: String): AirPodsBase {
        val model = AirPodsModels.getModelByModelNumber(number)
        assertNotNull("Unrecognized AirPods 5 model: $number", model)
        return model!!
    }

    @Test fun allSixModelNumbersIdentifyTheCorrectVariant() {
        standard.forEach {
            assertEquals("AirPods5", model(it).javaClass.simpleName)
            assertEquals("AirPods 5", model(it).name)
        }
        wireless.forEach {
            assertEquals("AirPods5WCC", model(it).javaClass.simpleName)
            assertEquals("AirPods 5 with Wireless Charging Case", model(it).name)
        }
        assertNull(AirPodsModels.getModelByModelNumber("A3530"))
        assertNull(AirPodsModels.getModelByModelNumber("AirPods 5"))
    }

    @Test fun capabilitiesMatchLibrePodsAndOnlyWirelessSupportsVolumeSwipe() {
        val common = setOf(Capability.LISTENING_MODE, Capability.CONVERSATION_AWARENESS,
            Capability.HEAD_GESTURES, Capability.ADAPTIVE_AUDIO, Capability.SLEEP_DETECTION,
            Capability.ADAPTIVE_VOLUME, Capability.STEM_CONFIG)
        standard.forEach { assertEquals(common, model(it).capabilities) }
        wireless.forEach { assertEquals(common + Capability.SWIPE_FOR_VOLUME, model(it).capabilities) }
        standard.forEach {
            assertFalse(PodsSettings.supports(Key.ADJUST_VOLUME_BY_SWIPER, model(it)))
            assertFalse(PodsSettings.supports(Key.SWIPE_SPEED, model(it)))
        }
        wireless.forEach {
            assertTrue(PodsSettings.supports(Key.ADJUST_VOLUME_BY_SWIPER, model(it)))
            for (value in 1..3) assertTrue(PodsSettings.validValue(Key.SWIPE_SPEED, value, model(it), emptyMap()))
        }
    }

    @Test fun bothVariantsExposeTheExistingNoiseAndSmartListeningControls() {
        (standard + wireless).forEach { number ->
            val model = model(number)
            assertEquals(setOf(1, 2, 3, 4), PodsSettings.allowedNoiseModes(model, emptyMap()))
            for (mode in 1..4) assertTrue(PodsSettings.validValue(PodsSettings.NOISE_MODE, mode, model, emptyMap()))
            for (key in listOf(Key.PERSONLIZED_VOLUME, Key.CONVERSATION_AWARENESS, Key.SINGLE_POD_ANC)) {
                for (value in 1..2) assertTrue(PodsSettings.validValue(key, value, model, emptyMap()))
            }
            for (key in listOf(Key.PRESS_SPEED, Key.HOLD_DURATION, Key.MICROPHONE_MODE)) {
                for (value in 0..2) assertTrue(PodsSettings.validValue(key, value, model, emptyMap()))
            }
            for (key in listOf(Key.ADAPTIVE_AUDIO_LEVEL, Key.CHIME_VOLUME)) {
                assertTrue(PodsSettings.validValue(key, 0, model, emptyMap()))
                assertTrue(PodsSettings.validValue(key, 100, model, emptyMap()))
            }
            assertTrue(PodsSettings.validValue(Key.LISTENING_MODE_BYTE, 15, model, emptyMap()))
        }
    }

    @Test fun airPods5DoesNotInheritProOnlyFeaturesOrAcceptInvalidCommands() {
        (standard + wireless).forEach { number ->
            val model = model(number)
            for (capability in listOf(Capability.LOUD_SOUND_REDUCTION, Capability.HEARING_AID, Capability.HRM))
                assertFalse(capability in model.capabilities)
            assertFalse(PodsSettings.supports(Key.ALLOW_OFF_OPTION, model))
            assertFalse(PodsSettings.supports(Key.LOUD_SOUND_REDUCTION, model))
            assertFalse(PodsSettings.validValue(PodsSettings.NOISE_MODE, 5, model, emptyMap()))
            assertFalse(PodsSettings.validValue(Key.PERSONLIZED_VOLUME, 0, model, emptyMap()))
            assertFalse(PodsSettings.validValue(Key.CHIME_VOLUME, 101, model, emptyMap()))
        }
    }

    @Test fun registeringAirPods5DoesNotOverlapExistingModels() {
        (standard + wireless).forEach { number ->
            assertEquals(1, AirPodsModels.models.count { number in it.modelNumber })
        }
        val numbers = AirPodsModels.models.flatMap { it.modelNumber }
        assertEquals(numbers.size, numbers.toSet().size)
        assertTrue(AirPodsModels.getModelByModelNumber("A3049") is AirPodsPro2USBC)
        assertTrue(AirPodsModels.getModelByModelNumber("A3057") is AirPods4ANC)
        assertTrue(AirPodsModels.getModelByModelNumber("A3065") is AirPodsPro3)
    }

    @Test fun dashboardAndIslandUseTheVariantArtwork() {
        standard.forEach { number ->
            val model = model(number)
            assertEquals(R.drawable.airpods_5_buds, model.budsRes)
            assertEquals(R.drawable.airpods_5_left, model.leftBudsRes)
            assertEquals(R.drawable.airpods_5_right, model.rightBudsRes)
            assertEquals(R.drawable.airpods_5_case, model.caseRes)
            assertSame(model, PodsIslandData.modelForIcons(number))
        }
        wireless.forEach { number ->
            val model = model(number)
            assertEquals(R.drawable.airpods_4_buds, model.budsRes)
            assertEquals(R.drawable.airpods_4_left, model.leftBudsRes)
            assertEquals(R.drawable.airpods_4_right, model.rightBudsRes)
            assertEquals(R.drawable.airpods_4_case, model.caseRes)
            assertSame(model, PodsIslandData.modelForIcons(number))
        }
        for (number in listOf(null, "", "unknown"))
            assertNull(PodsIslandData.modelForIcons(number))
    }

    @Test fun allRegisteredModelsShareTheirArtworkWithTheIsland() {
        AirPodsModels.models.forEach { model ->
            model.modelNumber.forEach { number ->
                assertSame("Island artwork differs for $number", model, PodsIslandData.modelForIcons(number))
            }
        }
        assertNull(PodsIslandData.modelForIcons("A9999"))
    }
}
