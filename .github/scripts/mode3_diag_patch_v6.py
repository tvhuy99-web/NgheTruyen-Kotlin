from pathlib import Path

def read(path):
    return Path(path).read_text(encoding="utf-8")

def write(path, text):
    Path(path).write_text(text, encoding="utf-8")

def replace_once(path, old, new):
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly 1 occurrence, found {count}: {old[:100]!r}")
    write(path, text.replace(old, new, 1))

def replace_first(path, old, new, expected_count):
    text = read(path)
    count = text.count(old)
    if count != expected_count:
        raise SystemExit(f"{path}: expected {expected_count} occurrences before first replacement, found {count}: {old[:100]!r}")
    write(path, text.replace(old, new, 1))

# FreesoundAutoAudioResolver: detailed trace + safer persistent cache.
path = "app/src/main/java/vn/nghetruyen/app/freesound/FreesoundAutoAudioResolver.kt"
text = read(path)
text = text.replace(
    """internal data class FreesoundAutoSearchOutcome(
    val sound: FreesoundSound?,
    val failureMessage: String? = null,
    val retryable: Boolean = false,
)""",
    """internal data class FreesoundAutoSearchOutcome(
    val sound: FreesoundSound?,
    val failureMessage: String? = null,
    val retryable: Boolean = false,
    val resultCount: Int = 0,
    val httpCode: Int? = null,
)""",
    1,
)
text = text.replace(
    """data class FreesoundAutoResolveResult(
    val resolved: List<FreesoundAutoResolvedNeed>,
    val warnings: List<String>,
    val importedTrackIds: Set<String>,
    val retryableFailure: Boolean = false,
) {
    val resolvedCount: Int get() = resolved.count { !it.trackId.isNullOrBlank() }
}""",
    """data class FreesoundAutoResolveResult(
    val resolved: List<FreesoundAutoResolvedNeed>,
    val warnings: List<String>,
    val importedTrackIds: Set<String>,
    val retryableFailure: Boolean = false,
    val diagnostics: List<String> = emptyList(),
) {
    val resolvedCount: Int get() = resolved.count { !it.trackId.isNullOrBlank() }
}""",
    1,
)

