from pathlib import Path


def read(path):
    return Path(path).read_text(encoding="utf-8")


def write(path, text):
    Path(path).write_text(text, encoding="utf-8")


def replace_once(path, old, new):
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected 1 occurrence, found {count}: {old[:120]!r}")
    write(path, text.replace(old, new, 1))


# 1) Resolver: expose usable physical managed files and classify incomplete resolution.
path = "app/src/main/java/vn/nghetruyen/app/freesound/FreesoundAutoAudioResolver.kt"
replace_once(
    path,
    """data class FreesoundAutoResolveResult(\n    val resolved: List<FreesoundAutoResolvedNeed>,\n    val warnings: List<String>,\n    val importedTrackIds: Set<String>,\n    val retryableFailure: Boolean = false,\n    val diagnostics: List<String> = emptyList(),\n) {\n    val resolvedCount: Int get() = resolved.count { !it.trackId.isNullOrBlank() }\n}""",
    """data class FreesoundAutoResolveResult(\n    val resolved: List<FreesoundAutoResolvedNeed>,\n    val warnings: List<String>,\n    val importedTrackIds: Set<String>,\n    val retryableFailure: Boolean = false,\n    val diagnostics: List<String> = emptyList(),\n) {\n    val resolvedCount: Int get() = resolved.count { !it.trackId.isNullOrBlank() }\n    val unresolvedCount: Int get() = resolved.size - resolvedCount\n    val unresolvedRequiredCount: Int get() = resolved.count {\n        it.trackId.isNullOrBlank() && it.need.importance == FreesoundRequirementImportance.REQUIRED\n    }\n    val shouldRetryIncomplete: Boolean get() =\n        retryableFailure || (resolved.isNotEmpty() && (resolvedCount == 0 || unresolvedRequiredCount > 0))\n}""",
)
replace_once(
    path,
    """    private val importer = FreesoundImporter(\n        context = appContext,\n        repository = repository,\n        existingTracksProvider = existingTracksProvider,\n    )\n\n    suspend fun resolve(requirements: List<FreesoundAutoRequirement>): FreesoundAutoResolveResult {""",
    """    private val importer = FreesoundImporter(\n        context = appContext,\n        repository = repository,\n        existingTracksProvider = existingTracksProvider,\n    )\n\n    suspend fun usableManagedTrackIds(kinds: Set<AudioAssetKind>): Set<String> {\n        if (kinds.isEmpty()) return emptySet()\n        return runCatching { existingTracksProvider() }.getOrDefault(emptyList())\n            .asSequence()\n            .filter { track ->\n                track.enabled &&\n                    AudioAssetClassifier.classify(track) in kinds &&\n                    FreesoundImporter.soundIdFromManagedUri(track.uri) != null &&\n                    FreesoundImporter.managedFileExists(appContext, track.uri)\n            }\n            .map(SceneMusicTrackEntity::id)\n            .toSet()\n    }\n\n    suspend fun resolve(requirements: List<FreesoundAutoRequirement>): FreesoundAutoResolveResult {""",
)
replace_once(
    path,
    """        diagnostics += \"RESOLVE_DONE requirements=${requirements.size} aggregated=${needs.size} resolved=${resolutions.count { !it.trackId.isNullOrBlank() }} unresolved=${resolutions.count { it.trackId.isNullOrBlank() }} queryCacheHits=$queryCacheHits clientSearches=$clientSearches importAttempts=$importAttempts imported=${imported.size} retryableFailure=$retryableFailure elapsedMs=$totalElapsedMs\"""",
    """        val unresolvedRequired = resolutions.count {\n            it.trackId.isNullOrBlank() && it.need.importance == FreesoundRequirementImportance.REQUIRED\n        }\n        val retryRecommended = retryableFailure ||\n            (resolutions.isNotEmpty() && (resolutions.none { !it.trackId.isNullOrBlank() } || unresolvedRequired > 0))\n        diagnostics += \"RESOLVE_DONE requirements=${requirements.size} aggregated=${needs.size} resolved=${resolutions.count { !it.trackId.isNullOrBlank() }} unresolved=${resolutions.count { it.trackId.isNullOrBlank() }} unresolvedRequired=$unresolvedRequired queryCacheHits=$queryCacheHits clientSearches=$clientSearches importAttempts=$importAttempts imported=${imported.size} retryableFailure=$retryableFailure retryRecommended=$retryRecommended elapsedMs=$totalElapsedMs\"""",
)

