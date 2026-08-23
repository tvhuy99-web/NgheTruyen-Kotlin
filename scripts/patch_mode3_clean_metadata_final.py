from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, found {count}")
    return text.replace(old, new, 1)

# 1) New Freesound imports must store the same clean semantic description as manual metadata:
#    only type marker + description. Provenance remains derivable from the managed URI and is not
#    allowed to pollute Sắc thái / Dùng / Tránh.
importer_path = Path("app/src/main/java/vn/nghetruyen/app/freesound/FreesoundImporter.kt")
importer = importer_path.read_text(encoding="utf-8")
old_tags = '''        internal fun tagsForImport(
            kind: AudioAssetKind,
            description: String,
            soundId: Int? = null,
            username: String = "",
            license: String = "",
            sourceUrl: String = "",
        ): String {
            val marker = when (kind) {
                AudioAssetKind.MUSIC -> "type:music"
                AudioAssetKind.AMBIENCE -> "type:ambience"
                AudioAssetKind.SFX -> "type:sfx"
            }
            val pieces = mutableListOf(marker)
            description.trim().take(300).takeIf(String::isNotBlank)?.let(pieces::add)
            soundId?.takeIf { it > 0 }?.let { pieces += "freesound_id:$it" }
            username.trim().take(120).takeIf(String::isNotBlank)?.let { pieces += "freesound_user:$it" }
            license.trim().take(240).takeIf(String::isNotBlank)?.let { pieces += "freesound_license:$it" }
            sourceUrl.trim()
                .takeIf { it.startsWith("https://freesound.org/", ignoreCase = true) }
                ?.let { pieces += "freesound_url:$it" }
            return pieces.joinToString(", ")
        }
'''
new_tags = '''        @Suppress("UNUSED_PARAMETER")
        internal fun tagsForImport(
            kind: AudioAssetKind,
            description: String,
            soundId: Int? = null,
            username: String = "",
            license: String = "",
            sourceUrl: String = "",
        ): String {
            val marker = when (kind) {
                AudioAssetKind.MUSIC -> "type:music"
                AudioAssetKind.AMBIENCE -> "type:ambience"
                AudioAssetKind.SFX -> "type:sfx"
            }
            val cleanDescription = description.trim().take(300)
            return if (cleanDescription.isBlank()) marker else "$marker, $cleanDescription"
        }
'''
importer = replace_once(importer, old_tags, new_tags, "clean Freesound import tags")
importer_path.write_text(importer, encoding="utf-8")

# 2) Existing legacy Freesound provenance must never appear in the description editor/copy output.
ui_path = Path("app/src/main/java/vn/nghetruyen/app/ui/components/UnifiedAudioAssetManagerDialog.kt")
ui = ui_path.read_text(encoding="utf-8")
old_desc = '''@Suppress("UNUSED_PARAMETER")
private fun assetDescription(kind: AudioAssetKind, tagsCsv: String): String = stripAssetTypeMarkers(tagsCsv)
'''
new_desc = '''private val legacyFreesoundProvenanceRegex = Regex(
    """(?i)(?:^|[,;]\\s*)freesound_(?:id|user|license|url)\\s*:[^,;]*""",
)

@Suppress("UNUSED_PARAMETER")
private fun assetDescription(kind: AudioAssetKind, tagsCsv: String): String =
    stripAssetTypeMarkers(tagsCsv)
        .replace(legacyFreesoundProvenanceRegex, "")
        .trim()
        .trim(',', ';')
        .trim()
'''
ui = replace_once(ui, old_desc, new_desc, "hide legacy Freesound provenance")
for forbidden in ["take(500)", "draft.size < 500", "draft.size > 500", "Danh sách vượt giới hạn 500 tệp"]:
    if forbidden in ui:
        raise SystemExit(f"audio hard cap still present: {forbidden}")
ui_path.write_text(ui, encoding="utf-8")

