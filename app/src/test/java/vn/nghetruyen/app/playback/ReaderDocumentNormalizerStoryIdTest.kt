package vn.nghetruyen.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.app.core.model.ChapterContent
import vn.nghetruyen.app.core.model.ChapterSummary

class ReaderDocumentNormalizerStoryIdTest {
    @Test
    fun `repairs missing TruyenFull story id from chapter url`() {
        val normalized = ReaderDocumentNormalizer.normalize(
            ChapterContent(
                chapter = ChapterSummary(
                    id = "4766160b0e7bbfcaef76607f",
                    storyId = "",
                    index = 49,
                    title = "Chương 50",
                    url = "https://truyenfull.live/quan-tai-my-nhan-linh-di-13-hao/chuong-50/",
                ),
                paragraphs = listOf("Nội dung chương"),
            ),
        )

        assertEquals("0d7b0d5c81a37130727cf597", normalized.chapter.storyId)
    }

    @Test
    fun `preserves an existing story id`() {
        val normalized = ReaderDocumentNormalizer.normalize(
            ChapterContent(
                chapter = ChapterSummary(
                    id = "chapter-1",
                    storyId = "canonical-story",
                    index = 0,
                    title = "Chương 1",
                    url = "https://truyenfull.live/story/chuong-1/",
                ),
                paragraphs = listOf("Nội dung"),
            ),
        )

        assertEquals("canonical-story", normalized.chapter.storyId)
    }

    @Test
    fun `does not invent an id for unrelated sources`() {
        val normalized = ReaderDocumentNormalizer.normalize(
            ChapterContent(
                chapter = ChapterSummary(
                    id = "chapter-1",
                    storyId = "",
                    index = 0,
                    title = "Chương 1",
                    url = "https://example.com/story/chapter-1/",
                ),
                paragraphs = listOf("Nội dung"),
            ),
        )

        assertTrue(normalized.chapter.storyId.isBlank())
    }
}