# 2) Coordinator: an empty/incomplete Mode-3 plan is never considered final.
path = "app/src/main/java/vn/nghetruyen/app/ai/NarrationPlanCoordinator.kt"
replace_once(
    path,
    """        val cachedFreesoundRetryRequired = cachedFreesoundRequirements != null && freesoundResolutionRetryRequired(content)\n        if (freesoundMode) {\n            freesoundDiagnostics += \"COORDINATOR_CACHE cachedRequirements=${cachedFreesoundRequirements?.size ?: 0} retryRequired=$cachedFreesoundRetryRequired force=$force\"\n        }\n        if (cachedFreesoundRequirements != null &&\n            (cachedFreesoundRetryRequired || !standardFreesoundPlanCurrent(content, freesoundKinds))\n        ) {\n            restoredFreesound = applyFreesoundRequirements(content, cachedFreesoundRequirements, freesoundKinds)\n            warnings += restoredFreesound.warnings\n            runCatching {\n                persistFreesoundRequirements(\n                    content,\n                    freesoundKinds,\n                    cachedFreesoundRequirements,\n                    retryRequired = restoredFreesound.retryableFailure,\n                )\n            }.onFailure { warnings += it.message ?: \"Không cập nhật được trạng thái retry Freesound.\" }\n        }""",
    """        val cachedFreesoundRetryRequired = cachedFreesoundRequirements != null && freesoundResolutionRetryRequired(content)\n        val cachedFreesoundEmpty = cachedFreesoundRequirements?.isEmpty() == true\n        val cachedFreesoundEmptyRetryDue = cachedFreesoundEmpty && freesoundEmptyAiRetryDue(content)\n        if (cachedFreesoundEmpty && !cachedFreesoundEmptyRetryDue) {\n            restoredFreesound = FreesoundApplyResult(\n                musicCreated = false,\n                audioCreated = false,\n                resolvedAssets = 0,\n                warnings = listOf(\"AI chưa trả yêu cầu Freesound; sẽ yêu cầu AI lập lại sau thời gian chờ.\"),\n                retryableFailure = true,\n                diagnostics = listOf(\"AI_REQUIREMENTS_EMPTY_CACHE retryDue=false\"),\n            )\n            warnings += restoredFreesound.warnings\n        }\n        if (freesoundMode) {\n            freesoundDiagnostics += \"COORDINATOR_CACHE cachedRequirements=${cachedFreesoundRequirements?.size ?: 0} retryRequired=$cachedFreesoundRetryRequired empty=$cachedFreesoundEmpty emptyRetryDue=$cachedFreesoundEmptyRetryDue force=$force\"\n        }\n        if (cachedFreesoundRequirements != null && cachedFreesoundRequirements.isNotEmpty() &&\n            (cachedFreesoundRetryRequired || !standardFreesoundPlanCurrent(content, freesoundKinds, cachedFreesoundRequirements))\n        ) {\n            restoredFreesound = applyFreesoundRequirements(content, cachedFreesoundRequirements, freesoundKinds)\n            warnings += restoredFreesound.warnings\n            runCatching {\n                persistFreesoundRequirements(\n                    content,\n                    freesoundKinds,\n                    cachedFreesoundRequirements,\n                    retryRequired = restoredFreesound.retryableFailure,\n                    resolvedAssets = restoredFreesound.resolvedAssets,\n                )\n            }.onFailure { warnings += it.message ?: \"Không cập nhật được trạng thái retry Freesound.\" }\n        }""",
)
replace_once(
    path,
    """        val freesoundNeeded = freesoundMode && freesoundKinds.isNotEmpty() && (force || cachedFreesoundRequirements == null)""",
    """        val freesoundNeeded = freesoundMode && freesoundKinds.isNotEmpty() &&\n            (force || cachedFreesoundRequirements == null || cachedFreesoundEmptyRetryDue)""",
)

