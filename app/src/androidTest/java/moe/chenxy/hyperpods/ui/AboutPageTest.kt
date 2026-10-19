package moe.chenxy.hyperpods.ui

import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
            R.string.dashboard_fork_repository, R.string.dashboard_fork_donate)) {
            compose.revealSetting(compose.activity.getString(label)).assertIsDisplayed()
        }
        compose.revealSetting("Art_Chen").assertIsDisplayed()
        compose.revealSetting("Mo-SeTian").assertIsDisplayed()
    }

    @Test fun eachRepositoryLinkOpensItsOwnRepository() {
        val repositories = listOf("https://github.com/Art-Chen/HyperPods", "https://github.com/Mo-SeTian/HyperPods")
        repositories.forEach { compose.revealSetting(it).assertIsDisplayed().performClick() }
        compose.runOnIdle {
            assertEquals(repositories, launched.map { it.dataString })
            assertEquals(listOf(Intent.ACTION_VIEW, Intent.ACTION_VIEW), launched.map { it.action })
        }
    }

    @Test fun eachDonationLinkOpensItsOwnCreator() {
        for (label in listOf(R.string.donate, R.string.dashboard_fork_donate)) {
            compose.revealSetting(compose.activity.getString(label)).assertIsDisplayed().performClick()
        }
        compose.runOnIdle {
            assertEquals(listOf("https://afdian.com/a/art_chen?tab=home", "https://afdian.com/a/mosetian"),
                launched.map { it.dataString })
            assertEquals(listOf(Intent.ACTION_VIEW, Intent.ACTION_VIEW), launched.map { it.action })
        }
    }
}
