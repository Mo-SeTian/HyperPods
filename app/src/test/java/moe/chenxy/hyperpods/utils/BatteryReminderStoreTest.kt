package moe.chenxy.hyperpods.utils

import android.content.SharedPreferences
import moe.chenxy.hyperpods.pods.BatteryComponent as C
import moe.chenxy.hyperpods.utils.data.BatteryParams
import moe.chenxy.hyperpods.utils.data.PodBatteryParams
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class BatteryReminderStoreTest {
    private val values = mutableMapOf<String, Any>()
    private val preferences = mock(SharedPreferences::class.java).also {
        val editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF)
        `when`(it.edit()).thenReturn(editor)
        `when`(it.getString(anyString(), nullable(String::class.java))).thenAnswer { call ->
            values[call.getArgument<String>(0)] as? String ?: call.getArgument<String?>(1)
        }
        `when`(it.getInt(anyString(), anyInt())).thenAnswer { call ->
            values[call.getArgument<String>(0)] as? Int ?: call.getArgument<Int>(1)
        }
        `when`(editor.putString(anyString(), anyString())).thenAnswer { call ->
            values[call.getArgument(0)] = call.getArgument<String>(1); editor
        }
        `when`(editor.putInt(anyString(), anyInt())).thenAnswer { call ->
            values[call.getArgument(0)] = call.getArgument<Int>(1); editor
        }
    }
    private val identity = "SYNTHETIC_DEVICE_ONE"
    private fun low() = BatteryParams(left = PodBatteryParams(18, false, true, 2))

    @Test fun reconnectingAndRestartingReusePersistedPerComponentLevels() {
        val first = BatteryReminderStore(preferences, identity)
        val decisions = LowBatteryReminder.evaluate(low(), intArrayOf(C.LEFT), LowBatterySettings(), first::state)
        assertTrue(decisions.single().alert)
        first.commit(decisions, true)
        val reopened = BatteryReminderStore(preferences, identity)
        assertEquals(1, reopened.state(C.LEFT))
        assertFalse(LowBatteryReminder.evaluate(low(), intArrayOf(C.LEFT), LowBatterySettings(), reopened::state).single().alert)
        assertEquals(0, reopened.state(C.RIGHT))
    }

    @Test fun deviceNamesAndAddressesAreNotStoredInTheDeduplicationKeysOrValues() {
        val store = BatteryReminderStore(preferences, identity)
        store.commit(listOf(LowBatteryReminder.Decision(C.LEFT, 0, 1, true)), true)
        assertEquals(2, values.size) // Random salt plus one salted device/component key.
        assertTrue(values.keys.filter { it != "salt" }.all { it.matches(Regex("[0-9a-f]{64}\\.4")) })
        assertFalse(values.toString().contains(identity))
    }

    @Test fun differentDevicesCannotSuppressEachOthersReminders() {
        val one = BatteryReminderStore(preferences, identity)
        one.commit(listOf(LowBatteryReminder.Decision(C.LEFT, 0, 3, true)), true)
        val two = BatteryReminderStore(preferences, "SYNTHETIC_DEVICE_TWO")
        assertEquals(0, two.state(C.LEFT))
        assertEquals(3, one.state(C.LEFT))
    }

    @Test fun aFailedNotificationDoesNotConsumeTheReminder() {
        val store = BatteryReminderStore(preferences, identity)
        val decisions = LowBatteryReminder.evaluate(low(), intArrayOf(C.LEFT), LowBatterySettings(), store::state)
        store.commit(decisions, false)
        assertEquals(0, store.state(C.LEFT))
        assertTrue(LowBatteryReminder.evaluate(low(), intArrayOf(C.LEFT), LowBatterySettings(), store::state).single().alert)
    }

    @Test fun rechargingRearmsEvenWhenTheNotificationUpdateFails() {
        val store = BatteryReminderStore(preferences, identity)
        store.commit(listOf(LowBatteryReminder.Decision(C.LEFT, 0, 3, true)), true)
        store.commit(listOf(LowBatteryReminder.Decision(C.LEFT, 3, 0, false)), false)
        assertEquals(0, store.state(C.LEFT))
    }

    @Test fun unchangedReportsDoNotCauseAnotherPreferenceWrite() {
        val store = BatteryReminderStore(preferences, identity)
        clearInvocations(preferences)
        store.commit(listOf(LowBatteryReminder.Decision(C.LEFT, 0, 0, false)), true)
        verify(preferences, never()).edit()
    }
}