start = text.index("    suspend fun resolve(requirements: List<FreesoundAutoRequirement>): FreesoundAutoResolveResult {")
end = text.index("        private suspend fun searchBest", start)
new_resolve = r"""    suspend fun resolve(requirements: List<FreesoundAutoRequirement>): FreesoundAutoResolveResult {
        val startedNanos = System.nanoTime()
        val needs = FreesoundAutoRequirementAggregator.aggregate(requirements)
        val diagnostics = mutableListOf<String>()
        diagnostics += "RESOLVE_START requirements=${requirements.size} aggregated=${needs.size}"
        if (needs.isEmpty()) {
            diagnostics += "RESOLVE_EMPTY no aggregated Freesound needs were produced"
            return FreesoundAutoResolveResult(
                resolved = emptyList(),
                warnings = emptyList(),
                importedTrackIds = emptySet(),
                diagnostics = diagnostics,
            )
        }
        val warnings = mutableListOf<String>()
        val imported = linkedSetOf<String>()
        val resolutions = mutableListOf<FreesoundAutoResolvedNeed>()
        var retryableFailure = false
        var queryCacheHits = 0
        var clientSearches = 0
        var importAttempts = 0

        for ((index, need) in needs.withIndex()) {
            diagnostics += "NEED_START index=${index + 1}/${needs.size} kind=${need.kind.name} importance=${need.importance.name} usages=${need.usages.size} query=${need.query.take(160)}"
            val currentTracks = runCatching { existingTracksProvider() }.getOrDefault(emptyList())
            val cachedId = queryCache.get(need.kind, need.query)
            val cachedTrack = cachedId?.let { id -> currentTracks.firstOrNull { it.id == id } }
                ?.takeIf {
                    it.enabled &&
                        AudioAssetClassifier.classify(it) == need.kind &&
                        FreesoundImporter.soundIdFromManagedUri(it.uri) != null &&
                        FreesoundImporter.managedFileExists(appContext, it.uri)
                }
            if (cachedTrack != null) {
                queryCacheHits += 1
                diagnostics += "QUERY_CACHE_HIT kind=${need.kind.name} trackId=${cachedTrack.id} soundId=${FreesoundImporter.soundIdFromManagedUri(cachedTrack.uri)} fileExists=true query=${need.query.take(140)}"
                resolutions += FreesoundAutoResolvedNeed(need, cachedTrack.id, FreesoundAutoResolutionSource.CACHE.name)
                continue
            } else if (cachedId != null) {
                diagnostics += "QUERY_CACHE_STALE kind=${need.kind.name} trackId=$cachedId reason=missing_disabled_wrong_kind_or_file_missing query=${need.query.take(140)}"
                queryCache.remove(need.kind, need.query)
            } else {
                diagnostics += "QUERY_CACHE_MISS kind=${need.kind.name} query=${need.query.take(140)}"
            }

            clientSearches += 1
            val searchStartedNanos = System.nanoTime()
            diagnostics += "CLIENT_SEARCH_START index=${index + 1} kind=${need.kind.name} query=${need.query.take(160)}"
            val search = searchBest(need)
            val searchElapsedMs = (System.nanoTime() - searchStartedNanos) / 1_000_000L
            val remote = search.sound
            diagnostics += "CLIENT_SEARCH_DONE index=${index + 1} kind=${need.kind.name} elapsedMs=$searchElapsedMs resultCount=${search.resultCount} httpCode=${search.httpCode ?: 0} selectedSoundId=${remote?.id ?: 0} failure=${search.failureMessage.orEmpty().take(180)}"
            if (!search.failureMessage.isNullOrBlank()) {
                warnings += "Freesound ‘${need.query}’: ${search.failureMessage}"
                retryableFailure = retryableFailure || search.retryable
            }

            var resolvedTrack: SceneMusicTrackEntity? = null
            if (remote != null) {
                importAttempts += 1
                val importStartedNanos = System.nanoTime()
                diagnostics += "IMPORT_START kind=${need.kind.name} soundId=${remote.id} durationSec=${"%.2f".format(java.util.Locale.US, remote.durationSeconds)} previewAvailable=${remote.preferredPreviewUrl != null} query=${need.query.take(140)}"
                val import = importer.importPreview(
                    sound = remote,
                    kind = need.kind,
                    normalizationTargetLufs = normalizationTarget(need.kind),
                )
                val importElapsedMs = (System.nanoTime() - importStartedNanos) / 1_000_000L
                if (import.isSuccess) {
                    val result = import.getOrThrow()
                    imported += result.trackId
                    resolvedTrack = runCatching { existingTracksProvider() }.getOrDefault(emptyList())
                        .firstOrNull { it.id == result.trackId && it.enabled }
                    diagnostics += "IMPORT_SUCCESS kind=${need.kind.name} soundId=${remote.id} trackId=${result.trackId} elapsedMs=$importElapsedMs fileExists=${resolvedTrack?.let { FreesoundImporter.managedFileExists(appContext, it.uri) } == true}"
                } else if (import.exceptionOrNull() is FreesoundDuplicateException) {
                    resolvedTrack = runCatching { existingTracksProvider() }.getOrDefault(emptyList())
                        .firstOrNull { track ->
                            track.enabled &&
                                FreesoundImporter.managedFileExists(appContext, track.uri) &&
                                FreesoundImporter.soundIdFromManagedUri(track.uri) == remote.id &&
                                AudioAssetClassifier.classify(track) == need.kind
                        }
                    diagnostics += "IMPORT_DUPLICATE kind=${need.kind.name} soundId=${remote.id} reusedTrackId=${resolvedTrack?.id.orEmpty()} elapsedMs=$importElapsedMs fileExists=${resolvedTrack != null}"
                } else {
                    val error = import.exceptionOrNull()
                    val message = error?.message?.takeIf(String::isNotBlank)
                        ?: "Không nhập/chuẩn hóa được preview đã chọn."
                    warnings += "Freesound ‘${need.query}’: $message"
                    if (error is FreesoundNormalizationException && error.retryable) retryableFailure = true
                    diagnostics += "IMPORT_FAILED kind=${need.kind.name} soundId=${remote.id} elapsedMs=$importElapsedMs retryable=${error is FreesoundNormalizationException && error.retryable} errorType=${error?.javaClass?.simpleName.orEmpty()} error=${message.take(220)}"
                }
            } else {
                diagnostics += "SEARCH_NO_SELECTION kind=${need.kind.name} resultCount=${search.resultCount} query=${need.query.take(160)}"
            }

            if (resolvedTrack != null && resolvedTrack.enabled &&
                FreesoundImporter.managedFileExists(appContext, resolvedTrack.uri) &&
                FreesoundImporter.soundIdFromManagedUri(resolvedTrack.uri) != null
            ) {
                queryCache.put(need.kind, need.query, resolvedTrack.id)
                resolutions += FreesoundAutoResolvedNeed(need, resolvedTrack.id, FreesoundAutoResolutionSource.FREESOUND.name)
                diagnostics += "NEED_RESOLVED kind=${need.kind.name} source=FREESOUND trackId=${resolvedTrack.id} soundId=${FreesoundImporter.soundIdFromManagedUri(resolvedTrack.uri)} query=${need.query.take(140)}"
                continue
            }

            resolutions += FreesoundAutoResolvedNeed(need, null, FreesoundAutoResolutionSource.UNRESOLVED.name)
            diagnostics += "NEED_UNRESOLVED kind=${need.kind.name} query=${need.query.take(160)}"
            if (search.failureMessage.isNullOrBlank() && remote == null) {
                val prefix = if (need.importance == FreesoundRequirementImportance.REQUIRED) "Âm thanh quan trọng" else "Âm thanh tùy chọn"
                warnings += "$prefix ‘${need.query}’ chưa tìm thấy kết quả đủ phù hợp trên Freesound; khoảng đó sẽ im lặng ở lớp tương ứng."
            }
        }

        val totalElapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L
        diagnostics += "RESOLVE_DONE requirements=${requirements.size} aggregated=${needs.size} resolved=${resolutions.count { !it.trackId.isNullOrBlank() }} unresolved=${resolutions.count { it.trackId.isNullOrBlank() }} queryCacheHits=$queryCacheHits clientSearches=$clientSearches importAttempts=$importAttempts imported=${imported.size} retryableFailure=$retryableFailure elapsedMs=$totalElapsedMs"
        return FreesoundAutoResolveResult(
            resolved = resolutions,
            warnings = warnings.distinct(),
            importedTrackIds = imported,
            retryableFailure = retryableFailure,
            diagnostics = diagnostics.distinct(),
        )
    }

"""
text = text[:start] + new_resolve + text[end:]

