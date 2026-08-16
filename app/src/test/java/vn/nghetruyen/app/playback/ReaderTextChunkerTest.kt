package vn.nghetruyen.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTextChunkerTest {
    @Test
    fun preservesInternalWhitespaceAndUsesNonEmptyLinesAsXpkScaffolding() {
        val result = ReaderTextChunker.normalizeParagraphs(listOf("  Xin   chào  ", "   ", "Dòng\n mới"))

        assertEquals(listOf("Xin   chào", "Dòng", "mới"), result)
    }

    @Test
    fun legacyChunkHelperRemainsAvailableForNonXpkCallers() {
        val longParagraph = "a".repeat(7_200)
        val paragraphs = ReaderTextChunker.normalizeParagraphs(listOf(longParagraph))
        val chunks = ReaderTextChunker.chunkParagraphs(paragraphs)

        assertEquals(listOf(longParagraph), paragraphs)
        assertEquals(3, chunks.size)
        assertTrue(chunks.all { it.paragraphIndex == 0 })
        assertTrue(chunks.all { it.text.length <= ReaderTextChunker.SAFE_TTS_CHARS })
        assertEquals(7_200, chunks.sumOf { it.text.length })
    }

    @Test
    fun productionQueueAdvancesThroughTitleAndXpkSizedUnits() {
        val longParagraph = "a".repeat(7_200)
        PlaybackQueueStore.load(
            sourceId = "source",
            storyId = "story",
            chapterId = "chapter",
            chapterIndex = 0,
            chapterTitle = "Chương 1",
            paragraphs = listOf(longParagraph, "Đoạn thứ hai"),
        )

        val initial = PlaybackQueueStore.state.value
        assertEquals(0, initial.paragraphIndex)
        assertEquals(longParagraph, initial.currentParagraph)
        assertEquals("TITLE-U01", initial.currentUnitId)
        assertEquals("Bạn đang nghe: Chương 1", initial.currentSpeechText)

        val firstParagraphChunks = initial.speechChunks.filter { it.paragraphIndex == 0 }
        assertEquals(7, firstParagraphChunks.size) 
        assertTrue(firstParagraphChunks.drop(1).all { it.text.toByteArray(Charsets.UTF_8).size <= 1_200 })

        repeat(6) {
            assertTrue(PlaybackQueueStore.advanceSpeechChunk())
            assertEquals(0, PlaybackQueueStore.state.value.paragraphIndex)
        }
        assertFalse(PlaybackQueueStore.advanceSpeechChunk())

        assertTrue(PlaybackQueueStore.moveBy(1))
        assertEquals(1, PlaybackQueueStore.state.value.paragraphIndex)
        assertEquals("Đoạn thứ hai", PlaybackQueueStore.state.value.currentSpeechText)
    }

    @Test
    fun queueStartIndexUsesCanonicalXpkLines() {
        PlaybackQueueStore.load(
            sourceId = "source",
            storyId = "story",
            chapterId = "chapter",
            chapterIndex = 0,
            chapterTitle = "Chương 1",
            paragraphs = listOf("", "Nội dung"),
            startIndex = 99,
        )

        assertEquals(0, PlaybackQueueStore.state.value.paragraphIndex)
        assertEquals("Nội dung", PlaybackQueueStore.state.value.currentParagraph)
    }
}
