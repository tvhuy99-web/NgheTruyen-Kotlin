package vn.nghetruyen.app.ai

import vn.nghetruyen.app.ai.vietphrase.VietPhraseDictionaryKind
import vn.nghetruyen.app.ai.vietphrase.VietPhraseEngine
import vn.nghetruyen.app.ai.vietphrase.VietPhraseOptions
import vn.nghetruyen.app.ai.vietphrase.VietPhraseRule
import vn.nghetruyen.app.data.local.VietPhraseEntity

 
object VietPhraseProcessor {
    fun apply(text: String, rules: List<VietPhraseEntity>): String {
        if (text.isBlank()) return text
        val advanced = rules.map { item ->
            VietPhraseRule(
                id = "legacy:${item.id}:${item.source}",
                source = item.source,
                target = item.target,
                kind = VietPhraseDictionaryKind.VIET_PHRASE,
                priority = item.priority,
                enabled = item.enabled,
                ignoreCase = true,
                updatedAt = item.updatedAt,
            )
        }
        return VietPhraseEngine(advanced).translate(
            text,
            VietPhraseOptions(normalizePunctuation = false, capitalizeSentences = false),
        )
    }
}