# 3) Do not merely loop through a completed 7/7 set. Return the previous valid winner set immediately.
resolver_path = Path("app/src/main/java/vn/nghetruyen/app/freesound/FreesoundAutoAudioResolver.kt")
resolver = resolver_path.read_text(encoding="utf-8")
old_reuse_point = '''        val usableTracksByKind = AudioAssetKind.entries.associateWith { kind ->
            knownTracks.filter { isUsableLibraryTrack(it, kind) }
        }
        val prepared = needs.mapIndexed { index, need ->
'''
new_reuse_point = '''        val usableTracksByKind = AudioAssetKind.entries.associateWith { kind ->
            knownTracks.filter { isUsableLibraryTrack(it, kind) }
        }
        if (completedCycleReuse) {
            val completedResolutions = needs.mapNotNull { need ->
                val lockedId = resolvedTrackIdsByNeed[failedSoundKey(need)] ?: return@mapNotNull null
                val lockedTrack = usableTracksByKind[need.kind].orEmpty().firstOrNull { it.id == lockedId }
                    ?: return@mapNotNull null
                FreesoundAutoResolvedNeed(
                    need = need,
                    trackId = lockedTrack.id,
                    source = if (isManagedFreesoundTrack(lockedTrack)) {
                        FreesoundAutoResolutionSource.FREESOUND.name
                    } else {
                        FreesoundAutoResolutionSource.LIBRARY.name
                    },
                )
            }
            if (completedResolutions.size == needs.size) {
                val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L
                val importedForTransaction = activeResolutionImportedTrackIds.toSet()
                diagnostics += "COMPLETED_CYCLE_REUSE resolved=${completedResolutions.size} networkNeeds=0 imported=${importedForTransaction.size} elapsedMs=$elapsedMs"
                liveDiagnostic(
                    traceId,
                    "FREESOUND_COMPLETED_CYCLE_REUSED",
                    attributes = baseAttributes + mapOf(
                        "resolved" to completedResolutions.size.toString(),
                        "networkNeeds" to "0",
                        "imported" to importedForTransaction.size.toString(),
                        "elapsedMs" to elapsedMs.toString(),
                    ),
                )
                liveDiagnostic(
                    traceId,
                    "FREESOUND_RESOLVE_DONE",
                    attributes = baseAttributes + mapOf(
                        "resolved" to completedResolutions.size.toString(),
                        "unresolved" to "0",
                        "networkNeeds" to "0",
                        "completedCycleReuse" to "true",
                        "imported" to importedForTransaction.size.toString(),
                        "importedThisCall" to "0",
                        "elapsedMs" to elapsedMs.toString(),
                    ),
                )
                return FreesoundAutoResolveResult(
                    resolved = completedResolutions,
                    warnings = emptyList(),
                    importedTrackIds = importedForTransaction,
                    retryableFailure = false,
                    diagnostics = diagnostics.distinct(),
                )
            }
            activeResolutionCycleComplete = false
        }
        val prepared = needs.mapIndexed { index, need ->
'''
resolver = replace_once(resolver, old_reuse_point, new_reuse_point, "short-circuit completed resolver cycle")
resolver_path.write_text(resolver, encoding="utf-8")

# 4) Regression test: provenance arguments may be passed for compatibility but must never enter tagsCsv.
test_path = Path("app/src/test/java/vn/nghetruyen/app/freesound/FreesoundImporterTest.kt")
test = test_path.read_text(encoding="utf-8")
old_test = '''        assertTrue(provenance.contains("freesound_id:123"))
        assertTrue(provenance.contains("freesound_user:fieldrecorder"))
        assertTrue(provenance.contains("freesound_license:Creative Commons 0"))
        assertTrue(provenance.contains("freesound_url:https://freesound.org/people/fieldrecorder/sounds/123/"))
'''
new_test = '''        assertEquals("type:sfx, Close thunder", provenance)
        assertFalse(provenance.contains("freesound_id:"))
        assertFalse(provenance.contains("freesound_user:"))
        assertFalse(provenance.contains("freesound_license:"))
        assertFalse(provenance.contains("freesound_url:"))
        assertFalse(provenance.contains("creativecommons", ignoreCase = true))
        assertFalse(provenance.contains("https://", ignoreCase = true))
'''
test = replace_once(test, old_test, new_test, "update clean metadata regression test")
test_path.write_text(test, encoding="utf-8")