search_start = text.index("        private suspend fun searchBest")
search_end = text.index("    private suspend fun normalizationTarget", search_start)
new_search = r"""    private suspend fun searchBest(need: FreesoundAutoSearchNeed): FreesoundAutoSearchOutcome {
        val request = FreesoundSearchRequest(
            query = need.query,
            category = need.kind.toFreesoundCategory(),
            duration = FreesoundDuration.RECOMMENDED,
            sort = FreesoundSort.RELEVANCE,
            page = 1,
            pageSize = SEARCH_PAGE_SIZE,
        )
        return when (val result = client.search(request)) {
            is FreesoundSearchResult.Failure -> FreesoundAutoSearchOutcome(
                sound = null,
                failureMessage = result.message,
                retryable = result.httpCode == null || result.httpCode in setOf(401, 403, 429) ||
                    (result.httpCode ?: 0) >= 500,
                resultCount = 0,
                httpCode = result.httpCode,
            )
            is FreesoundSearchResult.Success -> FreesoundAutoSearchOutcome(
                sound = result.page.results
                    .mapIndexed { index, sound -> sound to scoreCandidate(need.query, sound, index) }
                    .filter { it.first.preferredPreviewUrl != null }
                    .maxByOrNull { it.second }
                    ?.takeIf { it.second >= REMOTE_MIN_SCORE }
                    ?.first,
                resultCount = result.page.results.size,
                httpCode = 200,
            )
        }
    }

"""
text = text[:search_start] + new_search + text[search_end:]
write(path, text)

