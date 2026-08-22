from pathlib import Path
import gzip


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"{label}: anchor not found in {path}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# 1) Lazily invalidate the exact legacy TruyenFull cache signature produced by the old parser.
lib = Path("app/src/main/java/vn/nghetruyen/app/data/repository/LibraryRepository.kt")
old_cache = '''    suspend fun loadCachedChapter(chapterId: String): ChapterContent? =
        db.chapterDao().get(chapterId)?.toContentWithNeighbors()

    suspend fun loadCachedChapterByUrl(storyId: String, remoteUrl: String): ChapterContent? =
        db.chapterDao().getByRemoteUrl(storyId, remoteUrl)?.toContentWithNeighbors()

    suspend fun loadNextCachedChapter(storyId: String, chapterIndex: Int): ChapterContent? =
        db.chapterDao().getNextAfter(storyId, chapterIndex)?.toContentWithNeighbors()

    suspend fun loadPreviousCachedChapter(storyId: String, chapterIndex: Int): ChapterContent? =
        db.chapterDao().getPreviousBefore(storyId, chapterIndex)?.toContentWithNeighbors()
'''
new_cache = '''    suspend fun loadCachedChapter(chapterId: String): ChapterContent? =
        loadValidatedCachedChapter(db.chapterDao().get(chapterId))

    suspend fun loadCachedChapterByUrl(storyId: String, remoteUrl: String): ChapterContent? =
        loadValidatedCachedChapter(db.chapterDao().getByRemoteUrl(storyId, remoteUrl))

    suspend fun loadNextCachedChapter(storyId: String, chapterIndex: Int): ChapterContent? =
        loadValidatedCachedChapter(db.chapterDao().getNextAfter(storyId, chapterIndex))

    suspend fun loadPreviousCachedChapter(storyId: String, chapterIndex: Int): ChapterContent? =
        loadValidatedCachedChapter(db.chapterDao().getPreviousBefore(storyId, chapterIndex))

    private suspend fun loadValidatedCachedChapter(entity: ChapterEntity?): ChapterContent? {
        val stored = entity ?: return null
        val content = stored.toContentWithNeighbors() ?: return null
        if (!isLegacyBrokenTruyenFullCache(content)) return content
        // Old TruyenFull parser builds cached only the two hidden boilerplate paragraphs. Drop only
        // that exact stale signature; legitimate short chapters and explicit downloads stay intact.
        db.chapterDao().clearContent(stored.id)
        return null
    }

    private fun isLegacyBrokenTruyenFullCache(content: ChapterContent): Boolean {
        if (!content.chapter.url.contains("truyenfull.", ignoreCase = true)) return false
        if (content.paragraphs.isEmpty() || content.paragraphs.size > 3) return false
        var residue = content.paragraphs.joinToString(" ")
            .lowercase(Locale.ROOT)
            .replace(Regex("""[\\s,.;:|/_-]+"""), "")
        listOf("truyenfulllive", "truyenfullvn", "truyenfull").forEach { token ->
            residue = residue.replace(token, "")
        }
        return residue.isBlank()
    }
'''
replace_once(lib, old_cache, new_cache, "cache validation")

# 5) The caller's canonical storyId must win on every TOC page.
sp = Path("app/src/main/java/vn/nghetruyen/app/sourceplatform/SourcePackStorySource.kt")
replace_once(
    sp,
    '                storyId = chapter.storyId.ifBlank { storyId },\n',
    '                storyId = storyId.ifBlank { chapter.storyId },\n',
    "chapter page canonical storyId",
)

runtime = Path("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookJsRuntime.kt")
text = runtime.read_text(encoding="utf-8")
old_toc = '''            SourceActionName.TOC, SourceActionName.TOC_PAGES -> {
                val rawItems = when (unwrapped) {
'''
new_toc = '''            SourceActionName.TOC, SourceActionName.TOC_PAGES -> {
                val canonicalStoryId = request.input.string("storyId").orEmpty()
                val rawItems = when (unwrapped) {
'''
if old_toc not in text:
    raise SystemExit("VBook TOC canonical id anchor not found")
