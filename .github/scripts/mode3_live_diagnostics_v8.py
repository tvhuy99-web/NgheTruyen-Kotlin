from pathlib import Path


def read(path):
    return Path(path).read_text(encoding="utf-8")


def write(path, text):
    Path(path).write_text(text, encoding="utf-8")


def replace_once(path, old, new, label):
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 occurrence, found {count}")
    write(path, text.replace(old, new, 1))


# 1) Stream Freesound resolver progress into SourceDiagnostics immediately.
path = "app/src/main/java/vn/nghetruyen/app/freesound/FreesoundAutoAudioResolver.kt"
replace_once(
    path,
    "import android.content.Context\nimport java.security.MessageDigest\nimport kotlin.math.max\n",
    "import android.content.Context\nimport java.security.MessageDigest\nimport java.util.UUID\nimport kotlin.math.max\nimport vn.nghetruyen.app.NgheTruyenApplication\n",
    "resolver imports",
)
replace_once(
    path,
    "import vn.nghetruyen.app.data.settings.SettingsRepository\n",
    "import vn.nghetruyen.app.data.settings.SettingsRepository\nimport vn.nghetruyen.source.diagnostics.DiagnosticCategory\nimport vn.nghetruyen.source.diagnostics.DiagnosticSeverity\n",
    "resolver diagnostic imports",
)
replace_once(
    path,
    """    private val importer = FreesoundImporter(\n        context = appContext,\n        repository = repository,\n        existingTracksProvider = existingTracksProvider,\n    )\n\n    suspend fun usableManagedTrackIds""",
    """    private val importer = FreesoundImporter(\n        context = appContext,\n        repository = repository,\n        existingTracksProvider = existingTracksProvider,\n    )\n\n    private fun liveDiagnostic(\n        traceId: String,\n        name: String,\n        severity: DiagnosticSeverity = DiagnosticSeverity.INFO,\n        attributes: Map<String, String> = emptyMap(),\n    ) {\n        (appContext as? NgheTruyenApplication)?.container?.sourceDiagnostics?.mark(\n            name = name,\n            category = DiagnosticCategory.RUNTIME,\n            severity = severity,\n            sourceId = \"freesound\",\n            traceId = traceId,\n            attributes = attributes,\n        )\n    }\n\n    suspend fun usableManagedTrackIds""",
    "resolver live helper",
)
replace_once(
    path,
    """    suspend fun resolve(requirements: List<FreesoundAutoRequirement>): FreesoundAutoResolveResult {\n        val startedNanos = System.nanoTime()\n        val needs = FreesoundAutoRequirementAggregator.aggregate(requirements)\n        val diagnostics = mutableListOf<String>()\n        diagnostics += \"RESOLVE_START requirements=${requirements.size} aggregated=${needs.size}\"""",
    """    suspend fun resolve(\n        requirements: List<FreesoundAutoRequirement>,\n        retryAttempt: Int = 1,\n        retryMax: Int = 1,\n    ): FreesoundAutoResolveResult {\n        val startedNanos = System.nanoTime()\n        val traceId = \"freesound-resolve:${UUID.randomUUID()}\"\n        val needs = FreesoundAutoRequirementAggregator.aggregate(requirements)\n        val baseAttributes = mapOf(\n            \"retryAttempt\" to retryAttempt.coerceAtLeast(1).toString(),\n            \"retryMax\" to retryMax.coerceAtLeast(1).toString(),\n        )\n        val diagnostics = mutableListOf<String>()\n        diagnostics += \"RESOLVE_START requirements=${requirements.size} aggregated=${needs.size}\"\n        liveDiagnostic(\n            traceId,\n            \"FREESOUND_RESOLVE_START\",\n            attributes = baseAttributes + mapOf(\n                \"requirements\" to requirements.size.toString(),\n                \"aggregatedNeeds\" to needs.size.toString(),\n            ),\n        )""",
    "resolver start",
)
replace_once(
    path,
    """        if (needs.isEmpty()) {\n            diagnostics += \"RESOLVE_EMPTY no aggregated Freesound needs were produced\"\n            return FreesoundAutoResolveResult(""",
    """        if (needs.isEmpty()) {\n            diagnostics += \"RESOLVE_EMPTY no aggregated Freesound needs were produced\"\n            liveDiagnostic(\n                traceId,\n                \"FREESOUND_RESOLVE_EMPTY\",\n                DiagnosticSeverity.WARN,\n                baseAttributes + mapOf(\"requirements\" to requirements.size.toString()),\n            )\n            return FreesoundAutoResolveResult(""",
    "resolver empty",
)
replace_once(
    path,
    """        for ((index, need) in needs.withIndex()) {\n            diagnostics += \"NEED_START index=${index + 1}/${needs.size} kind=${need.kind.name} importance=${need.importance.name} usages=${need.usages.size} query=${need.query.take(160)}\"""",
    """        for ((index, need) in needs.withIndex()) {\n            diagnostics += \"NEED_START index=${index + 1}/${needs.size} kind=${need.kind.name} importance=${need.importance.name} usages=${need.usages.size} query=${need.query.take(160)}\"\n            liveDiagnostic(\n                traceId,\n                \"FREESOUND_NEED_START\",\n                attributes = baseAttributes + mapOf(\n                    \"index\" to (index + 1).toString(),\n                    \"total\" to needs.size.toString(),\n                    \"kind\" to need.kind.name,\n                    \"importance\" to need.importance.name,\n                    \"usages\" to need.usages.size.toString(),\n                    \"query\" to need.query.take(180),\n                ),\n            )""",
    "need start",
)
replace_once(
    path,
    """                diagnostics += \"QUERY_CACHE_HIT kind=${need.kind.name} trackId=${cachedTrack.id} soundId=${FreesoundImporter.soundIdFromManagedUri(cachedTrack.uri)} fileExists=true query=${need.query.take(140)}\"\n                resolutions +=""",
    """                diagnostics += \"QUERY_CACHE_HIT kind=${need.kind.name} trackId=${cachedTrack.id} soundId=${FreesoundImporter.soundIdFromManagedUri(cachedTrack.uri)} fileExists=true query=${need.query.take(140)}\"\n                liveDiagnostic(traceId, \"FREESOUND_QUERY_CACHE_HIT\", attributes = baseAttributes + mapOf(\n                    \"kind\" to need.kind.name, \"trackId\" to cachedTrack.id,\n                    \"soundId\" to FreesoundImporter.soundIdFromManagedUri(cachedTrack.uri).toString(),\n                    \"fileExists\" to \"true\", \"query\" to need.query.take(180),\n                ))\n                resolutions +=""",
    "cache hit",
)
replace_once(
    path,
    """                diagnostics += \"QUERY_CACHE_STALE kind=${need.kind.name} trackId=$cachedId reason=missing_disabled_wrong_kind_or_file_missing query=${need.query.take(140)}\"\n                queryCache.remove""",
    """                diagnostics += \"QUERY_CACHE_STALE kind=${need.kind.name} trackId=$cachedId reason=missing_disabled_wrong_kind_or_file_missing query=${need.query.take(140)}\"\n                liveDiagnostic(traceId, \"FREESOUND_QUERY_CACHE_STALE\", DiagnosticSeverity.WARN, baseAttributes + mapOf(\n                    \"kind\" to need.kind.name, \"trackId\" to cachedId, \"query\" to need.query.take(180),\n                ))\n                queryCache.remove""",
    "cache stale",
)
replace_once(
    path,
    """            diagnostics += \"CLIENT_SEARCH_START index=${index + 1} kind=${need.kind.name} query=${need.query.take(160)}\"\n            val search = searchBest(need)""",
    """            diagnostics += \"CLIENT_SEARCH_START index=${index + 1} kind=${need.kind.name} query=${need.query.take(160)}\"\n            liveDiagnostic(traceId, \"FREESOUND_CLIENT_SEARCH_START\", attributes = baseAttributes + mapOf(\n                \"index\" to (index + 1).toString(), \"total\" to needs.size.toString(),\n                \"kind\" to need.kind.name, \"query\" to need.query.take(180),\n            ))\n            val search = searchBest(need)""",
    "search start",
)
replace_once(
    path,
    """            diagnostics += \"CLIENT_SEARCH_DONE index=${index + 1} kind=${need.kind.name} elapsedMs=$searchElapsedMs resultCount=${search.resultCount} httpCode=${search.httpCode ?: 0} selectedSoundId=${remote?.id ?: 0} failure=${search.failureMessage.orEmpty().take(180)}\"\n            if (!search.failureMessage.isNullOrBlank())""",
    """            diagnostics += \"CLIENT_SEARCH_DONE index=${index + 1} kind=${need.kind.name} elapsedMs=$searchElapsedMs resultCount=${search.resultCount} httpCode=${search.httpCode ?: 0} selectedSoundId=${remote?.id ?: 0} failure=${search.failureMessage.orEmpty().take(180)}\"\n            liveDiagnostic(\n                traceId,\n                \"FREESOUND_CLIENT_SEARCH_DONE\",\n                if (search.failureMessage.isNullOrBlank()) DiagnosticSeverity.INFO else DiagnosticSeverity.WARN,\n                baseAttributes + mapOf(\n                    \"index\" to (index + 1).toString(), \"kind\" to need.kind.name,\n                    \"elapsedMs\" to searchElapsedMs.toString(), \"resultCount\" to search.resultCount.toString(),\n                    \"httpCode\" to (search.httpCode ?: 0).toString(), \"selectedSoundId\" to (remote?.id ?: 0).toString(),\n                    \"failure\" to search.failureMessage.orEmpty().take(220),\n                ),\n            )\n            if (!search.failureMessage.isNullOrBlank())""",
    "search done",
)
replace_once(
    path,
    """                diagnostics += \"IMPORT_START kind=${need.kind.name} soundId=${remote.id} durationSec=${\"%.2f\".format(java.util.Locale.US, remote.durationSeconds)} previewAvailable=${remote.preferredPreviewUrl != null} query=${need.query.take(140)}\"\n                val import = importer.importPreview(""",
    """                diagnostics += \"IMPORT_START kind=${need.kind.name} soundId=${remote.id} durationSec=${\"%.2f\".format(java.util.Locale.US, remote.durationSeconds)} previewAvailable=${remote.preferredPreviewUrl != null} query=${need.query.take(140)}\"\n                liveDiagnostic(traceId, \"FREESOUND_IMPORT_START\", attributes = baseAttributes + mapOf(\n                    \"kind\" to need.kind.name, \"soundId\" to remote.id.toString(),\n                    \"durationSec\" to \"%.2f\".format(java.util.Locale.US, remote.durationSeconds),\n                    \"previewAvailable\" to (remote.preferredPreviewUrl != null).toString(), \"query\" to need.query.take(180),\n                ))\n                val import = importer.importPreview(""",
    "import start",
)
replace_once(
    path,
    """                    diagnostics += \"IMPORT_SUCCESS kind=${need.kind.name} soundId=${remote.id} trackId=${result.trackId} elapsedMs=$importElapsedMs fileExists=${resolvedTrack?.let { FreesoundImporter.managedFileExists(appContext, it.uri) } == true}\"""",
    """                    diagnostics += \"IMPORT_SUCCESS kind=${need.kind.name} soundId=${remote.id} trackId=${result.trackId} elapsedMs=$importElapsedMs fileExists=${resolvedTrack?.let { FreesoundImporter.managedFileExists(appContext, it.uri) } == true}\"\n                    liveDiagnostic(traceId, \"FREESOUND_IMPORT_SUCCESS\", attributes = baseAttributes + mapOf(\n                        \"kind\" to need.kind.name, \"soundId\" to remote.id.toString(), \"trackId\" to result.trackId,\n                        \"elapsedMs\" to importElapsedMs.toString(),\n                        \"fileExists\" to (resolvedTrack?.let { FreesoundImporter.managedFileExists(appContext, it.uri) } == true).toString(),\n                    ))""",
    "import success",
)
replace_once(
    path,
    """                    diagnostics += \"IMPORT_DUPLICATE kind=${need.kind.name} soundId=${remote.id} reusedTrackId=${resolvedTrack?.id.orEmpty()} elapsedMs=$importElapsedMs fileExists=${resolvedTrack != null}\"""",
    """                    diagnostics += \"IMPORT_DUPLICATE kind=${need.kind.name} soundId=${remote.id} reusedTrackId=${resolvedTrack?.id.orEmpty()} elapsedMs=$importElapsedMs fileExists=${resolvedTrack != null}\"\n                    liveDiagnostic(traceId, \"FREESOUND_IMPORT_DUPLICATE\", attributes = baseAttributes + mapOf(\n                        \"kind\" to need.kind.name, \"soundId\" to remote.id.toString(),\n                        \"reusedTrackId\" to resolvedTrack?.id.orEmpty(), \"elapsedMs\" to importElapsedMs.toString(),\n                    ))""",
    "import duplicate",
)
replace_once(
    path,
    """                    diagnostics += \"IMPORT_FAILED kind=${need.kind.name} soundId=${remote.id} elapsedMs=$importElapsedMs retryable=${error is FreesoundNormalizationException && error.retryable} errorType=${error?.javaClass?.simpleName.orEmpty()} error=${message.take(220)}\"""",
    """                    diagnostics += \"IMPORT_FAILED kind=${need.kind.name} soundId=${remote.id} elapsedMs=$importElapsedMs retryable=${error is FreesoundNormalizationException && error.retryable} errorType=${error?.javaClass?.simpleName.orEmpty()} error=${message.take(220)}\"\n                    liveDiagnostic(traceId, \"FREESOUND_IMPORT_FAILED\", DiagnosticSeverity.WARN, baseAttributes + mapOf(\n                        \"kind\" to need.kind.name, \"soundId\" to remote.id.toString(), \"elapsedMs\" to importElapsedMs.toString(),\n                        \"retryable\" to (error is FreesoundNormalizationException && error.retryable).toString(),\n                        \"errorType\" to error?.javaClass?.simpleName.orEmpty(), \"error\" to message.take(240),\n                    ))""",
    "import failed",
)
replace_once(
    path,
    """                diagnostics += \"NEED_RESOLVED kind=${need.kind.name} source=FREESOUND trackId=${resolvedTrack.id} soundId=${FreesoundImporter.soundIdFromManagedUri(resolvedTrack.uri)} query=${need.query.take(140)}\"\n                continue""",
    """                diagnostics += \"NEED_RESOLVED kind=${need.kind.name} source=FREESOUND trackId=${resolvedTrack.id} soundId=${FreesoundImporter.soundIdFromManagedUri(resolvedTrack.uri)} query=${need.query.take(140)}\"\n                liveDiagnostic(traceId, \"FREESOUND_NEED_RESOLVED\", attributes = baseAttributes + mapOf(\n                    \"kind\" to need.kind.name, \"trackId\" to resolvedTrack.id,\n                    \"soundId\" to FreesoundImporter.soundIdFromManagedUri(resolvedTrack.uri).toString(),\n                    \"query\" to need.query.take(180),\n                ))\n                continue""",
    "need resolved",
)
replace_once(
    path,
    """            diagnostics += \"NEED_UNRESOLVED kind=${need.kind.name} query=${need.query.take(160)}\"\n            if (search.failureMessage.isNullOrBlank() && remote == null)""",
    """            diagnostics += \"NEED_UNRESOLVED kind=${need.kind.name} query=${need.query.take(160)}\"\n            liveDiagnostic(traceId, \"FREESOUND_NEED_UNRESOLVED\", DiagnosticSeverity.WARN, baseAttributes + mapOf(\n                \"kind\" to need.kind.name, \"importance\" to need.importance.name, \"query\" to need.query.take(180),\n            ))\n            if (search.failureMessage.isNullOrBlank() && remote == null)""",
    "need unresolved",
)
replace_once(
    path,
    """        diagnostics += \"RESOLVE_DONE requirements=${requirements.size} aggregated=${needs.size} resolved=${resolutions.count { !it.trackId.isNullOrBlank() }} unresolved=${resolutions.count { it.trackId.isNullOrBlank() }} unresolvedRequired=$unresolvedRequired queryCacheHits=$queryCacheHits clientSearches=$clientSearches importAttempts=$importAttempts imported=${imported.size} retryableFailure=$retryableFailure retryRecommended=$retryRecommended elapsedMs=$totalElapsedMs\"\n        return FreesoundAutoResolveResult(""",
    """        diagnostics += \"RESOLVE_DONE requirements=${requirements.size} aggregated=${needs.size} resolved=${resolutions.count { !it.trackId.isNullOrBlank() }} unresolved=${resolutions.count { it.trackId.isNullOrBlank() }} unresolvedRequired=$unresolvedRequired queryCacheHits=$queryCacheHits clientSearches=$clientSearches importAttempts=$importAttempts imported=${imported.size} retryableFailure=$retryableFailure retryRecommended=$retryRecommended elapsedMs=$totalElapsedMs\"\n        liveDiagnostic(\n            traceId,\n            \"FREESOUND_RESOLVE_DONE\",\n            if (retryRecommended) DiagnosticSeverity.WARN else DiagnosticSeverity.INFO,\n            baseAttributes + mapOf(\n                \"requirements\" to requirements.size.toString(), \"aggregatedNeeds\" to needs.size.toString(),\n                \"resolved\" to resolutions.count { !it.trackId.isNullOrBlank() }.toString(),\n                \"unresolved\" to resolutions.count { it.trackId.isNullOrBlank() }.toString(),\n                \"unresolvedRequired\" to unresolvedRequired.toString(), \"queryCacheHits\" to queryCacheHits.toString(),\n                \"clientSearches\" to clientSearches.toString(), \"importAttempts\" to importAttempts.toString(),\n                \"imported\" to imported.size.toString(), \"retryRecommended\" to retryRecommended.toString(),\n                \"elapsedMs\" to totalElapsedMs.toString(),\n            ),\n        )\n        return FreesoundAutoResolveResult(""",
    "resolve done",
)

