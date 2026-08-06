package vn.nghetruyen.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class Id3v23WriterChapterTest {
    @Test
    fun writesOrderedChapterAndTableOfContentsFrames() {
        val output = ByteArrayOutputStream()
        Id3v23Writer.write(
            output,
            Id3v23Writer.Metadata(
                title = "Truyện thử",
                artist = "Tác giả",
                chapters = listOf(
                    Id3v23Writer.Chapter("Chương 1", 0, 1_250),
                    Id3v23Writer.Chapter("Chương 2", 1_250, 2_900),
                ),
            ),
        )

        val bytes = output.toByteArray()
        assertEquals("ID3", bytes.copyOfRange(0, 3).toString(Charsets.US_ASCII))
        val text = bytes.toString(Charsets.ISO_8859_1)
        assertEquals(2, Regex("CHAP").findAll(text).count())
        assertTrue(text.contains("CTOC"))
        assertTrue(text.indexOf("ch00000") < text.indexOf("ch00001"))
    }
}
