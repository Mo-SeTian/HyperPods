package moe.chenxy.hyperpods.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import moe.chenxy.hyperpods.R
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Reproducible visual evidence from the real Compose pages and synthetic earbuds. */
class MiuixScreenshotsTest {
    @get:Rule val compose = createAndroidComposeRule<DashboardPreviewActivity>()
    private fun text(id: Int) = compose.activity.getString(id)

    private fun render(theme: Int = 1, unknown: Boolean = false, model: String = "A3049", caseConnected: Boolean = true) {
        compose.runOnUiThread {
            compose.activity.intent.putExtra("theme", theme).putExtra("unknown", unknown)
                .putExtra("case_connected", caseConnected).putExtra("in_case", caseConnected)
                .putExtra("personalized", true).putExtra("model_number", model)
        }
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix").orEmpty()
        val directory = File(compose.activity.getExternalFilesDir(null), "ui-miuix").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$prefix$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun open(id: Int) {
        compose.revealSetting(text(id)).performClick()
        compose.waitForIdle()
    }

    @Test fun captureHomeStates() {
        render(); capture("light")
        render(theme = 2); capture("dark")
        render(unknown = true); capture("unknown")
        render(caseConnected = false); capture("two-ears")
        render(model = "A3057"); capture("airpods-4")
        render(model = "A3531"); capture("airpods-5")
    }

    @Test fun captureSecondaryPages() {
        render()
        open(R.string.dashboard_more); capture("more")
        open(R.string.head_test_title); capture("gesture")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        open(R.string.dashboard_device_info); capture("device")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        open(R.string.diagnostics_title); capture("diagnostics")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(text(R.string.dashboard_about)).performClick()
        compose.waitForIdle(); capture("about")
    }
}
