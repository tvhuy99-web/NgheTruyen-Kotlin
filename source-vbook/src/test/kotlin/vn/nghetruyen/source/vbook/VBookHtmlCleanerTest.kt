package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VBookHtmlCleanerTest {
    @Test
    fun preservesOnlyAllowedTagsAndReadableText() {
        val cleaned = VBookHtmlCleaner.clean(
            "<div>Hello <b class='x'>World</b><i> italic</i><script>alert(1)</script></div>",
            listOf("b"),
        )
        assertTrue(cleaned.contains("Hello"))
        assertTrue(cleaned.contains("<b>World</b>"))
        assertTrue(cleaned.contains("italic"))
        assertFalse(cleaned.contains("<div"))
        assertFalse(cleaned.contains("<i"))
        assertFalse(cleaned.contains("script", ignoreCase = true))
        assertFalse(cleaned.contains("alert(1)"))
    }

    @Test
    fun emptyAllowListReturnsTextWithoutMarkup() {
        assertEquals("A B", VBookHtmlCleaner.clean("<p>A <em>B</em></p>", emptyList()).trim())
    }

    @Test
    fun invalidAndDuplicateTagsAreIgnoredSafely() {
        val cleaned = VBookHtmlCleaner.clean("<b>x</b><u>y</u>", listOf("B", "b", "<script>", "u"))
        assertEquals("<b>x</b><u>y</u>", cleaned)
    }
}