# 2) Carry retry attempt into each resolver trace so all three runtime attempts are visible live.
path = "app/src/main/java/vn/nghetruyen/app/ai/NarrationPlanCoordinator.kt"
replace_once(
    path,
    "latest = applyFreesoundRequirementsOnce(content, requirements, kinds)",
    "latest = applyFreesoundRequirementsOnce(content, requirements, kinds, attempt)",
    "coordinator retry call",
)
replace_once(
    path,
    """    private suspend fun applyFreesoundRequirementsOnce(\n        content: ChapterContent,\n        requirements: List<FreesoundAutoRequirement>,\n        kinds: Set<AudioAssetKind>,\n    ): FreesoundApplyResult {\n        val resolved = freesoundResolver.resolve(requirements)""",
    """    private suspend fun applyFreesoundRequirementsOnce(\n        content: ChapterContent,\n        requirements: List<FreesoundAutoRequirement>,\n        kinds: Set<AudioAssetKind>,\n        retryAttempt: Int,\n    ): FreesoundApplyResult {\n        val resolved = freesoundResolver.resolve(\n            requirements = requirements,\n            retryAttempt = retryAttempt,\n            retryMax = MAX_FREESOUND_RUNTIME_ATTEMPTS,\n        )""",
    "coordinator retry signature",
)

