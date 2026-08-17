package vn.nghetruyen.app

import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import vn.nghetruyen.app.ui.AppViewModel
import vn.nghetruyen.app.ui.RootTab

@RunWith(AndroidJUnit4::class)
class AudioDirectorMusicSettingsUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun personalMusicSettingsKeepManualMusicAndDoNotDuplicateAiAudioControls() {
        waitForComposeRoot()
        openPersonalRoot()
        composeRule.onNodeWithText("Cài đặt", useUnmergedTree = true).performClick()
        composeRule
            .onNodeWithText("NHẠC NỀN & NHẠC CẢNH", useUnmergedTree = true)
            .performScrollTo()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            hasText("Nhạc nền cục bộ")
        }

        composeRule.onNodeWithText("Nhạc nền cục bộ", useUnmergedTree = true).assertIsDisplayed()
        assertTextDoesNotExist("ÂM THANH AI")
        assertTextDoesNotExist("Nhạc cảnh AI")
        assertTextDoesNotExist("Âm thanh môi trường AI")
        assertTextDoesNotExist("Hiệu ứng âm thanh AI")
    }

    private fun waitForComposeRoot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var ready = false
        repeat(150) {
            instrumentation.waitForIdleSync()
            composeRule.runOnUiThread {
                val content = composeRule.activity.findViewById<ViewGroup>(android.R.id.content)
                ready = composeRule.activity.window.decorView.isAttachedToWindow &&
                    content != null && content.childCount > 0
            }
            if (ready) return
            Thread.sleep(100)
        }
        check(ready) { "Activity content did not become ready before Compose assertions." }
    }

    private fun openPersonalRoot() {
        composeRule.runOnUiThread {
            ViewModelProvider(composeRule.activity)[AppViewModel::class.java]
                .setRootTab(RootTab.PERSONAL)
        }
        composeRule.waitForIdle()
        check(waitForText("CÁ NHÂN", 5_000)) {
            "Personal root did not become visible before opening settings."
        }
    }

    private fun waitForText(text: String, timeoutMillis: Long): Boolean = runCatching {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) { hasText(text) }
        true
    }.getOrDefault(false)

    private fun hasText(text: String): Boolean = runCatching {
        composeRule.onAllNodesWithText(text, useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
    }.getOrDefault(false)

    private fun assertTextDoesNotExist(text: String) {
        check(!hasText(text)) { "Unexpected duplicate audio control: $text" }
    }
}
