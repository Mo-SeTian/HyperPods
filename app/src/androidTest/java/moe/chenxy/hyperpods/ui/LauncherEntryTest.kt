package moe.chenxy.hyperpods.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.LauncherApps
import android.os.Process
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import moe.chenxy.hyperpods.MainActivity
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.utils.AppPreferences
import moe.chenxy.hyperpods.utils.LauncherIcon
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Exercises the real entry and package manager on an emulator, without a Bluetooth peer. */
class LauncherEntryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.packageManager
    private var originalState = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT

    private fun launcherIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(LauncherIcon.component(context)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun controlIntent() = Intent(HyperPodsAction.ACTION_SHOW_PODS_UI).setPackage(context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @Before fun hideIcon() {
        originalState = manager.getComponentEnabledSetting(LauncherIcon.component(context))
        manager.setComponentEnabledSetting(LauncherIcon.component(context),
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
    }

    @After fun restoreIcon() {
        manager.setComponentEnabledSetting(LauncherIcon.component(context), originalState, PackageManager.DONT_KILL_APP)
    }

    @Test fun iconDefaultsToHiddenAndTogglingPreservesTheExistingEntryAndPolicies() {
        val policy = AppPreferences.read(context)
        val launcher = context.getSystemService(LauncherApps::class.java)
        assertFalse(LauncherIcon.isVisible(context))
        assertNull(manager.getLaunchIntentForPackage(context.packageName))
        assertTrue(launcher.getActivityList(context.packageName, Process.myUserHandle()).isEmpty())
        val alias = manager.getActivityInfo(LauncherIcon.component(context), PackageManager.MATCH_DISABLED_COMPONENTS)
        assertEquals(MainActivity::class.java.name, alias.targetActivity)
        assertFalse(alias.enabled)
        for (visible in listOf(true, false)) {
            LauncherIcon.setVisible(context, visible)
            assertEquals(visible, LauncherIcon.isVisible(context))
            assertEquals(visible, manager.getLaunchIntentForPackage(context.packageName) != null)
            assertEquals(if (visible) listOf(LauncherIcon.component(context)) else emptyList(),
                launcher.getActivityList(context.packageName, Process.myUserHandle()).map { it.componentName })
            assertEquals(MainActivity::class.java.name, manager.resolveActivity(controlIntent(), 0)?.activityInfo?.name)
            assertEquals(policy, AppPreferences.read(context))
        }
    }

    @Test fun launcherPageSurvivesDisconnectHidingItsIconAndRecreation() {
        LauncherIcon.setVisible(context, true)
        ActivityScenario.launch<MainActivity>(launcherIntent()).use { scenario ->
            compose.onNodeWithText(context.getString(R.string.launcher_disconnected_title)).assertIsDisplayed()
            scenario.onActivity {
                assertTrue(it.keepOpenOnDisconnect)
                LauncherIcon.setVisible(it, false)
                it.onPodsDisconnected(initializationFailed = false)
                assertFalse(it.isFinishing)
            }
            scenario.recreate()
            scenario.onActivity {
                assertTrue(it.keepOpenOnDisconnect)
                assertFalse(LauncherIcon.isVisible(it))
                it.onPodsDisconnected(initializationFailed = false)
                assertFalse(it.isFinishing)
            }
            compose.onNodeWithText(context.getString(R.string.launcher_disconnected_summary)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.dashboard_about)).performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.launcher_icon_title)).assertIsOff()
        }
    }

    @Test fun existingEntryStillClosesOnNormalDisconnect() {
        ActivityScenario.launch<MainActivity>(controlIntent()).use { scenario ->
            scenario.onActivity {
                assertFalse(it.keepOpenOnDisconnect)
                it.onPodsDisconnected(initializationFailed = false)
                assertTrue(it.isFinishing)
            }
        }
    }

    @Test fun existingEntryRetainsAnInitializationFailureForDiagnostics() {
        ActivityScenario.launch<MainActivity>(controlIntent()).use { scenario ->
            scenario.onActivity {
                it.onPodsDisconnected(initializationFailed = true)
                assertFalse(it.isFinishing)
            }
        }
    }

    @Test fun launcherReusesAnExistingControlActivityAndUpdatesItsDisconnectPolicy() {
        LauncherIcon.setVisible(context, true)
        ActivityScenario.launch<MainActivity>(controlIntent()).use { scenario ->
            lateinit var original: MainActivity
            scenario.onActivity {
                original = it
                it.startActivity(launcherIntent())
            }
            compose.onNodeWithText(context.getString(R.string.launcher_disconnected_title)).assertIsDisplayed()
            scenario.onActivity {
                assertSame(original, it)
                assertTrue(it.keepOpenOnDisconnect)
                it.onPodsDisconnected(initializationFailed = false)
                assertFalse(it.isFinishing)
            }
        }
    }

    @Test fun aboutSwitchCanBeChangedWithoutConnectingHeadphonesAndPersistsOnRecreation() {
        ActivityScenario.launch<MainActivity>(controlIntent()).use { scenario ->
            compose.onNodeWithText(context.getString(R.string.dashboard_about)).performClick()
            val title = context.getString(R.string.launcher_icon_title)
            compose.onNodeWithContentDescription(title).assertIsOff().performClick().assertIsOn()
            assertTrue(LauncherIcon.isVisible(context))
            scenario.recreate()
            compose.onNodeWithText(context.getString(R.string.dashboard_about)).performClick()
            compose.onNodeWithContentDescription(title).assertIsOn().performClick().assertIsOff()
            assertFalse(LauncherIcon.isVisible(context))
            scenario.onActivity { assertFalse(it.isFinishing) }
            assertNotNull(manager.resolveActivity(controlIntent(), 0))
        }
    }
}