text = text.replace(old_toc, new_toc, 1)
text = text.replace(
    '                        normalizeChapter(value, index, request.input.string("url").orEmpty())\n',
    '                        normalizeChapter(value, index, request.input.string("url").orEmpty(), canonicalStoryId)\n',
    1,
)
old_sig = '    private fun normalizeChapter(value: JsonValue, index: Int, storyUrl: String): JsonValue.Obj? {'
new_sig = '    private fun normalizeChapter(value: JsonValue, index: Int, storyUrl: String, canonicalStoryId: String = ""): JsonValue.Obj? {'
if old_sig not in text:
    raise SystemExit("normalizeChapter signature anchor not found")
text = text.replace(old_sig, new_sig, 1)
text = text.replace(
    '            "id" to JsonValue.Str(stableId(url)), "storyId" to JsonValue.Str(stableId(storyUrl)),\n',
    '            "id" to JsonValue.Str(stableId(url)), "storyId" to JsonValue.Str(canonicalStoryId.ifBlank { stableId(storyUrl) }),\n',
    1,
)
runtime.write_text(text, encoding="utf-8")

# 7) Persist only source install/package/trust/store/security failures, not runtime imports like Freesound.
diag = Path("app/src/main/java/vn/nghetruyen/app/sourceplatform/SourceDiagnosticRuntime.kt")
text = diag.read_text(encoding="utf-8")
old_policy = '''internal object PersistentCriticalDiagnosticPolicy {
    fun shouldPersist(event: DiagnosticEvent): Boolean {
        if (isObsolete(event)) return false
        if (event.severity !in setOf(DiagnosticSeverity.ERROR, DiagnosticSeverity.WARN)) return false
        val name = event.name.uppercase()
        return event.category in setOf(
            DiagnosticCategory.PACKAGE,
            DiagnosticCategory.TRUST,
            DiagnosticCategory.STORE,
            DiagnosticCategory.SECURITY,
        ) || listOf("INSTALL", "IMPORT", "PACKAGE", "REPOSITORY").any(name::contains)
    }

    fun isObsolete(event: DiagnosticEvent): Boolean =
        event.name == "BUILTIN_SOURCEPACK_BOOTSTRAP_FAILED" &&
            event.sourceId == "builtin:demo.ntsource"
}
'''
new_policy = '''internal object PersistentCriticalDiagnosticPolicy {
    fun shouldPersist(event: DiagnosticEvent): Boolean {
        if (isObsolete(event)) return false
        if (event.severity !in setOf(DiagnosticSeverity.ERROR, DiagnosticSeverity.WARN)) return false
        val name = event.name.uppercase(Locale.ROOT)
        val durableCategory = event.category in setOf(
            DiagnosticCategory.PACKAGE,
            DiagnosticCategory.TRUST,
            DiagnosticCategory.STORE,
            DiagnosticCategory.SECURITY,
        )
        // Runtime operations can legitimately contain words such as IMPORT (for example
        // FREESOUND_IMPORT_FAILED). They are session diagnostics, not durable install failures.
        val sourceLifecycleFailure = name.startsWith("SOURCE_") &&
            listOf("INSTALL", "IMPORT", "PACKAGE", "REPOSITORY").any(name::contains)
        return durableCategory || sourceLifecycleFailure
    }

    fun isObsolete(event: DiagnosticEvent): Boolean =
        event.name == "BUILTIN_SOURCEPACK_BOOTSTRAP_FAILED" &&
            event.sourceId == "builtin:demo.ntsource"
}
'''
if old_policy not in text:
    raise SystemExit("persistent policy block not found")
text = text.replace(old_policy, new_policy, 1)
old_purge = '''        val retained = existing.filterNot { line ->
            parseDiagnosticEventLine(line)?.let(PersistentCriticalDiagnosticPolicy::isObsolete) == true
        }
        if (retained.size != existing.size) rewrite(retained.takeLast(MAX_EVENTS))
'''
new_purge = '''        val retained = existing.filter { line ->
            parseDiagnosticEventLine(line)?.let(PersistentCriticalDiagnosticPolicy::shouldPersist) == true
        }
        if (retained.size != existing.size) rewrite(retained.takeLast(MAX_EVENTS))
'''
if old_purge not in text:
    raise SystemExit("persistent purge block not found")
