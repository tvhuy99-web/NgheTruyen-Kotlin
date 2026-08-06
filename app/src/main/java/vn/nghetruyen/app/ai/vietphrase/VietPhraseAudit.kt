package vn.nghetruyen.app.ai.vietphrase

import java.security.MessageDigest
import java.util.Locale

object VietPhraseAudit {
    data class Diff(
        val added: List<VietPhraseRule>,
        val removed: List<VietPhraseRule>,
        val changed: List<Pair<VietPhraseRule, VietPhraseRule>>,
        val unchanged: Int,
    )

    data class Snapshot(
        val id: String,
        val createdAt: Long,
        val checksum: String,
        val rules: List<VietPhraseRule>,
    )

    fun inspect(rules: List<VietPhraseRule>): List<VietPhraseConflict> {
        val conflicts = mutableListOf<VietPhraseConflict>()
        val byKey = rules.groupBy(::key)
        byKey.values.filter { it.size > 1 }.forEach { duplicates ->
            val targets = duplicates.map { it.target }.distinct()
            conflicts += VietPhraseConflict(
                severity = if (targets.size > 1) VietPhraseConflict.Severity.ERROR else VietPhraseConflict.Severity.WARNING,
                code = if (targets.size > 1) "CONFLICTING_DUPLICATE" else "DUPLICATE",
                message = if (targets.size > 1) "Cùng cụm nguồn nhưng có nhiều kết quả thay thế." else "Quy tắc bị lặp.",
                ruleIds = duplicates.map { it.id },
            )
        }
        rules.filter { it.matchMode == VietPhraseMatchMode.TEMPLATE }.forEach { rule ->
            val sourceSlots = SLOT.findAll(rule.source).map { it.groupValues[1] }.toSet()
            val targetSlots = SLOT.findAll(rule.target).map { it.groupValues[1] }.toSet()
            if (sourceSlots.isEmpty() || !sourceSlots.containsAll(targetSlots)) {
                conflicts += VietPhraseConflict(VietPhraseConflict.Severity.ERROR, "INVALID_TEMPLATE_SLOT", "Luật Nhân dùng placeholder không tồn tại trong mẫu nguồn.", listOf(rule.id))
            }
        }
        rules.groupBy { it.source.lowercase(Locale.ROOT) }.values.filter { group -> group.map { it.kind }.distinct().size > 1 }.forEach { shadowed ->
            conflicts += VietPhraseConflict(
                VietPhraseConflict.Severity.INFO,
                "LAYER_SHADOWING",
                "Cụm nguồn xuất hiện ở nhiều lớp; engine sẽ chọn theo độ dài và ưu tiên lớp.",
                shadowed.map { it.id },
            )
        }
        val aiSources = rules.filter { it.kind == VietPhraseDictionaryKind.AI_REPLACE }.associateBy { it.source.lowercase(Locale.ROOT) }
        aiSources.values.forEach { rule ->
            if (rule.source.equals(rule.target, ignoreCase = rule.ignoreCase)) {
                conflicts += VietPhraseConflict(VietPhraseConflict.Severity.INFO, "NO_OP", "AIReplace không làm thay đổi nội dung.", listOf(rule.id))
            }
        }
        val visited = mutableSetOf<String>()
        val active = linkedSetOf<String>()
        fun visit(key: String, path: List<String>) {
            if (key in active) {
                val cycleStart = path.indexOf(key).coerceAtLeast(0)
                val cycleKeys = path.drop(cycleStart) + key
                conflicts += VietPhraseConflict(
                    VietPhraseConflict.Severity.WARNING,
                    "AI_REPLACE_CYCLE",
                    "AIReplace tạo chu trình tiềm ẩn; runtime vẫn an toàn vì chỉ áp dụng một lượt.",
                    cycleKeys.mapNotNull { aiSources[it]?.id }.distinct(),
                )
                return
            }
            if (!visited.add(key)) return
            val rule = aiSources[key] ?: return
            val next = rule.target.lowercase(Locale.ROOT)
            if (next !in aiSources) return
            active += key
            visit(next, path + key)
            active -= key
        }
        aiSources.keys.forEach { visit(it, emptyList()) }
        return conflicts.sortedWith(compareByDescending<VietPhraseConflict> { it.severity }.thenBy { it.code }.thenBy { it.ruleIds.firstOrNull().orEmpty() })
    }

    fun diff(before: List<VietPhraseRule>, after: List<VietPhraseRule>): Diff {
        val left = before.associateBy(::key)
        val right = after.associateBy(::key)
        val added = (right.keys - left.keys).mapNotNull(right::get).sortedBy { it.id }
        val removed = (left.keys - right.keys).mapNotNull(left::get).sortedBy { it.id }
        val changed = (left.keys intersect right.keys).mapNotNull { key ->
            val old = left.getValue(key); val new = right.getValue(key)
            (old to new).takeIf { old != new }
        }.sortedBy { it.first.id }
        val unchanged = (left.keys intersect right.keys).size - changed.size
        return Diff(added, removed, changed, unchanged)
    }

    fun snapshot(rules: List<VietPhraseRule>, createdAt: Long = System.currentTimeMillis()): Snapshot {
        val stable = rules.sortedWith(compareBy<VietPhraseRule> { it.kind.name }.thenBy { it.scope.name }.thenBy { it.storyId.orEmpty() }.thenBy { it.source }.thenBy { it.id })
        val checksum = sha256(stable.joinToString("\n") { listOf(it.id, it.source, it.target, it.kind, it.priority, it.enabled, it.scope, it.storyId, it.matchMode, it.ignoreCase, it.updatedAt).joinToString("\u0001") })
        return Snapshot("vp-${createdAt}-${checksum.take(12)}", createdAt, checksum, stable)
    }

    fun verify(snapshot: Snapshot): Boolean = snapshot(snapshot.rules, snapshot.createdAt).checksum == snapshot.checksum

    private fun key(rule: VietPhraseRule): String = listOf(rule.kind, rule.scope, rule.storyId.orEmpty(), rule.source.lowercase(Locale.ROOT), rule.matchMode).joinToString("|")
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private val SLOT = Regex("\\{(\\d+)}")
}
