package moe.chenxy.hyperpods.ui

import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import moe.chenxy.hyperpods.R
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Verify attribution and external destinations without opening a browser. */
@RunWith(Parameterized::class)
class AboutPageTest(private val theme: Int) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "theme={0}")
        fun themes() = listOf(arrayOf(1), arrayOf(2))
    }

    @get:Rule val compose = createAndroidComposeRule<DashboardPreviewActivity>()
    private val launched = mutableListOf<Intent>()

    @Before fun showAbout() {
        compose.runOnUiThread {
            val context = object : ContextWrapper(compose.activity) {
                override fun startActivity(intent: Intent) { launched.add(intent) }
            }
            compose.activity.setContent {
                AppTheme(theme) {
                    CompositionLocalProvider(LocalContext provides context) {
                        AboutPage(PaddingValues())
                    }
                }
            }
        }
    }

    @Test fun originalAndForkAttributionsRemainVisible() {
        for (label in listOf(R.string.dashboard_author, R.string.dashboard_fork_author,
            R.string.redesign_sources)) {
            compose.revealSetting(compose.activity.getString(label)).assertIsDisplayed()
        }
        compose.revealSetting("Art_Chen").assertIsDisplayed()
        compose.revealSetting("Mo-SeTian").assertIsDisplayed()
    }

    @Test fun aboutPageHasASingleDiagnosticsEntry() {
        compose.revealSetting(compose.activity.getString(R.string.diagnostics_title)).assertIsDisplayed()
        compose.onAllNodesWithText(compose.activity.getString(R.string.diagnostics_title)).assertCountEquals(1)
    }

    @Test fun eachRepositoryLinkOpensItsOwnRepository() {
        val repositories = listOf("https://github.com/Art-Chen/HyperPods", "https://github.com/Mo-SeTian/HyperPods")
        for (name in listOf("Art_Chen", "Mo-SeTian")) {
            compose.revealSetting(androidx.compose.ui.test.hasContentDescription("$name GitHub")).assertIsDisplayed().performClick()
        }
        compose.runOnIdle {
            assertEquals(repositories, launched.map { it.dataString })
            assertEquals(listOf(Intent.ACTION_VIEW, Intent.ACTION_VIEW), launched.map { it.action })
        }
    }

    @Test fun eachDonationLinkOpensItsOwnCreator() {
        for (name in listOf("Art_Chen", "Mo-SeTian")) {
            compose.revealSetting(androidx.compose.ui.test.hasContentDescription("$name ${compose.activity.getString(R.string.fidelity_afdian)}")).assertIsDisplayed().performClick()
        }
        compose.runOnIdle {
            assertEquals(listOf("https://afdian.com/a/art_chen?tab=home", "https://afdian.com/a/mosetian"),
                launched.map { it.dataString })
            assertEquals(listOf(Intent.ACTION_VIEW, Intent.ACTION_VIEW), launched.map { it.action })
        }
    }
}
