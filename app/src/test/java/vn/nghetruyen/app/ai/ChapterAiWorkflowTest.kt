package vn.nghetruyen.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChapterAiWorkflowTest {
    @Test fun markedTranslationRoundTripsInOriginalOrder() {
        val source = listOf("Đoạn một", "Đoạn hai")
        assertEquals("[[P:0]] Đoạn một\n\n[[P:1]] Đoạn hai", ChapterAiWorkflow.markedParagraphs(source))
        val parsed = ChapterAiWorkflow.parseMarkedParagraphs("[[P:1]] Bản hai\n[[P:0]] Bản một", 2)
        assertEquals(listOf("Bản một", "Bản hai"), parsed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingParagraphIsRejected() {
        ChapterAiWorkflow.parseMarkedParagraphs("[[P:0]] Chỉ một đoạn", 2)
    }

    @Test fun translationFingerprintChangesWithProviderConfiguration() {
        val source = listOf("Một đoạn")
        val first = ChapterAiWorkflow.translationFingerprint(source, "https://a.example/v1/chat", "model-a", "")
        val second = ChapterAiWorkflow.translationFingerprint(source, "https://a.example/v1/chat", "model-b", "")
        val third = ChapterAiWorkflow.translationFingerprint(source, "https://a.example/v1/chat", "model-a", "giữ văn phong cổ")
        assertNotEquals(first, second)
        assertNotEquals(first, third)
    }

    @Test fun cacheHashIncludesOrderAndBoundaries() {
        assertNotEquals(ChapterAiWorkflow.sha256(listOf("ab", "c")), ChapterAiWorkflow.sha256(listOf("a", "bc")))
        assertNotEquals(ChapterAiWorkflow.sha256(listOf("a", "b")), ChapterAiWorkflow.sha256(listOf("b", "a")))
    }
}