start = read(path).index("    private suspend fun standardFreesoundPlanCurrent(")
end = read(path).index("    private suspend fun applyFreesoundRequirements(", start)
text = read(path)
new_standard = r'''    private suspend fun standardFreesoundPlanCurrent(
        content: ChapterContent,
        kinds: Set<AudioAssetKind>,
        requirements: List<FreesoundAutoRequirement>,
    ): Boolean {
        if (requirements.isEmpty()) return false
        val neededKinds = requirements.map(FreesoundAutoRequirement::kind).toSet()
        val usableIds = freesoundResolver.usableManagedTrackIds(kinds)
        val enabled = library.listEnabledSceneMusicTracks()
            .filter { it.id in usableIds && FreesoundImporter.soundIdFromManagedUri(it.uri) != null }

        if (AudioAssetKind.MUSIC in kinds) {
            val musicTracks = enabled.filter { AudioAssetClassifier.classify(it) == AudioAssetKind.MUSIC }
            if (AudioAssetKind.MUSIC in neededKinds && musicTracks.isEmpty()) return false
            val cached = library.getChapterTransform(content.chapter.id, ChapterAiWorkflow.KIND_SCENE_MUSIC) ?: return false
            if (cached.sourceSha256 != musicSourceHash(content, musicTracks)) return false
            if (!isExpectedAudioSourceMode(cached.transformedText, StoryAudioSourceMode.AI_FREESOUND)) return false
            if (!isCurrentTimelineTransform(cached.transformedText, MUSIC_TRANSFORM_ENGINE, content)) return false
            if (AudioAssetKind.MUSIC in neededKinds) {
                val allowed = musicTracks.map(SceneMusicTrackEntity::id).toSet()
                val scenes = runCatching { JSONObject(cached.transformedText).optJSONArray("music_scenes") }.getOrNull()
                    ?: return false
                var playable = false
                for (index in 0 until scenes.length()) {
                    val trackId = scenes.optJSONObject(index)?.optString("track_id")?.trim().orEmpty()
                    if (trackId in allowed) { playable = true; break }
                }
                if (!playable) return false
            }
        }

        if (AudioAssetKind.AMBIENCE in kinds || AudioAssetKind.SFX in kinds) {
            val ambienceTracks = if (AudioAssetKind.AMBIENCE in kinds) {
                enabled.filter { AudioAssetClassifier.classify(it) == AudioAssetKind.AMBIENCE }
            } else emptyList()
            val sfxTracks = if (AudioAssetKind.SFX in kinds) {
                enabled.filter { AudioAssetClassifier.classify(it) == AudioAssetKind.SFX }
            } else emptyList()
            if (AudioAssetKind.AMBIENCE in neededKinds && ambienceTracks.isEmpty()) return false
            if (AudioAssetKind.SFX in neededKinds && sfxTracks.isEmpty()) return false

            val cached = library.getChapterTransform(content.chapter.id, KIND_AUDIO_DIRECTION) ?: return false
            val hash = audioDirectionSourceHash(
                content,
                ambienceTracks,
                sfxTracks,
                AudioAssetKind.AMBIENCE in kinds,
                AudioAssetKind.SFX in kinds,
            )
            if (cached.sourceSha256 != hash) return false
            if (!isExpectedAudioSourceMode(cached.transformedText, StoryAudioSourceMode.AI_FREESOUND)) return false
            if (!isCurrentTimelineTransform(cached.transformedText, XpkAmbienceSfxDirector.ENGINE, content)) return false

            val root = runCatching { JSONObject(cached.transformedText) }.getOrNull() ?: return false
            if (AudioAssetKind.AMBIENCE in neededKinds) {
                val allowed = ambienceTracks.map(SceneMusicTrackEntity::id).toSet()
                val rows = root.optJSONArray("ambience_scenes") ?: return false
                var playable = false
                for (index in 0 until rows.length()) {
                    val id = rows.optJSONObject(index)?.optString("ambience_id")?.trim().orEmpty()
                    if (id in allowed) { playable = true; break }
                }
                if (!playable) return false
            }
            if (AudioAssetKind.SFX in neededKinds) {
                val allowed = sfxTracks.map(SceneMusicTrackEntity::id).toSet()
                val rows = root.optJSONArray("sfx_cues") ?: return false
                var playable = false
                for (index in 0 until rows.length()) {
                    val id = rows.optJSONObject(index)?.optString("effect_id")?.trim().orEmpty()
                    if (id in allowed) { playable = true; break }
                }
                if (!playable) return false
            }
        }
        return true
    }

'''
write(path, text[:start] + new_standard + text[end:])

