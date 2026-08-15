package vn.nghetruyen.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
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
    fun musicSettingsExposeMusicAmbienceAndSfxThroughUserNavigation() {
        composeRule.onNodeWithText("CÁ NHÂN", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("Cài đặt", useUnmergedTree = true).performClick()
        composeRule
            .onNode(hasText("NHẠC NỀN & NHẠC CẢNH"), useUnmergedTree = true)
            .performScrollTo()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("AI SOUND DIRECTOR", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("AI SOUND DIRECTOR", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Nhạc cảnh AI", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Âm thanh môi trường AI", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Hiệu ứng âm thanh AI", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNode(hasText("QUẢN LÝ NHẠC", substring = true), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }
}
