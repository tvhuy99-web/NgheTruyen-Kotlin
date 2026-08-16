package vn.nghetruyen.app.ai.vietphrase


enum class VietPhraseDictionaryKind(val fileName: String, val basePriority: Int) {
    LUAT_NHAN("LuatNhan.txt", 50),
    PRONOUNS("Pronouns.txt", 45),
    PHIEN_AM("ChinesePhienAmWords.txt", 10),
    LAC_VIET("LacViet.txt", 20),
    VIET_PHRASE("VietPhrase.txt", 30),
    NAMES("Names.txt", 40),
    AI_REPLACE("AIReplace.txt", 70);

    companion object {
        fun fromFileName(name: String): VietPhraseDictionaryKind? {
            val clean = name.substringAfterLast('/').substringAfterLast('\\').lowercase()
            return entries.firstOrNull { it.fileName.lowercase() == clean }
                ?: when (clean.substringBeforeLast('.', clean).replace(Regex("[^a-z0-9]"), "")) {
                    "luatnhan", "rules" -> LUAT_NHAN
                    "pronouns", "pronoun" -> PRONOUNS
                    "chinesephienamwords", "chinesephienamword", "phienam", "hanviet", "hv" -> PHIEN_AM
                    "lacviet" -> LAC_VIET
                    "vietphrase", "vp" -> VIET_PHRASE
                    "names", "name", "ne" -> NAMES
                    "aireplace", "aivietphrase", "vietphraseai", "aicorrections" -> AI_REPLACE
                    else -> null
                }
        }
    }
}

enum class VietPhraseScope { GLOBAL, STORY }
enum class VietPhraseMatchMode { LITERAL, TEMPLATE }

data class VietPhraseRule(
    val id: String,
    val source: String,
    val target: String,
    val kind: VietPhraseDictionaryKind = VietPhraseDictionaryKind.VIET_PHRASE,
    val priority: Int = 0,
    val enabled: Boolean = true,
    val scope: VietPhraseScope = VietPhraseScope.GLOBAL,
    val storyId: String? = null,
    val matchMode: VietPhraseMatchMode = if (PLACEHOLDER.containsMatchIn(source)) VietPhraseMatchMode.TEMPLATE else VietPhraseMatchMode.LITERAL,
    val ignoreCase: Boolean = false,
    val updatedAt: Long = 0L,
) {
    init {
        require(source.isNotBlank()) { "Cụm nguồn không được để trống." }
        require(source.length <= 2_000) { "Cụm nguồn quá dài." }
        require(target.length <= 4_000) { "Cụm thay thế quá dài." }
        require(scope != VietPhraseScope.STORY || !storyId.isNullOrBlank()) { "Quy tắc theo truyện phải có storyId." }
    }

    val effectivePriority: Int get() = kind.basePriority * 1_000 + priority.coerceIn(-999, 999)

    companion object {
        private val PLACEHOLDER = Regex("\\{\\d+}")
    }
}

data class VietPhraseOptions(
    val storyId: String? = null,
    val useRules: Boolean = true,
    val oneMeaning: Boolean = true,
    val normalizePunctuation: Boolean = true,
    val capitalizeSentences: Boolean = true,
    val fallbackHanViet: Boolean = true,
    val traceLimit: Int = 2_000,
    val diagnosticProbeLimit: Int = 0,
)

data class VietPhraseTraceEntry(
    val inputStart: Int,
    val inputEnd: Int,
    val source: String,
    val replacement: String,
    val kind: VietPhraseDictionaryKind?,
    val ruleId: String?,
    val captures: Map<Int, String> = emptyMap(),
)

data class VietPhraseProbeEntry(
    val position: Int,
    val phase: String,
    val kind: VietPhraseDictionaryKind?,
    val ruleId: String?,
    val outcome: String,
    val detail: String = "",
)

data class VietPhraseEngineDiagnostics(
    val cursorPositions: Int = 0,
    val literalLookups: Int = 0,
    val literalCandidates: Int = 0,
    val directSelections: Int = 0,
    val templateCandidates: Int = 0,
    val templateAttempts: Int = 0,
    val templateMatches: Int = 0,
    val templateSelections: Int = 0,
    val templateBudgetExceeded: Int = 0,
    val captureCandidates: Int = 0,
    val fallbackSelections: Int = 0,
    val unmatchedCodePoints: Int = 0,
    val aiReplaceSelections: Int = 0,
    val multiMeaningSelections: Int = 0,
    val probes: List<VietPhraseProbeEntry> = emptyList(),
    val probesTruncated: Boolean = false,
)

data class VietPhraseResult(
    val text: String,
    val trace: List<VietPhraseTraceEntry>,
    val traceTruncated: Boolean,
    val appliedByKind: Map<VietPhraseDictionaryKind, Int>,
    val diagnostics: VietPhraseEngineDiagnostics = VietPhraseEngineDiagnostics(),
)

data class VietPhraseConflict(
    val severity: Severity,
    val code: String,
    val message: String,
    val ruleIds: List<String>,
) {
    enum class Severity { INFO, WARNING, ERROR }
}
