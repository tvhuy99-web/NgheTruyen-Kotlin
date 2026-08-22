package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VBookChapterContentNormalizationRegressionTest {
    @Test
    fun directTextSeparatedByBreaksSurvivesWhenHiddenParagraphsExist() {
        val html = """
            <div id="ads-chapter-top"></div>
            Đoạn thật thứ nhất.
            <br><br>
            Đoạn thật thứ hai.
            <p style="display: none;visibility: hidden;height: 0;">truyen full, truyenfull, truyenfullvn, truyenfulllive</p>
            <p style="display: none;visibility: hidden;height: 0;">,</p>
        """.trimIndent()

        val paragraphs = normalizeVBookChapterParagraphs(html)

        assertEquals(listOf("Đoạn thật thứ nhất.", "Đoạn thật thứ hai."), paragraphs)
        assertFalse(paragraphs.any { "truyenfull" in it.lowercase() })
    }

    @Test
    fun normalParagraphMarkupStillProducesOneEntryPerParagraph() {
        val html = "<p>Đoạn một.</p><p>Đoạn hai.</p>"
        assertEquals(listOf("Đoạn một.", "Đoạn hai."), normalizeVBookChapterParagraphs(html))
    }
}
