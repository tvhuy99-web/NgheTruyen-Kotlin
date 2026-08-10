package com.nghetruyen.source.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityTestkitTest {
    @Test
    fun comparatorReportsNestedSemanticDifference() {
        val expected = CompatibilitySnapshot(
            data = CompatValue.ObjectValue(
                mapOf("book" to CompatValue.ObjectValue(mapOf("title" to CompatValue.StringValue("A")))),
            ),
        )
        val actual = CompatibilitySnapshot(
            data = CompatValue.ObjectValue(
                mapOf("book" to CompatValue.ObjectValue(mapOf("title" to CompatValue.StringValue("B")))),
            ),
        )

        val result = SemanticCompatibilityComparator().compare("detail-1", expected, actual)

        assertEquals(CompatibilityVerdict.FAIL, result.verdict)
        assertEquals("$.data.book.title", result.differences.single().path)
    }

    @Test
    fun comparatorCanIgnoreVolatileFields() {
        val expected = CompatibilitySnapshot(request = CompatibilityRequestSnapshot(headers = mapOf("Date" to "old")))
        val actual = CompatibilitySnapshot(request = CompatibilityRequestSnapshot(headers = mapOf("Date" to "new")))

        val result = SemanticCompatibilityComparator(
            CompatibilityCompareOptions(ignoredPaths = setOf("$.request.headers.Date")),
        ).compare("request-1", expected, actual)

        assertEquals(CompatibilityVerdict.PASS, result.verdict)
    }

    @Test
    fun specificityGuardFindsHardcodedWebsite() {
        val hosts = SourceSpecificityGuard.findUnexpectedHosts(
            "if (url == \"https://fiction.example.com/a\") return specialCase()",
        )

        assertTrue("fiction.example.com" in hosts)
    }
}
