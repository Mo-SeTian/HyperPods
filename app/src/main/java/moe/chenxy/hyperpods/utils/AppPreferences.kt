package moe.chenxy.hyperpods.utils

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.system.Os
import android.util.AtomicFile
import android.util.Log
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import moe.chenxy.hyperpods.utils.data.HyperPodsPrefsKey as Key
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** App-owned policy only. Earbud settings still come from AACP, not this store. */
object AppPreferences {
    const val AUTHORITY = BuildConfig.APPLICATION_ID + ".preferences"
    const val READ = "read"
    const val EXTRA = "app_settings"
    private const val FILE = "app_settings.json"
    private const val PRIVATE_MARKER = "hyperpods_private_migration"
    private var legacySecuredDir: File? = null

    data class Snapshot(
        val earDetection: Boolean = true,
        val switchSpeaker: Boolean = true,
        val conversationVolume: Boolean = true,
        val lowBattery: LowBatterySettings = LowBatterySettings(),
    ) {
        fun encode(): String = JSONObject(mapOf(
            Key.EAR_DETECTION to earDetection,
            Key.EAR_DETECTION_SWITCH_SPEAKER to switchSpeaker,
            Key.CONVERSATION_PHONE_VOLUME to conversationVolume,
            Key.LOW_BATTERY_EARS to lowBattery.earsEnabled,
            Key.LOW_BATTERY_EARS_THRESHOLD to lowBattery.earsThreshold,
            Key.LOW_BATTERY_CASE to lowBattery.caseEnabled,
            Key.LOW_BATTERY_CASE_THRESHOLD to lowBattery.caseThreshold,
        )).toString()
    }

    fun decode(value: String?): Snapshot? {
        value ?: return null
        return try {
            val json = JSONObject(value)
            val battery = LowBatterySettings(
                json.get(Key.LOW_BATTERY_EARS) as Boolean,
                json.get(Key.LOW_BATTERY_EARS_THRESHOLD) as Int,
                json.get(Key.LOW_BATTERY_CASE) as Boolean,
                json.get(Key.LOW_BATTERY_CASE_THRESHOLD) as Int,
            )
            if (!battery.valid) null else Snapshot(
                json.get(Key.EAR_DETECTION) as Boolean,
                json.get(Key.EAR_DETECTION_SWITCH_SPEAKER) as Boolean,
                json.get(Key.CONVERSATION_PHONE_VOLUME) as Boolean,
                battery,
            )
        } catch (_: Exception) { null }
    }

    private fun file(context: Context) = AtomicFile(File(context.filesDir, FILE))

    /** filesDir is not redirected by NSP; future framework upgrades keep this data. */
    @Synchronized
    fun read(context: Context): Snapshot {
        val store = file(context)
        val saved = try { decode(store.openRead().bufferedReader().use { it.readText() }) }
            catch (_: IOException) { null }
        if (saved != null && legacySecuredDir == context.filesDir) return saved
        // Import any legacy XML still accessible here; already migrated JSON remains authoritative
        // after NSP redirection is disabled. Never call Yuki's world-readable prefs bridge.
        val legacy = context.getSharedPreferences(BuildConfig.APPLICATION_ID + "_preferences", Context.MODE_PRIVATE)
        val snapshot = saved ?: fromLegacy(legacy)
        val persisted = saved != null || write(store, snapshot)
        if (persisted && legacySecuredDir != context.filesDir && secureLegacy(legacy)) legacySecuredDir = context.filesDir
        return snapshot
    }

    internal fun fromLegacy(legacy: SharedPreferences): Snapshot {
        val values = legacy.all
        fun boolean(key: String) = values[key] as? Boolean ?: true
        fun threshold(key: String) = (values[key] as? Int)?.takeIf { it in LowBatteryReminder.thresholds } ?: 20
        return Snapshot(boolean(Key.EAR_DETECTION), boolean(Key.EAR_DETECTION_SWITCH_SPEAKER),
            boolean(Key.CONVERSATION_PHONE_VOLUME), LowBatterySettings(boolean(Key.LOW_BATTERY_EARS),
                threshold(Key.LOW_BATTERY_EARS_THRESHOLD), boolean(Key.LOW_BATTERY_CASE), threshold(Key.LOW_BATTERY_CASE_THRESHOLD)))
    }

    internal fun secureLegacy(legacy: SharedPreferences): Boolean {
        if (legacy.all.isEmpty()) return true // Fresh installs must not create a legacy XML.
        // Force a private-mode rewrite, including removal of the old .bak file.
        // Retain all values for recovery; do not delete or clear the old settings.
        val secured = legacy.edit().putBoolean(PRIVATE_MARKER, !legacy.getBoolean(PRIVATE_MARKER, false)).commit()
        if (!secured) Log.w("HyperPods-Preferences", "Unable to privatize legacy settings; will retry")
        return secured
    }

    @Synchronized
    fun save(context: Context, snapshot: Snapshot): Boolean = write(file(context), snapshot)

    internal fun write(store: AtomicFile, snapshot: Snapshot): Boolean {
        if (!snapshot.lowBattery.valid) return false
        val output = try { store.startWrite() } catch (_: IOException) { return false }
        return try {
            Os.fchmod(output.fd, 0x180) // 0600: owner read/write only.
            output.write(snapshot.encode().toByteArray(Charsets.UTF_8))
            store.finishWrite(output)
            true
        } catch (_: Exception) {
            store.failWrite(output)
            false
        }
    }

    /** Only the provider can read the app's file; the Bluetooth process receives a fixed snapshot. */
    fun readRemote(context: Context): Snapshot? = try {
        decode(context.contentResolver.call(AUTHORITY, READ, null, null)?.getString(EXTRA))
    } catch (_: Exception) {
        Log.w("HyperPods-Preferences", "Settings provider unavailable; retaining current policy")
        null
    }

    fun publish(context: Context, snapshot: Snapshot) {
        HyperPodsBroadcasts.send(context, Intent(HyperPodsAction.ACTION_APP_SETTINGS_CHANGED)
            .putExtra(EXTRA, snapshot.encode()), HyperPodsBroadcasts.BLUETOOTH)
    }
}