# NarrationPlanCoordinator: propagate diagnostics and cue statistics.
path = "app/src/main/java/vn/nghetruyen/app/ai/NarrationPlanCoordinator.kt"
replace_once(
    path,
    """        val freesoundResolvedAssets: Int = 0,
        val freesoundRetryRequired: Boolean = false,
    )""",
    """        val freesoundResolvedAssets: Int = 0,
        val freesoundRetryRequired: Boolean = false,
        val freesoundDiagnostics: List<String> = emptyList(),
    )""",
)
replace_once(
    path,
    """        val warnings: List<String>,
        val retryableFailure: Boolean = false,
    )""",
    """        val warnings: List<String>,
        val retryableFailure: Boolean = false,
        val diagnostics: List<String> = emptyList(),
    )""",
)
replace_once(
    path,
    """        val freesoundKinds = if (freesoundMode) buildSet {
            if (music) add(AudioAssetKind.MUSIC)
            if (audioSettings.ambienceEnabled) add(AudioAssetKind.AMBIENCE)
            if (audioSettings.soundEffectsEnabled) add(AudioAssetKind.SFX)
        } else emptySet()
        if (freesoundMode && freesoundKinds.isEmpty()) {
            warnings += "Mode 3 đang được chọn nhưng MUSIC, AMBIENCE và SFX đều tắt; lượt AI này chỉ có thể phân vai giọng."
        }
""",
    """        val freesoundDiagnostics = mutableListOf<String>()
        val freesoundKinds = if (freesoundMode) buildSet {
            if (music) add(AudioAssetKind.MUSIC)
            if (audioSettings.ambienceEnabled) add(AudioAssetKind.AMBIENCE)
            if (audioSettings.soundEffectsEnabled) add(AudioAssetKind.SFX)
        } else emptySet()
        if (freesoundMode) {
            freesoundDiagnostics += "COORDINATOR_STATE mode=${sourceMode.name} musicRequested=$music ambienceEnabled=${audioSettings.ambienceEnabled} sfxEnabled=${audioSettings.soundEffectsEnabled} kinds=${freesoundKinds.map(AudioAssetKind::name).sorted().joinToString(",")}"
        }
        if (freesoundMode && freesoundKinds.isEmpty()) {
            warnings += "Mode 3 đang được chọn nhưng MUSIC, AMBIENCE và SFX đều tắt; lượt AI này chỉ có thể phân vai giọng."
            freesoundDiagnostics += "COORDINATOR_NO_LAYERS all Mode 3 audio layers are disabled"
        }
""",
)
replace_once(
    path,
    """        val cachedFreesoundRetryRequired = cachedFreesoundRequirements != null && freesoundResolutionRetryRequired(content)
        if (cachedFreesoundRequirements != null &&""",
    """        val cachedFreesoundRetryRequired = cachedFreesoundRequirements != null && freesoundResolutionRetryRequired(content)
        if (freesoundMode) {
            freesoundDiagnostics += "COORDINATOR_CACHE cachedRequirements=${cachedFreesoundRequirements?.size ?: 0} retryRequired=$cachedFreesoundRetryRequired force=$force"
        }
        if (cachedFreesoundRequirements != null &&""",
)
replace_once(
    path,
    """        val freesoundNeeded = freesoundMode && freesoundKinds.isNotEmpty() && (force || cachedFreesoundRequirements == null)

        if (!voiceNeeded && !musicNeeded && !audioNeeded && !freesoundNeeded) {""",
    """        val freesoundNeeded = freesoundMode && freesoundKinds.isNotEmpty() && (force || cachedFreesoundRequirements == null)
        if (freesoundMode) {
            freesoundDiagnostics += "COORDINATOR_NEEDS voiceNeeded=$voiceNeeded localMusicNeeded=$musicNeeded localAudioNeeded=$audioNeeded freesoundNeeded=$freesoundNeeded"
        }

        if (!voiceNeeded && !musicNeeded && !audioNeeded && !freesoundNeeded) {""",
)
replace_first(
    path,
    """                freesoundResolvedAssets = restoredFreesound.resolvedAssets,
        freesoundRetryRequired = restoredFreesound.retryableFailure,
            )""",
    """                freesoundResolvedAssets = restoredFreesound.resolvedAssets,
                freesoundRetryRequired = restoredFreesound.retryableFailure,
                freesoundDiagnostics = (freesoundDiagnostics + restoredFreesound.diagnostics + "COORDINATOR_REUSE no new AI/Freesound work required").distinct(),
            )""",
    expected_count=2,
)
replace_once(
    path,
    """                freesoundResolvedAssets = restoredFreesound.resolvedAssets,
        freesoundRetryRequired = restoredFreesound.retryableFailure,
            )
            is AppResult.Success -> {""",
    """                freesoundResolvedAssets = restoredFreesound.resolvedAssets,
                freesoundRetryRequired = restoredFreesound.retryableFailure,
                freesoundDiagnostics = (freesoundDiagnostics + restoredFreesound.diagnostics + "AI_PLAN_FAILURE error=${outcome.message.take(220)}").distinct(),
            )
            is AppResult.Success -> {""",
)
replace_once(
    path,
    """                outcome.value.freesoundRequirementError.takeIf(String::isNotBlank)?.let(warnings::add)

                val voiceCreated = if (effectiveVoice) {""",
    """                outcome.value.freesoundRequirementError.takeIf(String::isNotBlank)?.let(warnings::add)
                if (freesoundMode) {
                    freesoundDiagnostics += "AI_REQUIREMENTS_RESULT requested=$freesoundNeeded count=${outcome.value.freesoundRequirements.size} error=${outcome.value.freesoundRequirementError.take(220)}"
                }

                val voiceCreated = if (effectiveVoice) {""",
)
replace_once(
    path,
    """                    freesoundResolvedAssets = autoApplied.resolvedAssets,
                    freesoundRetryRequired = autoApplied.retryableFailure,
                )""",
    """                    freesoundResolvedAssets = autoApplied.resolvedAssets,
                    freesoundRetryRequired = autoApplied.retryableFailure,
                    freesoundDiagnostics = (freesoundDiagnostics + autoApplied.diagnostics).distinct(),
                )""",
)
replace_once(
    path,
    """        val resolved = freesoundResolver.resolve(requirements)
        val warnings = resolved.warnings.toMutableList()
        val units = XpkVoiceCastSplitter.buildUnits(content.chapter.title, chapterBody(content))
        val unitIds = units.map { it.id }
        val enabled = library.listEnabledSceneMusicTracks()
            .filter { FreesoundImporter.soundIdFromManagedUri(it.uri) != null }
        var musicCreated = false
        var audioCreated = false
""",
    """        val resolved = freesoundResolver.resolve(requirements)
        val warnings = resolved.warnings.toMutableList()
        val diagnostics = resolved.diagnostics.toMutableList()
        val units = XpkVoiceCastSplitter.buildUnits(content.chapter.title, chapterBody(content))
        val unitIds = units.map { it.id }
        val enabled = library.listEnabledSceneMusicTracks()
            .filter { FreesoundImporter.soundIdFromManagedUri(it.uri) != null }
        diagnostics += "PLAN_BUILD_START requirements=${requirements.size} units=${unitIds.size} managedTracks=${enabled.size} kinds=${kinds.map(AudioAssetKind::name).sorted().joinToString(",")}"
        var musicCreated = false
        var audioCreated = false
""",
)
replace_once(
    path,
    """            runCatching {
                persistMusicPlan(
                    content,
                    musicTracks,
                    validated,
                    "",
                    StoryAudioSourceMode.AI_FREESOUND,
                )
            }.onSuccess { musicCreated = true }
                .onFailure { warnings += it.message ?: "Không lưu được MUSIC Freesound tự động." }
        }
""",
    """            diagnostics += "PLAN_MUSIC rawCues=${rawCues.size} validatedCues=${validated.size} managedMusicTracks=${musicTracks.size}"
            runCatching {
                persistMusicPlan(
                    content,
                    musicTracks,
                    validated,
                    "",
                    StoryAudioSourceMode.AI_FREESOUND,
                )
            }.onSuccess {
                musicCreated = true
                diagnostics += "PLAN_MUSIC_PERSIST success=true"
            }.onFailure {
                warnings += it.message ?: "Không lưu được MUSIC Freesound tự động."
                diagnostics += "PLAN_MUSIC_PERSIST success=false error=${(it.message ?: it::class.java.simpleName).take(220)}"
            }
        }
""",
)
replace_once(
    path,
    """            val validated = AmbienceSfxPlan(
                ambienceScenes = validatedAmbience.ambienceScenes,
                soundEffectCues = validatedSfx.soundEffectCues,
            )
            runCatching {""",
    """            val validated = AmbienceSfxPlan(
                ambienceScenes = validatedAmbience.ambienceScenes,
                soundEffectCues = validatedSfx.soundEffectCues,
            )
            diagnostics += "PLAN_AUDIO ambienceCandidates=$originalAmbienceCount ambienceValidated=${validatedAmbience.ambienceScenes.size} ambienceTracks=${ambienceTracks.size} sfxCandidates=$originalSfxCount sfxValidated=${validatedSfx.soundEffectCues.size} sfxTracks=${sfxTracks.size}"
            runCatching {""",
)
replace_once(
    path,
    """            }.onSuccess { audioCreated = true }
                .onFailure { warnings += it.message ?: "Không lưu được AMBIENCE/SFX Freesound tự động." }
        }

        return FreesoundApplyResult(
            musicCreated = musicCreated,
            audioCreated = audioCreated,
            resolvedAssets = resolved.resolvedCount,
            warnings = warnings.distinct(),
            retryableFailure = resolved.retryableFailure,
        )""",
    """            }.onSuccess {
                audioCreated = true
                diagnostics += "PLAN_AUDIO_PERSIST success=true"
            }.onFailure {
                warnings += it.message ?: "Không lưu được AMBIENCE/SFX Freesound tự động."
                diagnostics += "PLAN_AUDIO_PERSIST success=false error=${(it.message ?: it::class.java.simpleName).take(220)}"
            }
        }

        diagnostics += "PLAN_BUILD_DONE musicCreated=$musicCreated audioCreated=$audioCreated resolvedAssets=${resolved.resolvedCount} retryableFailure=${resolved.retryableFailure}"
        return FreesoundApplyResult(
            musicCreated = musicCreated,
            audioCreated = audioCreated,
            resolvedAssets = resolved.resolvedCount,
            warnings = warnings.distinct(),
            retryableFailure = resolved.retryableFailure,
            diagnostics = diagnostics.distinct(),
        )""",
)

