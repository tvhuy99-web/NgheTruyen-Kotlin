package vn.nghetruyen.app.ai

import org.junit.Assert.assertEquals
import org.junit.Test
import vn.nghetruyen.app.data.local.VietPhraseEntity

class VietPhraseFileCodecTest {
    @Test fun acceptsCanonicalAndLegacyFormats() {
        val records = VietPhraseFileCodec.decode(
            "# comment\nThiên Đạo\tĐạo Trời\t10\ttrue\nKim Đan=Kim Đan",
        )
        assertEquals(2, records.size)
        assertEquals(10, records.first().priority)
    }

    @Test fun escapedTabsAndNewlinesRoundTrip() {
        val encoded = VietPhraseFileCodec.encode(
            listOf(
                VietPhraseEntity(
                    id = 1,
                    source = "A\\B",
                    target = "X\tY\nZ",
                    priority = 3,
                    enabled = true,
                    createdAt = 0L,
                    updatedAt = 0L,
                ),
            ),
        )
        val decoded = VietPhraseFileCodec.decode(encoded).single()
        assertEquals("A\\B", decoded.source)
        assertEquals("X\tY\nZ", decoded.target)
    }

    @Test fun duplicateSourceUsesLastRule() {
        val decoded = VietPhraseFileCodec.decode("AI=ây ai\nai=trí tuệ")
        assertEquals(1, decoded.size)
        assertEquals("trí tuệ", decoded.single().target)
    }
}
