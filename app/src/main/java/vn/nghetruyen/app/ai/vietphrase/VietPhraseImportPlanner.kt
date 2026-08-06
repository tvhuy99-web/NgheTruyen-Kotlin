package vn.nghetruyen.app.ai.vietphrase

import java.util.Locale

/** Immutable import preview. Callers persist [Plan.after] only after all checks and user approval pass. */
object VietPhraseImportPlanner {
    data class Plan(
        val beforeSnapshot: VietPhraseAudit.Snapshot,
        val after: List<VietPhraseRule>,
        val diff: VietPhraseAudit.Diff,
        val conflicts: List<VietPhraseConflict>,
        val replacedKinds: Set<VietPhraseDictionaryKind>,
    ) {
        val canCommit: Boolean get() = conflicts.none { it.severity == VietPhraseConflict.Severity.ERROR }
    }

    fun plan(
        existing: List<VietPhraseRule>,
        incoming: List<VietPhraseRule>,
        replaceKinds: Set<VietPhraseDictionaryKind> = incoming.mapTo(linkedSetOf()) { it.kind },
        createdAt: Long = System.currentTimeMillis(),
    ): Plan {
        require(incoming.size <= MAX_IMPORT_RULES) { "Lượt nhập có quá nhiều quy tắc." }
        val before = VietPhraseAudit.snapshot(existing, createdAt)
        val retained = existing.filterNot { it.kind in replaceKinds && incoming.any { candidate -> sameScope(candidate, it) } }
        val merged = LinkedHashMap<String, VietPhraseRule>()
        (retained + incoming).forEach { merged[key(it)] = it }
        val after = merged.values.toList()
        val conflicts = VietPhraseAudit.inspect(after)
        return Plan(before, after, VietPhraseAudit.diff(existing, after), conflicts, replaceKinds)
    }

    fun commit(plan: Plan): List<VietPhraseRule> {
        require(plan.canCommit) { "Không thể áp dụng khi còn xung đột nghiêm trọng." }
        return plan.after
    }

    fun rollback(plan: Plan): List<VietPhraseRule> {
        require(VietPhraseAudit.verify(plan.beforeSnapshot)) { "Snapshot trước khi nhập không còn toàn vẹn." }
        return plan.beforeSnapshot.rules
    }

    private fun sameScope(left: VietPhraseRule, right: VietPhraseRule): Boolean = left.scope == right.scope && left.storyId == right.storyId
    private fun key(rule: VietPhraseRule): String = listOf(rule.kind, rule.scope, rule.storyId.orEmpty(), rule.source.lowercase(Locale.ROOT), rule.matchMode).joinToString("|")
    const val MAX_IMPORT_RULES = 1_000_000
}
