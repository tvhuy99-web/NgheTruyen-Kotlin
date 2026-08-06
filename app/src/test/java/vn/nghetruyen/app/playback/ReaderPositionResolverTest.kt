package vn.nghetruyen.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderPositionResolverTest {
    @Test fun forcedPositionWinsAndIsClamped() {
        assertEquals(4, ReaderPositionResolver.resolve("c", 5, forcedParagraphIndex = 99, savedChapterId = "c", savedParagraphIndex = 2))
        assertEquals(0, ReaderPositionResolver.resolve("c", 5, forcedParagraphIndex = -9))
    }

    @Test fun savedPositionIsUsedOnlyForTheSameChapter() {
        assertEquals(3, ReaderPositionResolver.resolve("c", 5, savedChapterId = "c", savedParagraphIndex = 3))
        assertEquals(0, ReaderPositionResolver.resolve("c", 5, savedChapterId = "other", savedParagraphIndex = 3))
    }

    @Test fun emptyChapterAlwaysResolvesToZero() {
        assertEquals(0, ReaderPositionResolver.resolve("c", 0, forcedParagraphIndex = 7))
    }
}