# 3) Use one stable trace for AI narration START -> COMPLETED so active-operation tracking closes correctly.
path = "app/src/main/java/vn/nghetruyen/app/ai/XpkNarrationAiServices.kt"
replace_once(
    path,
    "import java.io.IOException\nimport java.util.concurrent.TimeUnit\n",
    "import java.io.IOException\nimport java.util.UUID\nimport java.util.concurrent.TimeUnit\n",
    "xpk uuid import",
)
replace_once(
    path,
    """    suspend fun planNarration(request: NarrationPlanRequest): AppResult<NarrationPlan> {\n        diagnostic(\n            \"AI_NARRATION_PLAN_START\",\n            attributes = mapOf(""",
    """    suspend fun planNarration(request: NarrationPlanRequest): AppResult<NarrationPlan> {\n        val narrationTraceId = \"ai-narration:${UUID.randomUUID()}\"\n        diagnostic(\n            \"AI_NARRATION_PLAN_START\",\n            attributes = mapOf(""",
    "xpk narration trace init",
)
replace_once(
    path,
    """                \"inputChars\" to request.rawText.length.toString(),\n            ),\n        )\n        val rawText = request.rawText.trim()""",
    """                \"inputChars\" to request.rawText.length.toString(),\n            ),\n            traceId = narrationTraceId,\n        )\n        val rawText = request.rawText.trim()""",
    "xpk narration start trace",
)
replace_once(
    path,
    """                            \"freesoundRequirements\" to it.freesoundRequirements.size.toString(),\n                        ),\n                    )\n                    AppResult.Success(it)""",
    """                            \"freesoundRequirements\" to it.freesoundRequirements.size.toString(),\n                        ),\n                        traceId = narrationTraceId,\n                    )\n                    AppResult.Success(it)""",
    "xpk narration completed trace",
)
replace_once(
    path,
    """    private fun diagnostic(name: String, severity: DiagnosticSeverity = DiagnosticSeverity.DEBUG, attributes: Map<String, String> = emptyMap()) {\n        (appContext as? NgheTruyenApplication)?.container?.sourceDiagnostics?.mark(\n            name = name,\n            category = DiagnosticCategory.RUNTIME,\n            severity = severity,\n            sourceId = \"ai\",\n            attributes = attributes,\n        )\n    }""",
    """    private fun diagnostic(\n        name: String,\n        severity: DiagnosticSeverity = DiagnosticSeverity.DEBUG,\n        attributes: Map<String, String> = emptyMap(),\n        traceId: String? = null,\n    ) {\n        (appContext as? NgheTruyenApplication)?.container?.sourceDiagnostics?.mark(\n            name = name,\n            category = DiagnosticCategory.RUNTIME,\n            severity = severity,\n            sourceId = \"ai\",\n            traceId = traceId ?: \"app:${UUID.randomUUID()}\",\n            attributes = attributes,\n        )\n    }""",
    "xpk diagnostic helper",
)

print("Mode 3 V8 live diagnostics patch applied successfully.")
