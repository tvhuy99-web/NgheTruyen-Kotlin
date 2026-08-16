package vn.nghetruyen.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioDirectorMusicSettingsUiTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun personalMusicSettingsKeepManualMusicAndDoNotDuplicateAiAudioControls() {
        // MainActivity performs substantial startup work before its first Compose frame. Launch it
        // only after the empty Compose test environment is installed so setContent cannot race the
        // root registry on slower API-33 CI emulators.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForText("CÁ NHÂN", timeoutMillis = 15_000)
            returnToRoot(scenario)

            composeRule.onNodeWithText("CÁ NHÂN", useUnmergedTree = true).performClick()
            waitForText("Cài đặt")
            composeRule.onNodeWithText("Cài đặt", useUnmergedTree = true).performClick()
            waitForText("NHẠC NỀN & NHẠC CẢNH")
            composeRule
                .onNodeWithText("NHẠC NỀN & NHẠC CẢNH", useUnmergedTree = true)
                .performScrollTo()
                .performClick()

            waitForText("Nhạc nền cục bộ")
            composeRule.onNodeWithText("Nhạc nền cục bộ", useUnmergedTree = true).assertIsDisplayed()
            assertTextDoesNotExist("ÂM THANH AI")
            assertTextDoesNotExist("Nhạc cảnh AI")
            assertTextDoesNotExist("Âm thanh môi trường AI")
            assertTextDoesNotExist("Hiệu ứng âm thanh AI")
        }
    }

    private fun returnToRoot(scenario: ActivityScenario<MainActivity>) {
        repeat(6) {
            if (hasText("CÁ NHÂN")) return
            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
            }
            composeRule.waitForIdle()
        }
        check(hasText("CÁ NHÂN")) {
            "Could not return to the root navigation before opening Personal settings."
        }
    }

    private fun waitForText(text: String, timeoutMillis: Long = 10_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) { hasText(text) }
    }

    private fun hasText(text: String): Boolean = runCatching {
        composeRule.onAllNodesWithText(text, useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
    }.getOrDefault(false)

    private fun assertTextDoesNotExist(text: String) {
        check(!hasText(text)) { "Unexpected duplicate audio control: $text" }
    }
}
