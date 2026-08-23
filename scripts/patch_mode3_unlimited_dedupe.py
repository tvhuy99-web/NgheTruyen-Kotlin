from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, found {count}")
    return text.replace(old, new, 1)

# 1) Remove the 500-item UI hard cap from the unified MUSIC/AMBIENCE/SFX manager.
ui_path = Path("app/src/main/java/vn/nghetruyen/app/ui/components/UnifiedAudioAssetManagerDialog.kt")
ui = ui_path.read_text(encoding="utf-8")
ui = replace_once(
    ui,
    "draft = (draft + accepted).take(500).mapIndexed { index, row -> row.copy(orderIndex = index) }",
    "draft = (draft + accepted).mapIndexed { index, row -> row.copy(orderIndex = index) }",
    "remove draft take(500)",
)
ui = replace_once(
    ui,
    "                            enabled = draft.size < 500,\n",
    "",
    "remove add-button 500 cap",
)
ui = replace_once(
    ui,
    "                        onClick = {\n                            if (draft.size > 500) {\n                                notify(\"Danh sách vượt giới hạn 500 tệp.\")\n                            } else {\n                                stopPreview()",
    "                        onClick = {\n                            stopPreview()",
    "remove save-time 500 guard opening",
)
ui = replace_once(
    ui,
    "                                    notify(\"Đã lưu ${kindDisplayName(kind).lowercase()}.\")\n                                    onDismiss()\n                                }\n                            }\n                        },",
    "                                    notify(\"Đã lưu ${kindDisplayName(kind).lowercase()}.\")\n                                    onDismiss()\n                                }\n                        },",
    "remove save-time 500 guard closing",
)
for forbidden in ["take(500)", "draft.size < 500", "draft.size > 500", "Danh sách vượt giới hạn 500 tệp"]:
    if forbidden in ui:
        raise SystemExit(f"UI hard cap still present: {forbidden}")
ui_path.write_text(ui, encoding="utf-8")

