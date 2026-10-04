package moe.chenxy.hyperpods.utils

import android.content.SharedPreferences
import androidx.core.content.edit
import java.security.MessageDigest
import java.util.UUID

/** Stored in the Xiaomi Bluetooth process. No raw address or device name is retained or logged. */
class BatteryReminderStore(private val preferences: SharedPreferences, address: String) {
    private val prefix: String

    init {
        val salt = preferences.getString("salt", null) ?: UUID.randomUUID().toString().also {
            preferences.edit { putString("salt", it) }
        }
        prefix = MessageDigest.getInstance("SHA-256").digest("$salt:${address.uppercase(java.util.Locale.ROOT)}".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun state(component: Int): Int = preferences.getInt("$prefix.$component", 0) and 3

    fun commit(decisions: List<LowBatteryReminder.Decision>, notificationPosted: Boolean) {
        val changed = decisions.mapNotNull {
            // A failed notification must not consume a reminder; recharge observations still rearm it.
            val next = if (notificationPosted) it.next else it.next and it.previous
            if (next != it.previous) "$prefix.${it.component}" to next else null
        }
        if (changed.isEmpty()) return
        preferences.edit { changed.forEach { (key, value) -> putInt(key, value) } }
    }
}
