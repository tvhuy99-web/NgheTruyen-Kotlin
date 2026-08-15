package vn.nghetruyen.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
        composeRule.onNodeWithText("CÁ NHÂN", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("Cài đặt", useUnmergedTree = true).performClick()
        composeRule
            .onNodeWithText("NHẠC NỀN & NHẠC CẢNH", useUnmergedTree = true)
            .performScrollTo()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Nhạc nền cục bộ", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("Nhạc nền cục bộ", useUnmergedTree = true).assertIsDisplayed()
        assertTextDoesNotExist("ÂM THANH AI")
        assertTextDoesNotExist("Nhạc cảnh AI")
        assertTextDoesNotExist("Âm thanh môi trường AI")
        assertTextDoesNotExist("Hiệu ứng âm thanh AI")
    }

    private fun assertTextDoesNotExist(text: String) {
        check(
            composeRule.onAllNodesWithText(text, useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        ) { "Unexpected duplicate audio control: $text" }
    }
}
