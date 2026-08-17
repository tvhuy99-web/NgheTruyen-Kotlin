package vn.nghetruyen.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNode(isRoot(), useUnmergedTree = true).fetchSemanticsNode()
                true
            }.getOrDefault(false)
        }
    }

    private fun returnToRoot() {
        repeat(6) {
            if (hasText("CÁ NHÂN")) return
            composeRule.runOnUiThread {
                composeRule.activity.onBackPressedDispatcher.onBackPressed()
            }
            composeRule.waitForIdle()
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
