package vn.nghetruyen.app

import android.view.View
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioDirectorMusicSettingsUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun personalMusicSettingsKeepManualMusicAndDoNotDuplicateAiAudioControls() {
        waitForComposeRoot()
        returnToRoot()
        composeRule.onNodeWithText("CÁ NHÂN", useUnmergedTree = true).performClick()
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
                val content = composeRule.activity.findViewById<View>(android.R.id.content)
                ready = composeRule.activity.window.decorView.isAttachedToWindow &&
                    content != null && content.childCount > 0
            }
            if (ready) return
            Thread.sleep(100)
        }
        check(ready) { "Activity content did not become ready before Compose assertions." }
    }

    private fun returnToRoot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(6) {
            if (hasText("CÁ NHÂN")) return
            composeRule.runOnUiThread {
                composeRule.activity.onBackPressedDispatcher.onBackPressed()
            }
            instrumentation.waitForIdleSync()
        }
        check(hasText("CÁ NHÂN")) {
            "Could not return to the root navigation before opening Personal settings."
        }
    }

    private fun hasText(text: String): Boolean = runCatching {
        composeRule.onAllNodesWithText(text, useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
    }.getOrDefault(false)

    private fun assertTextDoesNotExist(text: String) {
        check(!hasText(text)) { "Unexpected duplicate audio control: $text" }
    }
}
