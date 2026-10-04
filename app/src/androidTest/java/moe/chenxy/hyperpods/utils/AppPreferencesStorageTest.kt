package moe.chenxy.hyperpods.utils

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.utils.data.HyperPodsPrefsKey as Key
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/** Isolated synthetic settings only; never changes a real user's policies or connects to Bluetooth. */
class AppPreferencesStorageTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var legacyName: String
    private lateinit var legacy: SharedPreferences
    private lateinit var context: Context
    private val snapshot = AppPreferences.Snapshot(false, false, false, LowBatterySettings(false, 35, true, 15))

    @Before fun setUp() {
        val id = UUID.randomUUID().toString()
        directory = File(app.cacheDir, "preferences-test-$id").apply { check(mkdirs()) }
        legacyName = "preferences_test_$id"
        legacy = app.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        context = object : ContextWrapper(app) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                assertEquals(BuildConfig.APPLICATION_ID + "_preferences", name)
                assertEquals(Context.MODE_PRIVATE, mode)
                return legacy
            }
        }
    }

    @After fun tearDown() {
        app.deleteSharedPreferences(legacyName)
        directory.deleteRecursively()
    }

    private fun oldFile() = File(app.applicationInfo.dataDir, "shared_prefs/$legacyName.xml")
    private fun populateLegacy() {
        assertTrue(legacy.edit().putBoolean(Key.EAR_DETECTION, false)
            .putBoolean(Key.EAR_DETECTION_SWITCH_SPEAKER, false)
            .putBoolean(Key.CONVERSATION_PHONE_VOLUME, false)
            .putBoolean(Key.LOW_BATTERY_EARS, false).putInt(Key.LOW_BATTERY_EARS_THRESHOLD, 35)
            .putBoolean(Key.LOW_BATTERY_CASE, true).putInt(Key.LOW_BATTERY_CASE_THRESHOLD, 15).commit())
        Os.chmod(oldFile().path, 0x1a4) // Synthetic 0644 XML reproduces the warning's permission condition.
    }

    @Test fun upgradeCopiesEveryChoiceThenRemovesWorldReadableXmlWithoutDeletingIt() {
        populateLegacy()
        assertEquals(0x1a4, Os.stat(oldFile().path).st_mode and 0x1ff)
        assertEquals(snapshot, AppPreferences.read(context))
        assertTrue(oldFile().isFile)
        assertFalse(File(oldFile().path + ".bak").exists())
        // Android may use 0660 for MODE_PRIVATE. The warning checks the "others" bits, not the app's own group.
        assertEquals(0, Os.stat(oldFile().path).st_mode and 0x7)
        assertEquals(0x180, Os.stat(oldFile().path).st_mode and 0x180)
        assertEquals(snapshot, AppPreferences.fromLegacy(legacy))
        assertEquals(snapshot, AppPreferences.decode(File(directory, "app_settings.json").readText()))
        assertEquals(0x180, Os.stat(File(directory, "app_settings.json").path).st_mode and 0x1ff)
    }

    @Test fun editsAndReopeningReadTheNewPrivateStoreNotStaleLegacyValues() {
        populateLegacy()
        assertEquals(snapshot, AppPreferences.read(context))
        val changed = snapshot.copy(earDetection = true, lowBattery = LowBatterySettings(true, 50, false, 10))
        assertTrue(AppPreferences.save(context, changed))
        assertEquals(changed, AppPreferences.read(context))
        val reopened = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                error("A migrated process must not use the legacy bridge again")
        }
        assertEquals(changed, AppPreferences.read(reopened))
        // Simulate another process start, including a framework with no NSP redirection.
        AppPreferences.javaClass.getDeclaredField("legacySecuredDir").apply { isAccessible = true }.set(AppPreferences, null)
        legacy.edit().clear().commit()
        assertEquals(changed, AppPreferences.read(context))
    }

    @Test fun freshInstallAndRepeatedWritesNeverCreateWorldReadableSettings() {
        assertEquals(AppPreferences.Snapshot(), AppPreferences.read(context))
        assertFalse(oldFile().exists())
        repeat(5) {
            assertTrue(AppPreferences.save(context, snapshot.copy(earDetection = it % 2 == 0)))
            assertEquals(0x180, Os.stat(File(directory, "app_settings.json").path).st_mode and 0x1ff)
        }
        assertFalse(oldFile().exists())
    }

    @Test fun invalidWriteKeepsTheLastSavedSettings() {
        assertTrue(AppPreferences.save(context, snapshot))
        assertFalse(AppPreferences.save(context, snapshot.copy(lowBattery = LowBatterySettings(caseThreshold = 99))))
        assertEquals(snapshot, AppPreferences.read(context))
    }

    @Test fun exportedProviderRejectsTheRealNonBluetoothCaller() {
        assertThrows(SecurityException::class.java) {
            app.contentResolver.call(AppPreferences.AUTHORITY, AppPreferences.READ, null, null)
        }
    }
}