# ReaderPlaybackService: surface coordinator trace and MUSIC runtime behavior.
path = "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt"
replace_once(
    path,
    "import vn.nghetruyen.app.ai.ChapterAiWorkflow\n",
    "import vn.nghetruyen.app.ai.ChapterAiWorkflow\nimport vn.nghetruyen.app.ai.NarrationPlanCoordinator\n",
)
replace_once(
    path,
    """    private var transitionMessage: String? = null
""",
    """    private fun diagnosticFreesoundPlanStart(phase: String) {
        if (!StoryAudioModeRouter.usesAiFreesound(storyAudioSourceMode)) return
        val audio = AudioDirectionPreferences.currentSnapshot()
        diagnostic(
            "FREESOUND_MODE3_PLAN_START",
            DiagnosticSeverity.INFO,
            mapOf(
                "phase" to phase,
                "musicEnabled" to autoSceneMusicEnabled.toString(),
                "ambienceEnabled" to audio.ambienceEnabled.toString(),
                "sfxEnabled" to audio.soundEffectsEnabled.toString(),
            ),
        )
    }

    private fun diagnosticFreesoundPlanResult(
        phase: String,
        result: NarrationPlanCoordinator.Result?,
        error: Throwable? = null,
    ) {
        if (!StoryAudioModeRouter.usesAiFreesound(storyAudioSourceMode)) return
        diagnostic(
            "FREESOUND_MODE3_PLAN_RESULT",
            if (result == null || result.freesoundRetryRequired || error != null) DiagnosticSeverity.WARN else DiagnosticSeverity.INFO,
            mapOf(
                "phase" to phase,
                "resultPresent" to (result != null).toString(),
                "resolvedAssets" to (result?.freesoundResolvedAssets ?: 0).toString(),
                "musicPlanCreated" to (result?.musicPlanCreated ?: false).toString(),
                "audioPlanCreated" to (result?.audioPlanCreated ?: false).toString(),
                "freesoundPlanCreated" to (result?.freesoundPlanCreated ?: false).toString(),
                "retryRequired" to (result?.freesoundRetryRequired ?: false).toString(),
                "traceCount" to (result?.freesoundDiagnostics?.size ?: 0).toString(),
                "warningCount" to (result?.warnings?.size ?: 0).toString(),
                "error" to (error?.message ?: "").take(220),
            ),
        )
        result?.freesoundDiagnostics.orEmpty().forEachIndexed { index, detail ->
            val stage = detail.substringBefore(' ').take(56).ifBlank { "TRACE" }
            val severity = if (
                detail.contains("FAIL", ignoreCase = true) ||
                detail.contains("UNRESOLVED", ignoreCase = true) ||
                detail.contains("STALE", ignoreCase = true) ||
                detail.contains("MISSING", ignoreCase = true) ||
                detail.contains("NO_LAYERS", ignoreCase = true)
            ) DiagnosticSeverity.WARN else DiagnosticSeverity.INFO
            diagnostic(
                "FREESOUND_$stage",
                severity,
                mapOf(
                    "phase" to phase,
                    "index" to (index + 1).toString(),
                    "detail" to detail.take(420),
                ),
            )
        }
    }

    private var transitionMessage: String? = null
""",
)
replace_once(
    path,
    """                val content = container.libraryRepository.loadCachedChapter(snapshot.chapterId)
                val planningAttempt = if (content == null) {""",
    """                diagnosticFreesoundPlanStart("voice_and_audio")
                val content = container.libraryRepository.loadCachedChapter(snapshot.chapterId)
                val planningAttempt = if (content == null) {""",
)
replace_once(
    path,
    """                val planResult = planningAttempt.getOrNull()
                val warnings = planResult?.warnings ?: listOf(""",
    """                val planResult = planningAttempt.getOrNull()
                diagnosticFreesoundPlanResult("voice_and_audio", planResult, planningAttempt.exceptionOrNull())
                val warnings = planResult?.warnings ?: listOf(""",
)
replace_once(
    path,
    """        sceneMusicTracks = if (hasSceneMusicPlan()) {
            orderedMusicTracks.associateBy { it.id }
        } else emptyMap()
        activeSceneTrackId = sceneMusicController.activeTrackId
""",
    """        sceneMusicTracks = if (hasSceneMusicPlan()) {
            orderedMusicTracks.associateBy { it.id }
        } else emptyMap()
        if (StoryAudioModeRouter.usesAiFreesound(storyAudioSourceMode)) {
            diagnostic(
                "FREESOUND_RUNTIME_MUSIC_PLAN_STATE",
                if (musicPlanUsable && hasSceneMusicPlan()) DiagnosticSeverity.INFO else DiagnosticSeverity.WARN,
                mapOf(
                    "enabledMusicTracks" to enabledMusicTracks.size.toString(),
                    "musicPlanPresent" to (musicPlan != null).toString(),
                    "sourceHashMatch" to (musicPlan != null && musicPlan.sourceSha256 == musicSourceHash).toString(),
                    "sourceModeMatch" to (musicPlan?.let { scenePlanMatchesCurrentSourceMode(it.transformedText) } == true).toString(),
                    "musicPlanUsable" to musicPlanUsable.toString(),
                    "unitAssignments" to xpkSceneTrackByUnitId.size.toString(),
                    "legacyCueRows" to sceneMusicCues.size.toString(),
                    "runtimeTracks" to sceneMusicTracks.size.toString(),
                ),
            )
        }
        activeSceneTrackId = sceneMusicController.activeTrackId
""",
)
replace_once(
    path,
    """        val requestedTrackId = if (canonicalPlanActive) unitId?.let(xpkSceneTrackByUnitId::get) else legacyCue?.trackId
        val snapshot = PlaybackQueueStore.state.value
        if (requestedTrackId == XpkSceneMusicParity.SILENCE_TRACK_ID) {""",
    """        val requestedTrackId = if (canonicalPlanActive) unitId?.let(xpkSceneTrackByUnitId::get) else legacyCue?.trackId
        val snapshot = PlaybackQueueStore.state.value
        if (StoryAudioModeRouter.usesAiFreesound(storyAudioSourceMode)) {
            diagnostic(
                "FREESOUND_RUNTIME_MUSIC_LOOKUP",
                DiagnosticSeverity.INFO,
                mapOf(
                    "canonicalPlan" to canonicalPlanActive.toString(),
                    "requestedTrackId" to requestedTrackId.orEmpty(),
                    "unitId" to unitId.orEmpty(),
                    "paragraphIndex" to paragraphIndex.toString(),
                    "availableTracks" to sceneMusicTracks.size.toString(),
                ),
            )
        }
        if (requestedTrackId == XpkSceneMusicParity.SILENCE_TRACK_ID) {""",
)
replace_once(
    path,
    """        if (requestedTrackId == XpkSceneMusicParity.SILENCE_TRACK_ID) {
            sceneMusicController.stop(clearTrack = true)
            activeSceneTrackId = null
            transitionMessage = null
            return
        }""",
    """        if (requestedTrackId == XpkSceneMusicParity.SILENCE_TRACK_ID) {
            if (StoryAudioModeRouter.usesAiFreesound(storyAudioSourceMode)) {
                diagnostic("FREESOUND_RUNTIME_MUSIC_SILENCE", DiagnosticSeverity.INFO)
            }
            sceneMusicController.stop(clearTrack = true)
            activeSceneTrackId = null
            transitionMessage = null
            return
        }""",
)
replace_once(
    path,
    """        if (requestedTrackId == null || track == null || !track.enabled) {
            if (canonicalPlanActive) {""",
    """        if (requestedTrackId == null || track == null || !track.enabled) {
            if (StoryAudioModeRouter.usesAiFreesound(storyAudioSourceMode)) {
                diagnostic(
                    "FREESOUND_RUNTIME_MUSIC_MISSING",
                    DiagnosticSeverity.WARN,
                    mapOf(
                        "requestedTrackId" to requestedTrackId.orEmpty(),
                        "trackFound" to (track != null).toString(),
                        "trackEnabled" to (track?.enabled ?: false).toString(),
                        "availableTracks" to sceneMusicTracks.size.toString(),
                    ),
                )
            }
            if (canonicalPlanActive) {""",
)
replace_once(
    path,
    """        val sceneVolume = legacyCue?.volume?.coerceIn(0f, 1f) ?: 1f
        sceneMusicController.transition(
            trackId = track.id,
            uri = track.uri,
            volume = (track.volume * sceneVolume * normalizationGain).coerceIn(0f, 1f),""",
    """        val sceneVolume = legacyCue?.volume?.coerceIn(0f, 1f) ?: 1f
        val effectiveSceneVolume = (track.volume * sceneVolume * normalizationGain).coerceIn(0f, 1f)
        if (StoryAudioModeRouter.usesAiFreesound(storyAudioSourceMode)) {
            diagnostic(
                "FREESOUND_RUNTIME_MUSIC_PLAY",
                if (FreesoundImporter.managedFileExists(this, track.uri)) DiagnosticSeverity.INFO else DiagnosticSeverity.ERROR,
                mapOf(
                    "trackId" to track.id,
                    "soundId" to (FreesoundImporter.soundIdFromManagedUri(track.uri)?.toString() ?: ""),
                    "fileExists" to FreesoundImporter.managedFileExists(this, track.uri).toString(),
                    "normalizationVersion" to track.normalizationVersion.toString(),
                    "normalizationError" to track.normalizationError.take(160),
                    "volume" to effectiveSceneVolume.toString(),
                ),
            )
        }
        sceneMusicController.transition(
            trackId = track.id,
            uri = track.uri,
            volume = effectiveSceneVolume,""",
)