text = text.replace(old_purge, new_purge, 1)
diag.write_text(text, encoding="utf-8")

# 8) Count a dropped-data attribute only when its value actually reports loss (>0/true/non-empty reason).
deep = Path("app/src/main/java/vn/nghetruyen/app/sourceplatform/DiagnosticDeepBlackBox.kt")
text = deep.read_text(encoding="utf-8")
old_loss = '''        put("eventsReportingDroppedData", events.count { event ->
            event.attributes.keys.any { key -> key.contains("drop", true) || key.contains("evict", true) || key.contains("reject", true) }
        })
        put("lossVisible", recorderStats.evictedEvents > 0 || evidenceStats.evictedItems > 0 || evidenceStats.truncatedItems > 0)
'''
new_loss = '''        val eventsReportingDroppedData = events.count(::reportsDroppedData)
        put("eventsReportingDroppedData", eventsReportingDroppedData)
        put(
            "lossVisible",
            recorderStats.evictedEvents > 0 || evidenceStats.evictedItems > 0 ||
                evidenceStats.truncatedItems > 0 || eventsReportingDroppedData > 0,
        )
'''
if old_loss not in text:
    raise SystemExit("data loss count block not found")
text = text.replace(old_loss, new_loss, 1)
anchor = '''    private fun flowLogs(events: List<DiagnosticEvent>): Map<String, String> = events
'''
helper = '''    private fun reportsDroppedData(event: DiagnosticEvent): Boolean = event.attributes.any { (key, rawValue) ->
        if (!key.contains("drop", true) && !key.contains("evict", true) && !key.contains("reject", true)) {
            return@any false
        }
        val value = rawValue.trim()
        value.toLongOrNull()?.let { return@any it > 0L }
        when (value.lowercase(Locale.ROOT)) {
            "", "0", "false", "no", "none", "null" -> false
            "true", "yes" -> true
            else -> true
        }
    }

    private fun flowLogs(events: List<DiagnosticEvent>): Map<String, String> = events
'''
if anchor not in text:
    raise SystemExit("data loss helper anchor not found")
text = text.replace(anchor, helper, 1)
deep.write_text(text, encoding="utf-8")

# 4 + 9) Fix the actual built-in TruyenFull Native source and bump its version so installed v13 refreshes.
asset = Path("app/src/main/assets/source-lua/nguon_truyenfull_native.lua.gz.gz")
outer = gzip.decompress(asset.read_bytes())
source = gzip.decompress(outer).decode("utf-8")
required = {
    '    version = 13,': '    version = 14,',
    '    updated = "2026-08-02"': '    updated = "2026-08-22"',
    'title = ctx.regex.replace(title, "\\\\s+[|-]\\\\s+Truyện Full.*$", "")': 'title = ctx.regex.replace(title, "\\\\s+[|-]\\\\s+(?:Truyện\\\\s*Full|Truyen\\\\s*Full|Truyenfull)(?:\\\\.[A-Za-z0-9.-]+)?.*$", "")',
    '"#list-chapter li a"': '"#list-chapter li a[href*=\'/chuong-\']"',
    '"ul.list-chapter li a"': '"ul.list-chapter li a[href*=\'/chuong-\']"',
    '".list-chapter li a"': '".list-chapter li a[href*=\'/chuong-\']"',
}
for old, new in required.items():
    if old not in source:
        raise SystemExit(f"native source anchor not found: {old}")
    source = source.replace(old, new)
asset.write_bytes(gzip.compress(gzip.compress(source.encode("utf-8"))))

# Static sanity checks for this patch (not a test suite).
assert 'version = 14' in source
assert "a[href*='/chuong-']" in source
assert 'Truyenfull' in source
assert 'storyId = storyId.ifBlank { chapter.storyId }' in sp.read_text(encoding='utf-8')
assert 'events.count(::reportsDroppedData)' in deep.read_text(encoding='utf-8')
assert 'FREESOUND_IMPORT_FAILED' not in new_policy
assert 'loadValidatedCachedChapter' in lib.read_text(encoding='utf-8')
print("Applied all six requested diagnostic fixes")
