package vn.nghetruyen.app

import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        composeRule.onNodeWithText("CÁ NHÂN", useUnmergedTree = true).assertIsDisplayed()
        check(waitForContentDescription("Cài đặt", 5_000)) {
            "Personal settings entry did not become visible."
        }
        composeRule.onNodeWithContentDescription("Cài đặt", useUnmergedTree = true).performClick()
        check(waitForText("CÀI ĐẶT ỨNG DỤNG", 5_000)) {
            "Application settings dialog did not become visible."
        }
        composeRule.onNodeWithText("CÀI ĐẶT ỨNG DỤNG", useUnmergedTree = true).assertIsDisplayed()

        // The compact Settings home intentionally hides the legacy music page entry; the Reader
        // owns the three AI audio controls. Guard against accidentally reintroducing duplicate
        // Music/Ambience/SFX controls into this settings dialog.
        assertContentDescriptionDoesNotExist("NHẠC NỀN & NHẠC CẢNH")
        assertTextDoesNotExist("Nhạc nền cục bộ")
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

    private fun waitForContentDescription(description: String, timeoutMillis: Long): Boolean = runCatching {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) { hasContentDescription(description) }
        true
    }.getOrDefault(false)

    private fun hasText(text: String): Boolean = runCatching {
        composeRule.onAllNodesWithText(text, useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
    }.getOrDefault(false)

    private fun hasContentDescription(description: String): Boolean = runCatching {
        composeRule.onAllNodesWithContentDescription(description, useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
    }.getOrDefault(false)

    private fun assertTextDoesNotExist(text: String) {
        check(!hasText(text)) { "Unexpected duplicate audio control: $text" }
    }

    private fun assertContentDescriptionDoesNotExist(description: String) {
        check(!hasContentDescription(description)) { "Unexpected hidden settings action: $description" }
    }
}