# AudioDirectionRuntime: AMBIENCE/SFX runtime diagnostics routed into screen diagnostic store.
path = "app/src/main/java/vn/nghetruyen/app/playback/AudioDirectionRuntime.kt"
replace_once(
    path,
    "import vn.nghetruyen.app.ai.ChapterAiWorkflow\n",
    "import vn.nghetruyen.app.NgheTruyenApplication\nimport vn.nghetruyen.app.ai.ChapterAiWorkflow\n",
)
replace_once(
    path,
    "import vn.nghetruyen.app.freesound.FreesoundImporter\n",
    "import vn.nghetruyen.app.freesound.FreesoundImporter\nimport vn.nghetruyen.source.diagnostics.DiagnosticCategory\nimport vn.nghetruyen.source.diagnostics.DiagnosticSeverity\n",
)
replace_once(
    path,
    """    private val mutex = Mutex()
    private val ambienceController = SceneAmbienceController(context)
    private val sfxController = SceneSfxController(context)
""",
    """    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val ambienceController = SceneAmbienceController(context)
    private val sfxController = SceneSfxController(context)
    private var lastAmbienceTraceState = ""

    private fun diagnostic(
        name: String,
        severity: DiagnosticSeverity = DiagnosticSeverity.DEBUG,
        attributes: Map<String, String> = emptyMap(),
    ) {
        val app = appContext as? NgheTruyenApplication ?: return
        val snapshot = PlaybackQueueStore.state.value
        runCatching {
            app.container.sourceDiagnostics.mark(
                name = name,
                category = DiagnosticCategory.RUNTIME,
                severity = severity,
                sourceId = snapshot.sourceId.ifBlank { "audio-direction" },
                traceId = "audio-direction:${snapshot.chapterId}",
                attributes = attributes + mapOf(
                    "storyId" to snapshot.storyId,
                    "chapterId" to snapshot.chapterId,
                    "unitId" to snapshot.currentUnitId.orEmpty(),
                ),
            )
        }
    }

    private fun mode3Active(): Boolean =
        StoryAudioModeRouter.usesAiFreesound(narrationPlanCoordinator.storyAudioSourceMode())
""",
)
replace_once(
    path,
    """        val sourceMode = narrationPlanCoordinator.storyAudioSourceMode()
        val fastKey = buildFastKey(snapshot, settings, sourceMode)
""",
    """        val sourceMode = narrationPlanCoordinator.storyAudioSourceMode()
        val fastKey = buildFastKey(snapshot, settings, sourceMode)
        if (StoryAudioModeRouter.usesAiFreesound(sourceMode)) {
            diagnostic(
                "FREESOUND_RUNTIME_AUDIO_PLAN_CHECK",
                DiagnosticSeverity.INFO,
                mapOf(
                    "force" to force.toString(),
                    "ambienceEnabled" to settings.ambienceEnabled.toString(),
                    "sfxEnabled" to settings.soundEffectsEnabled.toString(),
                    "preparedChapterId" to preparedChapterId,
                ),
            )
        }
""",
)
replace_once(
    path,
    """        var activeAudioAssets = activeAudioAssets(allAssets, settings)
        updateRuntimeAssets(allAssets)

        val validUnits =""",
    """        var activeAudioAssets = activeAudioAssets(allAssets, settings)
        updateRuntimeAssets(allAssets)
        if (StoryAudioModeRouter.usesAiFreesound(sourceMode)) {
            diagnostic(
                "FREESOUND_RUNTIME_AUDIO_ASSETS",
                if (activeAudioAssets.isEmpty()) DiagnosticSeverity.WARN else DiagnosticSeverity.INFO,
                mapOf(
                    "rawTracks" to rawTracks.size.toString(),
                    "allMode3Assets" to allAssets.size.toString(),
                    "activeAudioAssets" to activeAudioAssets.size.toString(),
                    "ambienceAssets" to activeAudioAssets.count { it.kind == AudioAssetKind.AMBIENCE }.toString(),
                    "sfxAssets" to activeAudioAssets.count { it.kind == AudioAssetKind.SFX }.toString(),
                ),
            )
        }

        val validUnits =""",
)
replace_once(
    path,
    """            if (outcome == null) {
                markFailure(signature)
                return false
            }
""",
    """            if (outcome == null) {
                if (StoryAudioModeRouter.usesAiFreesound(sourceMode)) {
                    diagnostic("FREESOUND_RUNTIME_AUDIO_PREPARE_FAILED", DiagnosticSeverity.ERROR)
                }
                markFailure(signature)
                return false
            }
            if (StoryAudioModeRouter.usesAiFreesound(sourceMode)) {
                diagnostic(
                    "FREESOUND_RUNTIME_AUDIO_PREPARE_RESULT",
                    if (outcome.freesoundResolvedAssets > 0 && !outcome.freesoundRetryRequired) DiagnosticSeverity.INFO else DiagnosticSeverity.WARN,
                    mapOf(
                        "resolvedAssets" to outcome.freesoundResolvedAssets.toString(),
                        "audioPlanCreated" to outcome.audioPlanCreated.toString(),
                        "retryRequired" to outcome.freesoundRetryRequired.toString(),
                        "warningCount" to outcome.warnings.size.toString(),
                    ),
                )
            }
""",
)
replace_once(
    path,
    """        if (plan == null) {
            markFailure(signature)
            return false
        }

        clearFailure()
        installPlan(snapshot.chapterId, signature, validUnits, plan)
""",
    """        if (plan == null) {
            if (StoryAudioModeRouter.usesAiFreesound(sourceMode)) {
                diagnostic(
                    "FREESOUND_RUNTIME_AUDIO_PLAN_MISSING",
                    DiagnosticSeverity.ERROR,
                    mapOf("activeAudioAssets" to activeAudioAssets.size.toString()),
                )
            }
            markFailure(signature)
            return false
        }

        if (StoryAudioModeRouter.usesAiFreesound(sourceMode)) {
            diagnostic(
                "FREESOUND_RUNTIME_AUDIO_PLAN_READY",
                DiagnosticSeverity.INFO,
                mapOf(
                    "ambienceScenes" to plan.ambienceScenes.size.toString(),
                    "sfxCues" to plan.soundEffectCues.size.toString(),
                    "activeAudioAssets" to activeAudioAssets.size.toString(),
                ),
            )
        }
        clearFailure()
        installPlan(snapshot.chapterId, signature, validUnits, plan)
""",
)
replace_once(
    path,
    """        preparedChapterId = chapterId
        preparedSignature = signature
        lastTriggeredSfxKey = ""
        lastEffectAtMillis.clear()
""",
    """        preparedChapterId = chapterId
        preparedSignature = signature
        lastTriggeredSfxKey = ""
        lastEffectAtMillis.clear()
        if (mode3Active()) {
            diagnostic(
                "FREESOUND_RUNTIME_AUDIO_PLAN_INSTALLED",
                DiagnosticSeverity.INFO,
                mapOf(
                    "ambienceMappedUnits" to ambienceByUnitId.size.toString(),
                    "sfxTriggerUnits" to sfxByUnitId.size.toString(),
                    "sfxCueCount" to runtimeCues.size.toString(),
                    "boundedSfxCueCount" to boundedSfxCueKeys.size.toString(),
                ),
            )
        }
""",
)
text = read(path)
amb_start = text.index("    private fun applyAmbience(unitId: String, settings: AudioDirectionPreferences.Snapshot) {")
amb_end = text.index("    private fun applySfx(", amb_start)
new_amb = r"""    private fun applyAmbience(unitId: String, settings: AudioDirectionPreferences.Snapshot) {
        if (!settings.ambienceEnabled) {
            if (mode3Active() && lastAmbienceTraceState != "disabled") {
                diagnostic("FREESOUND_RUNTIME_AMBIENCE_DISABLED", DiagnosticSeverity.WARN)
                lastAmbienceTraceState = "disabled"
            }
            ambienceController.stop()
            return
        }
        val assets = ambienceByUnitId[unitId]
            .orEmpty()
            .asSequence()
            .mapNotNull(assetsById::get)
            .filter { it.kind == AudioAssetKind.AMBIENCE }
            .distinctBy(AudioDirectionAsset::id)
            .take(AudioDirectionLimits.MAX_CONCURRENT_AMBIENCE)
            .toList()
        val state = assets.joinToString(",") { it.id }.ifBlank { "empty" }
        if (mode3Active() && state != lastAmbienceTraceState) {
            diagnostic(
                if (assets.isEmpty()) "FREESOUND_RUNTIME_AMBIENCE_EMPTY" else "FREESOUND_RUNTIME_AMBIENCE_PLAY",
                if (assets.isEmpty()) DiagnosticSeverity.WARN else DiagnosticSeverity.INFO,
                mapOf(
                    "unitId" to unitId,
                    "assetCount" to assets.size.toString(),
                    "assetIds" to assets.joinToString(",") { it.id }.take(260),
                    "filesExist" to assets.all { FreesoundImporter.managedFileExists(appContext, it.uri) }.toString(),
                ),
            )
            lastAmbienceTraceState = state
        }
        if (assets.isEmpty()) {
            ambienceController.stop()
            return
        }
        ambienceController.play(
            assets = assets,
            variantsByAssetId = assets.associate { asset ->
                asset.id to ambienceVariantsById[asset.id].orEmpty()
            },
            masterVolume = 1f,
            crossfadeMillis = settings.ambienceCrossfadeMillis,
            overlapMinMillis = settings.ambienceLoopOverlapMinMillis,
            overlapMaxMillis = settings.ambienceLoopOverlapMaxMillis,
        )
    }

"""
text = text[:amb_start] + new_amb + text[amb_end:]
write(path, text)
replace_once(
    path,
    """        if (candidates.isEmpty()) return

        val now = System.currentTimeMillis()
""",
    """        if (candidates.isEmpty()) return
        if (mode3Active()) {
            diagnostic(
                "FREESOUND_RUNTIME_SFX_CANDIDATES",
                DiagnosticSeverity.INFO,
                mapOf(
                    "unitId" to unitId,
                    "candidateCount" to candidates.size.toString(),
                    "maxConcurrent" to maxConcurrent.toString(),
                ),
            )
        }

        val now = System.currentTimeMillis()
""",
)
replace_once(
    path,
    """            val started = sfxController.play(
                asset = asset,
                masterVolume = 1f,
                maxConcurrent = maxConcurrent,
                cueKey = runtimeCue.key,
                loopUntilStopped = cue.loopUntilStop,
                repeatCount = cue.repeatCount,
                repeatIntervalMillis = cue.cadence.intervalMillis,
            )
            if (started) {
""",
    """            val started = sfxController.play(
                asset = asset,
                masterVolume = 1f,
                maxConcurrent = maxConcurrent,
                cueKey = runtimeCue.key,
                loopUntilStopped = cue.loopUntilStop,
                repeatCount = cue.repeatCount,
                repeatIntervalMillis = cue.cadence.intervalMillis,
            )
            if (mode3Active()) {
                diagnostic(
                    "FREESOUND_RUNTIME_SFX_TRIGGER",
                    if (started) DiagnosticSeverity.INFO else DiagnosticSeverity.WARN,
                    mapOf(
                        "unitId" to unitId,
                        "effectId" to cue.effectId,
                        "soundId" to (FreesoundImporter.soundIdFromManagedUri(asset.uri)?.toString() ?: ""),
                        "fileExists" to FreesoundImporter.managedFileExists(appContext, asset.uri).toString(),
                        "started" to started.toString(),
                        "repeatCount" to cue.repeatCount.toString(),
                        "loopUntilStop" to cue.loopUntilStop.toString(),
                    ),
                )
            }
            if (started) {
""",
)
replace_once(
    path,
    """        cachedParagraphHash = ""
    }
""",
    """        cachedParagraphHash = ""
        lastAmbienceTraceState = ""
    }
""",
)

print("Mode 3 diagnostics patch applied successfully.")