# Re-read after whole-function replacement.
text = read(path)
text = text.replace(
    """        val enabled = library.listEnabledSceneMusicTracks()\n            .filter { FreesoundImporter.soundIdFromManagedUri(it.uri) != null }\n        diagnostics += \"PLAN_BUILD_START requirements=${requirements.size} units=${unitIds.size} managedTracks=${enabled.size} kinds=${kinds.map(AudioAssetKind::name).sorted().joinToString(\",\")}\"""",
    """        val usableIds = freesoundResolver.usableManagedTrackIds(kinds)\n        val enabled = library.listEnabledSceneMusicTracks()\n            .filter { it.id in usableIds && FreesoundImporter.soundIdFromManagedUri(it.uri) != null }\n        diagnostics += \"PLAN_BUILD_START requirements=${requirements.size} units=${unitIds.size} managedTracks=${enabled.size} kinds=${kinds.map(AudioAssetKind::name).sorted().joinToString(\",\")}\"""",
    1,
)
text = text.replace(
    """        diagnostics += \"PLAN_BUILD_DONE musicCreated=$musicCreated audioCreated=$audioCreated resolvedAssets=${resolved.resolvedCount} retryableFailure=${resolved.retryableFailure}\"\n        return FreesoundApplyResult(\n            musicCreated = musicCreated,\n            audioCreated = audioCreated,\n            resolvedAssets = resolved.resolvedCount,\n            warnings = warnings.distinct(),\n            retryableFailure = resolved.retryableFailure,\n            diagnostics = diagnostics.distinct(),\n        )""",
    """        val retryRecommended = resolved.shouldRetryIncomplete\n        if (retryRecommended && requirements.isNotEmpty()) {\n            warnings += \"Freesound chưa resolve đủ âm thanh quan trọng; ứng dụng sẽ tự thử lại mà không cần phân vai lại.\"\n        }\n        diagnostics += \"PLAN_BUILD_DONE musicCreated=$musicCreated audioCreated=$audioCreated resolvedAssets=${resolved.resolvedCount} unresolved=${resolved.unresolvedCount} unresolvedRequired=${resolved.unresolvedRequiredCount} retryableFailure=${resolved.retryableFailure} retryRecommended=$retryRecommended\"\n        return FreesoundApplyResult(\n            musicCreated = musicCreated,\n            audioCreated = audioCreated,\n            resolvedAssets = resolved.resolvedCount,\n            warnings = warnings.distinct(),\n            retryableFailure = retryRecommended,\n            diagnostics = diagnostics.distinct(),\n        )""",
    1,
)
write(path, text)

