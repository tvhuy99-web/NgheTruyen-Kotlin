package vn.nghetruyen.app.ai.vietphrase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VietPhraseArchiveCodecTest {
    @Test
    fun roundTripKeepsAdvancedFields() {
        val rules = listOf(
            VietPhraseRule(
                id = "story-rule",
                source = "宗门",
                target = "thánh địa",
                kind = VietPhraseDictionaryKind.NAMES,
                priority = 99,
                enabled = false,
                scope = VietPhraseScope.STORY,
                storyId = "story-1",
                matchMode = VietPhraseMatchMode.LITERAL,
                ignoreCase = true,
                updatedAt = 1234,
            ),
        )
        val encoded = VietPhraseArchiveCodec.encode(rules)
        val decoded = VietPhraseArchiveCodec.decode(encoded)
        assertEquals(rules, decoded.rules)
        assertEquals(VietPhraseArchiveCodec.checksum(rules), decoded.checksum)
        assertTrue(runCatching { VietPhraseArchiveCodec.decode(encoded.copyOf(encoded.size / 2)) }.isFailure)
    }
}
