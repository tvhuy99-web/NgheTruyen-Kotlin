package vn.nghetruyen.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import vn.nghetruyen.app.core.model.ChapterContent
import vn.nghetruyen.app.core.model.ChapterSummary

class NextChapterNormalizerTest {
    @Test
    fun `normalizes fetched chapter against the active queue`() {
        val parent = PlaybackSnapshot(
            sourceId = "truyenfull",
            storyId = "story-1",
            chapterId = "chapter-4",
            chapterIndex = 3,
            chapterTitle = "Chương 4",
            chapterUrl = "https://truyenfull.live/story/chuong-4/",
            nextChapterUrl = "https://truyenfull.live/story/chuong-5/",
        )
        val fetched = ChapterContent(
            chapter = ChapterSummary(
                id = "chapter-5",
                storyId = "parsed-from-url",
                index = 0,
                title = "Chương 5",
                url = "",
            ),
            paragraphs = listOf("Nội dung"),
        )

        val normalized = NextChapterNormalizer.normalize(
            parent = parent,
            requestedUrl = parent.nextChapterUrl!!,
            fetched = fetched,
        )

        assertEquals("story-1", normalized.chapter.storyId)
        assertEquals(4, normalized.chapter.index)
        assertEquals(parent.nextChapterUrl, normalized.chapter.url)
        assertEquals(parent.chapterUrl, normalized.previousChapterUrl)
    }
}