replace_once(
    path,
    """                    if (outcome.value.freesoundRequirements.isEmpty()) {\n                        warnings += \"AI không yêu cầu MUSIC/AMBIENCE/SFX Freesound nào cho chương này; các lớp Mode 3 tương ứng sẽ im lặng.\"\n                    }\n                    autoApplied = applyFreesoundRequirements(content, outcome.value.freesoundRequirements, freesoundKinds)\n                    warnings += autoApplied.warnings\n                    runCatching {\n                        persistFreesoundRequirements(\n                            content,\n                            freesoundKinds,\n                            outcome.value.freesoundRequirements,\n                            retryRequired = autoApplied.retryableFailure,\n                        )\n                    }.onSuccess { markerCreated = true }""",
    """                    if (outcome.value.freesoundRequirements.isEmpty()) {\n                        warnings += \"AI không trả yêu cầu MUSIC/AMBIENCE/SFX Freesound; sẽ tự yêu cầu AI lập lại sau thời gian chờ.\"\n                        autoApplied = FreesoundApplyResult(\n                            musicCreated = false,\n                            audioCreated = false,\n                            resolvedAssets = 0,\n                            warnings = emptyList(),\n                            retryableFailure = true,\n                            diagnostics = listOf(\"AI_REQUIREMENTS_EMPTY fresh=true\"),\n                        )\n                    } else {\n                        autoApplied = applyFreesoundRequirements(content, outcome.value.freesoundRequirements, freesoundKinds)\n                        warnings += autoApplied.warnings\n                    }\n                    runCatching {\n                        persistFreesoundRequirements(\n                            content,\n                            freesoundKinds,\n                            outcome.value.freesoundRequirements,\n                            retryRequired = autoApplied.retryableFailure,\n                            resolvedAssets = autoApplied.resolvedAssets,\n                        )\n                    }.onSuccess { markerCreated = true }""",
)
replace_once(
    path,
    """    private suspend fun persistFreesoundRequirements(\n        content: ChapterContent,\n        kinds: Set<AudioAssetKind>,\n        requirements: List<FreesoundAutoRequirement>,\n        retryRequired: Boolean = false,\n    ) {""",
    """    private suspend fun persistFreesoundRequirements(\n        content: ChapterContent,\n        kinds: Set<AudioAssetKind>,\n        requirements: List<FreesoundAutoRequirement>,\n        retryRequired: Boolean = false,\n        resolvedAssets: Int = 0,\n    ) {""",
)
replace_once(
    path,
    """            .put(\"enabled_kinds\", JSONArray(kinds.map(AudioAssetKind::name).sorted()))\n    .put(\"resolution_retry_required\", retryRequired)\n            .put(FreesoundAutoRequirementCodec.JSON_KEY, FreesoundAutoRequirementCodec.toJson(requirements))""",
    """            .put(\"enabled_kinds\", JSONArray(kinds.map(AudioAssetKind::name).sorted()))\n            .put(\"requirement_count\", requirements.size)\n            .put(\"resolved_asset_count\", resolvedAssets.coerceAtLeast(0))\n            .put(\"resolution_state\", when {\n                requirements.isEmpty() -> \"AI_EMPTY\"\n                retryRequired -> \"INCOMPLETE\"\n                else -> \"COMPLETE\"\n            })\n            .put(\"resolution_retry_required\", retryRequired)\n            .put(FreesoundAutoRequirementCodec.JSON_KEY, FreesoundAutoRequirementCodec.toJson(requirements))""",
)
replace_once(
    path,
    """    private suspend fun freesoundResolutionRetryRequired(content: ChapterContent): Boolean {\n    val cached = library.getChapterTransform(content.chapter.id, KIND_FREESOUND_AUTO_AUDIO) ?: return false\n    val root = runCatching { JSONObject(cached.transformedText) }.getOrNull() ?: return false\n    return root.optBoolean(\"resolution_retry_required\", false)\n}\n""",
    """    private suspend fun freesoundResolutionRetryRequired(content: ChapterContent): Boolean {\n        val cached = library.getChapterTransform(content.chapter.id, KIND_FREESOUND_AUTO_AUDIO) ?: return false\n        val root = runCatching { JSONObject(cached.transformedText) }.getOrNull() ?: return false\n        return root.optBoolean(\"resolution_retry_required\", false)\n    }\n\n    private suspend fun freesoundEmptyAiRetryDue(content: ChapterContent): Boolean {\n        val cached = library.getChapterTransform(content.chapter.id, KIND_FREESOUND_AUTO_AUDIO) ?: return true\n        return System.currentTimeMillis() - cached.updatedAt >= FREESOUND_EMPTY_AI_RETRY_COOLDOWN_MS\n    }\n""",
)
replace_once(
    path,
    """        private const val FREESOUND_AUTO_ENGINE = \"freesound-auto-audio-v2\"""",
    """        private const val FREESOUND_AUTO_ENGINE = \"freesound-auto-audio-v2\"\n        private const val FREESOUND_EMPTY_AI_RETRY_COOLDOWN_MS = 60_000L""",
)

# Filter missing physical files when loading Mode-3 audio-direction assets.
replace_once(
    path,
    """        val sourceAssets = if (StoryAudioModeRouter.usesAiFreesound(sourceMode)) {\n            enabledAssets.filter { FreesoundImporter.soundIdFromManagedUri(it.uri) != null }\n        } else enabledAssets""",
    """        val sourceAssets = if (StoryAudioModeRouter.usesAiFreesound(sourceMode)) {\n            val usableIds = freesoundResolver.usableManagedTrackIds(setOf(AudioAssetKind.AMBIENCE, AudioAssetKind.SFX))\n            enabledAssets.filter { it.id in usableIds && FreesoundImporter.soundIdFromManagedUri(it.uri) != null }\n        } else enabledAssets""",
)

