package vn.nghetruyen.app.ai.vietphrase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VietPhrasePersistenceArchiveCodecTest {
    @Test
    fun roundTripKeepsRulesAndDictionaryState() {
        val rules = listOf(
            VietPhraseRule(
                id = "story-name",
                source = "叶凡",
                target = "Diệp Phàm",
                kind = VietPhraseDictionaryKind.NAMES,
                scope = VietPhraseScope.STORY,
                storyId = "story-1",
                priority = 42,
                ignoreCase = true,
                updatedAt = 123L,
            ),
        )
        val states = listOf(
            VietPhrasePersistenceArchiveCodec.DictionaryState(
                id = "NAMES:STORY:story-1",
                kind = VietPhraseDictionaryKind.NAMES,
                scope = VietPhraseScope.STORY,
                storyId = "story-1",
                enabled = false,
                sourceName = "Names.dic",
                sourceFormat = "DIC_DOTNET_UTF8_GROUPED",
                checksum = "abc",
                entryCount = 1,
                revision = 9,
                importedAt = 8,
            ),
        )
        val archive = VietPhrasePersistenceArchiveCodec.decode(
            VietPhrasePersistenceArchiveCodec.encode(rules, states),
        )
        assertEquals(rules, archive.rules)
        assertEquals(states, archive.dictionaryStates)
        assertFalse(archive.dictionaryStates.single().enabled)
    }

    @Test
    fun readsLegacyRuleOnlyArchive() {
        val rules = listOf(VietPhraseRule("vp", "天道", "Thiên Đạo"))
        val archive = VietPhrasePersistenceArchiveCodec.decodeCompatible(VietPhraseArchiveCodec.encode(rules))
        assertEquals(rules, archive.rules)
        assertEquals(emptyList<VietPhrasePersistenceArchiveCodec.DictionaryState>(), archive.dictionaryStates)
    }
}
