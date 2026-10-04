package moe.chenxy.hyperpods.utils

import moe.chenxy.hyperpods.utils.AACPManager.Companion.ControlCommandIdentifiers as Id
import moe.chenxy.hyperpods.utils.data.HyperPodsPrefsKey as Key

/** Wire values are kept separate from UI indices and local wear-policy preferences. */
object PodsSettings {
    const val NOISE_MODE = "noise_mode"
    const val RENAME = "rename"
    val identifiers = mapOf(
        NOISE_MODE to Id.LISTENING_MODE,
        Key.PERSONLIZED_VOLUME to Id.ADAPTIVE_VOLUME_CONFIG,
        Key.CONVERSATION_AWARENESS to Id.CONVERSATION_DETECT_CONFIG,
        Key.ADAPTIVE_AUDIO_LEVEL to Id.AUTO_ANC_STRENGTH,
        Key.ADJUST_VOLUME_BY_SWIPER to Id.VOLUME_SWIPE_MODE,
        Key.LISTENING_MODE_BYTE to Id.LISTENING_MODE_CONFIGS,
        Key.SINGLE_POD_ANC to Id.ONE_BUD_ANC_MODE,
        Key.MICROPHONE_MODE to Id.MIC_MODE,
        Key.ALLOW_OFF_OPTION to Id.ALLOW_OFF_OPTION,
    )

    fun keyFor(identifier: Byte): String? = identifiers.entries.find { it.value.value == identifier }?.key

    fun knownValue(key: String, value: Int): Boolean = when (key) {
        NOISE_MODE -> value in 1..4
        Key.MICROPHONE_MODE -> value in 0..2
        Key.ADAPTIVE_AUDIO_LEVEL -> value in 0..100
        Key.LISTENING_MODE_BYTE -> value in 1..15
        else -> key in identifiers && (value == 1 || value == 2)
    }

    fun supports(key: String, model: AirPodsBase?): Boolean {
        model ?: return false
        return when (key) {
            NOISE_MODE, Key.LISTENING_MODE_BYTE, Key.SINGLE_POD_ANC -> Capability.LISTENING_MODE in model.capabilities
            Key.PERSONLIZED_VOLUME -> Capability.ADAPTIVE_VOLUME in model.capabilities
            Key.CONVERSATION_AWARENESS -> Capability.CONVERSATION_AWARENESS in model.capabilities
            Key.ADAPTIVE_AUDIO_LEVEL -> Capability.ADAPTIVE_AUDIO in model.capabilities
            Key.ADJUST_VOLUME_BY_SWIPER -> Capability.SWIPE_FOR_VOLUME in model.capabilities
            Key.ALLOW_OFF_OPTION -> Capability.LOUD_SOUND_REDUCTION in model.capabilities
            Key.MICROPHONE_MODE -> true
            // Loud-sound reduction requires a separate ATT connection, which is not implemented.
            else -> false
        }
    }

    fun allowedNoiseModes(model: AirPodsBase?, values: Map<String, Int>): Set<Int> {
        if (!supports(NOISE_MODE, model)) return emptySet()
        return buildSet {
            if (Capability.LOUD_SOUND_REDUCTION !in model!!.capabilities || values[Key.ALLOW_OFF_OPTION] == 1) add(1)
            add(2)
            add(3)
            if (Capability.ADAPTIVE_AUDIO in model.capabilities) add(4)
        }
    }

    fun validValue(key: String, value: Int, model: AirPodsBase?, values: Map<String, Int>): Boolean {
        if (!supports(key, model)) return false
        return when (key) {
            NOISE_MODE -> value in allowedNoiseModes(model, values)
            Key.MICROPHONE_MODE -> value in 0..2
            Key.ADAPTIVE_AUDIO_LEVEL -> value in 0..100
            Key.LISTENING_MODE_BYTE -> {
                val mask = allowedNoiseModes(model, values).fold(0) { acc, mode -> acc or (1 shl (mode - 1)) }
                value in 1..15 && value and mask == value && Integer.bitCount(value) >= 2
            }
            else -> value == 1 || value == 2
        }
    }

    fun microphoneIndex(wireValue: Int?): Int? = when (wireValue) { 0 -> 0; 2 -> 1; 1 -> 2; else -> null }
    fun microphoneValue(index: Int): Int? = when (index) { 0 -> 0; 1 -> 2; 2 -> 1; else -> null }
}