# 3) Runtime: never expose deleted Mode-3 files; fix empty-list filesExist=true diagnostic.
path = "app/src/main/java/vn/nghetruyen/app/playback/AudioDirectionRuntime.kt"
replace_once(
    path,
    """            !StoryAudioModeRouter.usesAiFreesound(sourceMode) ||\n                FreesoundImporter.soundIdFromManagedUri(track.uri) != null""",
    """            !StoryAudioModeRouter.usesAiFreesound(sourceMode) ||\n                (FreesoundImporter.soundIdFromManagedUri(track.uri) != null &&\n                    FreesoundImporter.managedFileExists(appContext, track.uri))""",
)
replace_once(
    path,
    """                    \"filesExist\" to assets.all { FreesoundImporter.managedFileExists(appContext, it.uri) }.toString(),""",
    """                    \"filesExist\" to (assets.isNotEmpty() &&\n                        assets.all { FreesoundImporter.managedFileExists(appContext, it.uri) }).toString(),""",
)

# 4) Reader summary: make zero-audio state unmistakable in basic screen logs.
path = "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt"
anchor = """        result?.freesoundDiagnostics.orEmpty().forEachIndexed { index, detail ->"""
insert = """        if (result != null && result.freesoundResolvedAssets == 0) {\n            diagnostic(\n                \"FREESOUND_MODE3_ZERO_AUDIO\",\n                DiagnosticSeverity.WARN,\n                mapOf(\n                    \"phase\" to phase,\n                    \"retryRequired\" to result.freesoundRetryRequired.toString(),\n                    \"musicPlanCreated\" to result.musicPlanCreated.toString(),\n                    \"audioPlanCreated\" to result.audioPlanCreated.toString(),\n                    \"traceCount\" to result.freesoundDiagnostics.size.toString(),\n                    \"firstTrace\" to result.freesoundDiagnostics.firstOrNull().orEmpty().take(260),\n                    \"firstWarning\" to result.warnings.firstOrNull().orEmpty().take(260),\n                ),\n            )\n        }\n        result?.freesoundDiagnostics.orEmpty().forEachIndexed { index, detail ->"""
replace_once(path, anchor, insert)

# 5) Regression tests for incomplete resolution semantics.
path = "app/src/test/java/vn/nghetruyen/app/freesound/FreesoundMode3RegressionTest.kt"
replace_once(
    path,
    """import org.junit.Assert.assertTrue\nimport org.junit.Test""",
    """import org.junit.Assert.assertTrue\nimport org.junit.Test\nimport vn.nghetruyen.app.audio.AudioAssetKind""",
)
insert_before = """    @Test\n    fun preferredPreviewUsesRealMp3ThenRealOgg() {"""
addition = r'''    @Test
    fun unresolvedRequiredNeedForcesRetryEvenWithoutNetworkFailure() {
        val need = FreesoundAutoSearchNeed(
            kind = AudioAssetKind.AMBIENCE,
            query = "thunder storm",
            importance = FreesoundRequirementImportance.REQUIRED,
            usages = emptyList(),
        )
        val result = FreesoundAutoResolveResult(
            resolved = listOf(FreesoundAutoResolvedNeed(need, null, "UNRESOLVED")),
            warnings = emptyList(),
            importedTrackIds = emptySet(),
            retryableFailure = false,
        )
        assertEquals(0, result.resolvedCount)
        assertEquals(1, result.unresolvedRequiredCount)
        assertTrue(result.shouldRetryIncomplete)
    }

    @Test
    fun fullyResolvedRequiredNeedDoesNotForceRetry() {
        val need = FreesoundAutoSearchNeed(
            kind = AudioAssetKind.SFX,
            query = "sword hit",
            importance = FreesoundRequirementImportance.REQUIRED,
            usages = emptyList(),
        )
        val result = FreesoundAutoResolveResult(
            resolved = listOf(FreesoundAutoResolvedNeed(need, "track-1", "CACHE")),
            warnings = emptyList(),
            importedTrackIds = emptySet(),
            retryableFailure = false,
        )
        assertEquals(1, result.resolvedCount)
        assertEquals(0, result.unresolvedRequiredCount)
        assertFalse(result.shouldRetryIncomplete)
    }

'''
replace_once(path, insert_before, addition + insert_before)

print("Mode 3 V7 empty-plan/incomplete-resolution fixes applied successfully.")
