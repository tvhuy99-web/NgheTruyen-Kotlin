package vn.nghetruyen.app.ai.vietphrase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VietPhraseEngineTest {
    private fun rule(
        id: String,
        source: String,
        target: String,
        kind: VietPhraseDictionaryKind = VietPhraseDictionaryKind.VIET_PHRASE,
        priority: Int = 0,
        scope: VietPhraseScope = VietPhraseScope.GLOBAL,
        storyId: String? = null,
    ) = VietPhraseRule(id, source, target, kind, priority, true, scope, storyId)

    @Test fun dictionaryPriorityAndLongestMatchAreDeterministic() {
        val engine = VietPhraseEngine(listOf(
            rule("vp-short", "天", "thiên"),
            rule("vp-long", "天道", "thiên đạo"),
            rule("name", "天道", "Thiên Đạo", VietPhraseDictionaryKind.NAMES),
        ))
        assertEquals("Thiên Đạo vận hành", engine.translate("天道 vận hành"))
    }

    @Test fun luatNhanCapturesNamesAndPronouns() {
        val engine = VietPhraseEngine(listOf(
            rule("name", "叶凡", "Diệp Phàm", VietPhraseDictionaryKind.NAMES),
            rule("pronoun", "他", "hắn", VietPhraseDictionaryKind.PRONOUNS),
            rule("rule", "{0}看着{1}", "{0} nhìn {1}", VietPhraseDictionaryKind.LUAT_NHAN),
        ))
        assertEquals("Diệp Phàm nhìn hắn.", engine.translate("叶凡看着他。"))
    }

    @Test fun aiReplaceRunsOnceAfterBaseTranslationWithoutLoop() {
        val engine = VietPhraseEngine(listOf(
            rule("base", "天道", "Thiên Đạo"),
            rule("ai1", "Thiên Đạo", "Đạo Trời", VietPhraseDictionaryKind.AI_REPLACE),
            rule("ai2", "Đạo Trời", "không được cascade", VietPhraseDictionaryKind.AI_REPLACE),
        ))
        assertEquals("Đạo Trời", engine.translate("天道"))
    }

    @Test fun storyScopedRuleOnlyAffectsItsStory() {
        val engine = VietPhraseEngine(listOf(
            rule("global", "宗门", "tông môn"),
            rule("story", "宗门", "thánh địa", scope = VietPhraseScope.STORY, storyId = "s1", priority = 100),
        ))
        assertEquals("Thánh địa", engine.translate("宗门", VietPhraseOptions(storyId = "s1")))
        assertEquals("Tông môn", engine.translate("宗门", VietPhraseOptions(storyId = "s2")))
    }

    @Test fun traceAndSnapshotAreVerifiable() {
        val rules = listOf(rule("name", "叶凡", "Diệp Phàm", VietPhraseDictionaryKind.NAMES))
        val result = VietPhraseEngine(rules).translateWithTrace("叶凡")
        assertEquals(1, result.trace.size)
        assertEquals(VietPhraseDictionaryKind.NAMES, result.trace.single().kind)
        val snapshot = VietPhraseAudit.snapshot(rules, 123L)
        assertTrue(VietPhraseAudit.verify(snapshot))
        assertFalse(VietPhraseAudit.verify(snapshot.copy(checksum = "00")))
    }
}