# 2) Keep a completed resolver cycle alive across an immediate duplicate attempt-1 call.
#    This makes a repeated 7/7 set reuse all winners and skip network entirely.
resolver_path = Path("app/src/main/java/vn/nghetruyen/app/freesound/FreesoundAutoAudioResolver.kt")
resolver = resolver_path.read_text(encoding="utf-8")
resolver = replace_once(
    resolver,
    "    private val failedSoundIdsByNeed = linkedMapOf<String, MutableSet<Int>>()\n    private val resolvedTrackIdsByNeed = linkedMapOf<String, String>()\n",
    "    private val failedSoundIdsByNeed = linkedMapOf<String, MutableSet<Int>>()\n    private val resolvedTrackIdsByNeed = linkedMapOf<String, String>()\n    private var activeResolutionCycleKey: String? = null\n    private var activeResolutionCycleComplete: Boolean = false\n    private val activeResolutionImportedTrackIds = linkedSetOf<String>()\n",
    "add resolver cycle state",
)
resolver = replace_once(
    resolver,
    "    private fun forgetResolvedTrack(need: FreesoundAutoSearchNeed) {\n        resolvedTrackIdsByNeed.remove(failedSoundKey(need))\n    }\n\n    private fun liveDiagnostic(",
    "    private fun forgetResolvedTrack(need: FreesoundAutoSearchNeed) {\n        resolvedTrackIdsByNeed.remove(failedSoundKey(need))\n    }\n\n    private fun resolutionCycleKey(needs: List<FreesoundAutoSearchNeed>): String =\n        needs.joinToString(\"\\u001e\") { need ->\n            buildString {\n                append(need.kind.name)\n                append('\\u001f')\n                append(FreesoundAutoRequirementAggregator.normalizeQuery(need.query))\n                append('\\u001f')\n                append(need.importance.name)\n                need.usages.forEach { usage ->\n                    append('\\u001d').append(usage.localContext.trim())\n                    append('\\u001c').append(usage.startUnitId.orEmpty())\n                    append('\\u001c').append(usage.endUnitId.orEmpty())\n                    append('\\u001c').append(usage.unitId.orEmpty())\n                    append('\\u001c').append(usage.stopUnitId.orEmpty())\n                    append('\\u001c').append(usage.repeatCount)\n                    append('\\u001c').append(usage.cadence.name)\n                    append('\\u001c').append(usage.loopUntilStop)\n                }\n            }\n        }\n\n    private fun clearRuntimeResolutionState() {\n        failedSoundIdsByNeed.clear()\n        resolvedTrackIdsByNeed.clear()\n        activeResolutionCycleKey = null\n        activeResolutionCycleComplete = false\n        activeResolutionImportedTrackIds.clear()\n    }\n\n    private fun liveDiagnostic(",
    "add resolver cycle fingerprint helpers",
)
resolver = replace_once(
    resolver,
    "    fun clearResolutionCaches() {\n        client.clearSearchCache()\n    }",
    "    fun clearResolutionCaches() {\n        clearRuntimeResolutionState()\n        client.clearSearchCache()\n    }",
    "clear runtime cycle with resolution caches",
)
resolver = replace_once(
    resolver,
    "        val traceId = \"freesound-resolve:${UUID.randomUUID()}\"\n        val needs = FreesoundAutoRequirementAggregator.aggregate(requirements)\n        if (retryAttempt <= 1) {\n            failedSoundIdsByNeed.clear()\n            resolvedTrackIdsByNeed.clear()\n        }\n        val baseAttributes = mapOf(",
    "        val traceId = \"freesound-resolve:${UUID.randomUUID()}\"\n        val needs = FreesoundAutoRequirementAggregator.aggregate(requirements)\n        val cycleKey = resolutionCycleKey(needs)\n        val completedCycleReuse = retryAttempt <= 1 &&\n            activeResolutionCycleComplete &&\n            activeResolutionCycleKey == cycleKey\n        if (activeResolutionCycleKey != cycleKey || (retryAttempt <= 1 && !completedCycleReuse)) {\n            clearRuntimeResolutionState()\n            activeResolutionCycleKey = cycleKey\n        }\n        val baseAttributes = mapOf(",
    "preserve completed cycle on duplicate attempt1",
)
resolver = replace_once(
    resolver,
    "        diagnostics += \"RESOLVE_START requirements=${requirements.size} aggregated=${needs.size} parallelSearchLimit=${FreesoundParallelSearchPolicy.MAX_PARALLEL_SEARCHES} parallelImportLimit=${FreesoundParallelImportPolicy.MAX_PARALLEL_IMPORTS}\"",
    "        diagnostics += \"RESOLVE_START requirements=${requirements.size} aggregated=${needs.size} completedCycleReuse=$completedCycleReuse parallelSearchLimit=${FreesoundParallelSearchPolicy.MAX_PARALLEL_SEARCHES} parallelImportLimit=${FreesoundParallelImportPolicy.MAX_PARALLEL_IMPORTS}\"",
    "diagnose completed cycle reuse",
)
resolver = replace_once(
    resolver,
    "            if (retryAttempt > 1) {",
    "            if (retryAttempt > 1 || completedCycleReuse) {",
    "reuse locked winners on duplicate completed cycle",
)
resolver = replace_once(
    resolver,
    "                        strategy = \"LOCKED_SUCCESS\",",
    "                        strategy = if (completedCycleReuse) \"COMPLETED_CYCLE_REUSE\" else \"LOCKED_SUCCESS\",",
    "label duplicate completed cycle",
)
resolver = replace_once(
    resolver,
    "        val clientSearches = searched.sumOf { it.search?.requestCount ?: 0 }\n        diagnostics += \"RESOLVE_DONE",
    "        val clientSearches = searched.sumOf { it.search?.requestCount ?: 0 }\n        activeResolutionImportedTrackIds += imported\n        activeResolutionCycleComplete = resolutions.isNotEmpty() && resolutions.all { !it.trackId.isNullOrBlank() }\n        val transactionImportedTrackIds = activeResolutionImportedTrackIds.toSet()\n        diagnostics += \"RESOLVE_DONE",
    "aggregate transaction imports",
)
resolver = replace_once(
    resolver,
    " imported=${imported.size} importElapsedTotalMs=$importElapsedTotalMs",
    " imported=${transactionImportedTrackIds.size} importedThisCall=${imported.size} importElapsedTotalMs=$importElapsedTotalMs",
    "diagnostic transaction import count",
)
resolver = replace_once(
    resolver,
    "                \"imported\" to imported.size.toString(),",
    "                \"imported\" to transactionImportedTrackIds.size.toString(),\n                \"importedThisCall\" to imported.size.toString(),\n                \"completedCycleReuse\" to completedCycleReuse.toString(),",
    "live diagnostic transaction import count",
)
resolver = replace_once(
    resolver,
    "            importedTrackIds = imported,",
    "            importedTrackIds = transactionImportedTrackIds,",
    "return transaction imported IDs",
)
resolver_path.write_text(resolver, encoding="utf-8")

# 3) A forced rebuild must explicitly start a fresh resolver cycle even if requirements are identical.
coord_path = Path("app/src/main/java/vn/nghetruyen/app/ai/NarrationPlanCoordinator.kt")
coord = coord_path.read_text(encoding="utf-8")
coord = replace_once(
    coord,
    "        val warnings = mutableListOf<String>()\n        val sourceMode = storyAudioModeStore.get()",
    "        val warnings = mutableListOf<String>()\n        if (force) freesoundResolver.clearResolutionCaches()\n        val sourceMode = storyAudioModeStore.get()",
    "force starts fresh resolver cycle",
)
coord_path.write_text(coord, encoding="utf-8")

print("Patched unlimited audio library, duplicate completed-cycle reuse, and transaction download stats.")
