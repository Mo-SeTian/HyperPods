package moe.chenxy.hyperpods.utils

import android.content.ComponentName
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.MainActivity
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import org.junit.Assert.*
import org.junit.Test

class ModuleIdentityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun newPackageResolvesTheOriginalActivityClassAndPrivateProvider() {
        assertEquals("io.github.mo_setian.hyperpods", context.packageName)
        val component = context.packageManager.resolveActivity(Intent(HyperPodsAction.ACTION_SHOW_PODS_UI)
            .setPackage(context.packageName), 0)?.activityInfo
        assertNotNull(component)
        assertEquals(context.packageName, component!!.packageName)
        assertEquals(MainActivity::class.java.name, component.name)
        val provider = context.packageManager.resolveContentProvider(AppPreferences.AUTHORITY, 0)
        assertNotNull(provider)
        assertEquals(context.packageName, provider!!.packageName)
        assertEquals(AppPreferencesProvider::class.java.name, provider.name)
        assertEquals(DiagnosticsHistory::class.java.name, context.packageManager.getReceiverInfo(
            ComponentName(context.packageName, DiagnosticsHistory::class.java.name), 0).name)
    }

    @Test fun legacyEntryDoesNotResolveToTheNewApp() {
        assertNull(context.packageManager.resolveActivity(Intent("chen.action.hyperpods.show_airpods_ui")
            .setPackage(BuildConfig.APPLICATION_ID), 0))
    }
}
