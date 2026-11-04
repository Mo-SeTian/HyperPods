package moe.chenxy.hyperpods.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.R
import moe.chenxy.hyperpods.ui.components.*
import moe.chenxy.hyperpods.utils.HyperPodsBroadcasts
import moe.chenxy.hyperpods.utils.ModuleStatus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Native UI with synthetic host reports; no LSPosed state or headset settings are changed. */
class ModuleActivationUiTest {
    @get:Rule val compose = createAndroidComposeRule<DashboardPreviewActivity>()
    private fun text(id: Int) = compose.activity.getString(id)
    private fun reports() = ModuleStatus.packages.associateWith {
        ModuleStatus.Report(it, BuildConfig.VERSION_CODE, BuildConfig.BUILD_REVISION, "native_ok")
    }
    private var check by mutableStateOf(ModuleStatus.Check(checking = false))
    private var retries = 0

    private fun show(theme: Int = 1) {
        compose.runOnUiThread {
            compose.activity.setContent {
                AppTheme(theme) {
                    top.yukonga.miuix.kmp.basic.Scaffold(bottomBar = { DashboardNavigation(0) {} }) { padding ->
                        if (check.issue != null) ModuleActivationGuide(padding, check) {
                            retries++
                            check = ModuleStatus.Check(previousIssue = check.issue)
                        } else WaitingPodsPage(padding, true)
                    }
                }
            }
        }
    }

    @Test fun onboardingExplainsTheDependencyAndTheHelpSheetListsTheRealScope() {
        show()
        listOf(R.string.module_setup_title, R.string.module_setup_summary, R.string.module_setup_step_enable,
            R.string.module_setup_step_scope, R.string.module_setup_step_restart).forEach {
            compose.onNodeWithText(text(it)).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText(text(R.string.module_setup_help)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.module_setup_requirements)).assertIsDisplayed()
        val scope = compose.activity.resources.getStringArray(R.array.xposedscope).joinToString("\n")
        compose.onNodeWithText(scope).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(text(R.string.module_setup_title)).assertIsDisplayed()
    }

    @Test fun retryKeepsTheGuideVisibleAndRecoveryReturnsToTheOrdinaryDisconnectedPage() {
        show()
        compose.onNodeWithText(text(R.string.module_setup_retry)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithText(text(R.string.module_setup_title)).assertExists()
        compose.onNodeWithText(text(R.string.module_setup_checking)).assertIsNotEnabled()
        compose.runOnIdle { check = ModuleStatus.Check(reports(), false) }
        compose.onNodeWithText(text(R.string.launcher_disconnected_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.module_setup_title)).assertDoesNotExist()
    }

    @Test fun partialAndOutdatedReportsHaveDistinctActionableMessages() {
        check = ModuleStatus.Check(reports() - HyperPodsBroadcasts.SYSTEM_UI, false)
        show()
        compose.onNodeWithText(text(R.string.module_setup_partial_title)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.module_setup_missing,
            text(R.string.module_system_ui))).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.module_setup_title)).assertDoesNotExist()
        compose.runOnIdle {
            check = ModuleStatus.Check(reports().mapValues { it.value.copy(revision = "00000000") }, false)
        }
        compose.onNodeWithText(text(R.string.module_setup_restart_title)).assertIsDisplayed()
    }

    @Test fun liveProbeWaitsForTimeoutAndRefreshesWhenTheAppResumes() {
        compose.mainClock.autoAdvance = false
        lateinit var state: ModuleCheckState
        compose.runOnUiThread {
            compose.activity.setContent { AppTheme(1) {
                state = rememberModuleCheck()
                if (state.check.issue != null) ModuleActivationGuide(PaddingValues(), state.check, state::retry)
            } }
        }
        compose.mainClock.advanceTimeBy(2000)
        compose.runOnIdle { assertTrue(state.check.checking); assertNull(state.check.issue) }
        compose.runOnIdle { state.retry() }
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle { assertTrue(state.check.checking); assertNull(state.check.issue) }
        compose.mainClock.advanceTimeBy(2000)
        compose.onNodeWithText(text(R.string.module_setup_title)).assertIsDisplayed()
        val oldRequest = state.request
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { assertTrue(state.check.checking); assertNotEquals(oldRequest, state.request) }
        compose.mainClock.advanceTimeBy(2700)
        compose.runOnIdle { assertFalse(state.check.checking) }
    }

    @Test fun missingHostWarningDoesNotDisableWorkingHeadphoneControls() {
        compose.runOnUiThread { compose.activity.intent.putExtra("module_warning", true) }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(text(R.string.module_setup_partial_title)).assertIsDisplayed()
        compose.revealSetting(text(R.string.noise_cancellation_title)).assertIsEnabled()
        compose.revealSetting(text(R.string.dashboard_transparency)).performClick().assertIsSelected()
    }

    @Test fun workingControlsWithNoSelfCheckReportAreNotLabeledAsAnUndetectedModule() {
        compose.runOnUiThread {
            compose.activity.setContent { AppTheme(1) {
                androidx.compose.foundation.layout.Column {
                    ModuleWarningCard(ModuleStatus.Check(checking = false), {})
                }
            } }
        }
        compose.onNodeWithText(text(R.string.module_setup_control_report_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.module_setup_title)).assertDoesNotExist()
    }

    private fun capture(name: String) {
        val directory = File(compose.activity.getExternalFilesDir(null), "module-setup").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun captureLightDarkAndSetupHelp() {
        show(); capture("light")
        show(2); capture("dark")
        show(); compose.onNodeWithText(text(R.string.module_setup_help)).performScrollTo().performClick()
        capture("help")
    }
}
