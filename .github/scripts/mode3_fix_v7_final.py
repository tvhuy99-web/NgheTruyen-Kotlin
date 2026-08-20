from pathlib import Path


def read(path):
    return Path(path).read_text(encoding="utf-8")


def write(path, text):
    Path(path).write_text(text, encoding="utf-8")


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 occurrence, found {count}: {old[:140]!r}")
    return text.replace(old, new, 1)


# -----------------------------------------------------------------------------
# Freesound resolver: a NEW cast must bypass both persistent query cache and the
# client's five-minute HTTP page cache. Retries of the same cast keep resolved
# query hits but clear the HTTP page cache before a new network attempt.
# -----------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/freesound/FreesoundAutoAudioResolver.kt"
text = read(path)
text = replace_once(
    text,
    '''    fun remove(kind: AudioAssetKind, query: String) {
        preferences.edit().remove(key(kind, query)).apply()
    }

    private fun key(kind: AudioAssetKind, query: String): String {''',
    '''    fun remove(kind: AudioAssetKind, query: String) {
        preferences.edit().remove(key(kind, query)).apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun key(kind: AudioAssetKind, query: String): String {''',
    "query cache clear",
)
text = replace_once(
    text,
    '''    suspend fun resolve(requirements: List<FreesoundAutoRequirement>): FreesoundAutoResolveResult {''',
    '''    fun clearResolutionCaches() {
        queryCache.clear()
        client.clearSearchCache()
    }

    fun clearNetworkSearchCache() {
        client.clearSearchCache()
    }

    suspend fun resolve(requirements: List<FreesoundAutoRequirement>): FreesoundAutoResolveResult {''',
    "resolver cache APIs",
)
write(path, text)


# -----------------------------------------------------------------------------
# Coordinator: chapter-scoped clean reset, exact three resolver attempts, and a
# persisted exhausted state so runtime cannot loop forever after process restarts.
# -----------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/ai/NarrationPlanCoordinator.kt"
text = read(path)
text = replace_once(text, "import kotlinx.coroutines.sync.Mutex\n", "import kotlinx.coroutines.delay\nimport kotlinx.coroutines.sync.Mutex\n", "delay import")
text = replace_once(text, "import vn.nghetruyen.app.freesound.FreesoundImporter\n", "import vn.nghetruyen.app.freesound.FreesoundImporter\nimport vn.nghetruyen.app.freesound.FreesoundRequirementImportance\n", "importance import")
text = replace_once(
    text,
    '''    private val planningMutex = Mutex()

    data class Result(''',
    '''    private val planningMutex = Mutex()
    private val freesoundRetryExhaustedChapters = linkedSetOf<String>()

    data class Result(''',
    "exhausted chapter set",
)
text = replace_once(
    text,
    '''        val freesoundResolvedAssets: Int = 0,
        val freesoundRetryRequired: Boolean = false,
        val freesoundDiagnostics: List<String> = emptyList(),
    )''',
    '''        val freesoundResolvedAssets: Int = 0,
        val freesoundRetryRequired: Boolean = false,
        val freesoundRetryAttempts: Int = 0,
        val freesoundRetryExhausted: Boolean = false,
        val freesoundDiagnostics: List<String> = emptyList(),
    )''',
    "Result retry fields",
)
text = replace_once(
    text,
    '''        val warnings: List<String>,
        val retryableFailure: Boolean = false,
        val diagnostics: List<String> = emptyList(),
    )''',
    '''        val warnings: List<String>,
        val retryableFailure: Boolean = false,
        val diagnostics: List<String> = emptyList(),
        val attempts: Int = 0,
        val retryExhausted: Boolean = false,
    )''',
    "apply retry fields",
)
text = replace_once(
    text,
    '''    fun storyAudioSourceMode(): StoryAudioSourceMode = storyAudioModeStore.get()

    private suspend fun ensurePlansLocked(''',
    '''    fun storyAudioSourceMode(): StoryAudioSourceMode = storyAudioModeStore.get()

    suspend fun resetChapterNarrationState(
        content: ChapterContent,
        clearFreesoundCaches: Boolean = true,
    ) = planningMutex.withLock {
        val effectiveContent = currentPlaybackContent(content)
        val chapterId = effectiveContent.chapter.id
        val storyId = effectiveContent.chapter.storyId
        listOf(
            ChapterAiWorkflow.KIND_VOICE_CAST,
            ChapterAiWorkflow.KIND_SCENE_MUSIC,
            KIND_AUDIO_DIRECTION,
            KIND_FREESOUND_AUTO_AUDIO,
        ).forEach { kind -> library.deleteChapterTransform(chapterId, kind) }
        library.replaceVoiceAssignments(storyId, chapterId, emptyList())
        library.replaceSceneMusicCues(storyId, chapterId, emptyList())
        freesoundRetryExhaustedChapters.remove(chapterId)
        if (clearFreesoundCaches) freesoundResolver.clearResolutionCaches()
    }

    private suspend fun ensurePlansLocked(''',
    "fresh reset API",
)
# Only REQUIRED kinds make a partial plan invalid. Optional misses are allowed once
# at least one usable asset has resolved, preventing perpetual optional-only retries.
text = replace_once(
    text,
    '''        val neededKinds = requirements.map(FreesoundAutoRequirement::kind).toSet()''',
    '''        val requiredKinds = requirements
            .filter { it.importance == FreesoundRequirementImportance.REQUIRED }
            .map(FreesoundAutoRequirement::kind)
            .toSet()''',
    "required kinds",
)
text = text.replace(" in neededKinds", " in requiredKinds")

# Cached exhausted state blocks more runtime attempts until a fresh cast reset occurs.
text = replace_once(
    text,
    '''        val cachedFreesoundRetryRequired = cachedFreesoundRequirements != null && freesoundResolutionRetryRequired(content)
        val cachedFreesoundEmpty = cachedFreesoundRequirements?.isEmpty() == true''',
    '''        val cachedFreesoundRetryRequired = cachedFreesoundRequirements != null && freesoundResolutionRetryRequired(content)
        val cachedFreesoundRetryExhausted = cachedFreesoundRequirements != null && freesoundResolutionRetryExhausted(content)
        val cachedFreesoundEmpty = cachedFreesoundRequirements?.isEmpty() == true''',
    "cached exhausted read",
)
text = replace_once(
    text,
    '''        if (freesoundMode) {
            freesoundDiagnostics += "COORDINATOR_CACHE cachedRequirements=${cachedFreesoundRequirements?.size ?: 0} retryRequired=$cachedFreesoundRetryRequired empty=$cachedFreesoundEmpty emptyRetryDue=$cachedFreesoundEmptyRetryDue force=$force"
        }
        if (cachedFreesoundRequirements != null && cachedFreesoundRequirements.isNotEmpty() &&
            (cachedFreesoundRetryRequired || !standardFreesoundPlanCurrent(content, freesoundKinds, cachedFreesoundRequirements))
        ) {
            restoredFreesound = applyFreesoundRequirements(content, cachedFreesoundRequirements, freesoundKinds)
            warnings += restoredFreesound.warnings
            runCatching {
                persistFreesoundRequirements(
                    content,
                    freesoundKinds,
                    cachedFreesoundRequirements,
                    retryRequired = restoredFreesound.retryableFailure,
                    resolvedAssets = restoredFreesound.resolvedAssets,
                )
            }.onFailure { warnings += it.message ?: "Không cập nhật được trạng thái retry Freesound." }
        }''',
    '''        if (cachedFreesoundRetryExhausted) {
            freesoundRetryExhaustedChapters += content.chapter.id
            restoredFreesound = FreesoundApplyResult(
                musicCreated = false,
                audioCreated = false,
                resolvedAssets = 0,
                warnings = listOf("Freesound đã thất bại sau 3 lần; cần bắt đầu một lượt phân vai mới."),
                retryableFailure = true,
                diagnostics = listOf("RESOLVE_BLOCKED retryExhausted=true attempts=3"),
                attempts = MAX_FREESOUND_RUNTIME_ATTEMPTS,
                retryExhausted = true,
            )
            warnings += restoredFreesound.warnings
        }
        if (freesoundMode) {
            freesoundDiagnostics += "COORDINATOR_CACHE cachedRequirements=${cachedFreesoundRequirements?.size ?: 0} retryRequired=$cachedFreesoundRetryRequired retryExhausted=$cachedFreesoundRetryExhausted empty=$cachedFreesoundEmpty emptyRetryDue=$cachedFreesoundEmptyRetryDue force=$force"
        }
        if (!cachedFreesoundRetryExhausted && cachedFreesoundRequirements != null && cachedFreesoundRequirements.isNotEmpty() &&
            (cachedFreesoundRetryRequired || !standardFreesoundPlanCurrent(content, freesoundKinds, cachedFreesoundRequirements))
        ) {
            restoredFreesound = applyFreesoundRequirements(content, cachedFreesoundRequirements, freesoundKinds)
            warnings += restoredFreesound.warnings
            runCatching {
                persistFreesoundRequirements(
                    content,
                    freesoundKinds,
                    cachedFreesoundRequirements,
                    retryRequired = restoredFreesound.retryableFailure,
                    resolvedAssets = restoredFreesound.resolvedAssets,
                    retryAttempts = restoredFreesound.attempts,
                    retryExhausted = restoredFreesound.retryExhausted,
                )
            }.onFailure { warnings += it.message ?: "Không cập nhật được trạng thái retry Freesound." }
        }''',
    "cached retry block",
)

# Propagate retry status through all Result branches that carry Freesound state.
text = text.replace(
    '''                freesoundRetryRequired = restoredFreesound.retryableFailure,
                freesoundDiagnostics =''',
    '''                freesoundRetryRequired = restoredFreesound.retryableFailure,
                freesoundRetryAttempts = restoredFreesound.attempts,
                freesoundRetryExhausted = restoredFreesound.retryExhausted,
                freesoundDiagnostics =''',
)
text = text.replace(
    '''                    freesoundRetryRequired = autoApplied.retryableFailure,
                    freesoundDiagnostics =''',
    '''                    freesoundRetryRequired = autoApplied.retryableFailure,
                    freesoundRetryAttempts = autoApplied.attempts,
                    freesoundRetryExhausted = autoApplied.retryExhausted,
                    freesoundDiagnostics =''',
)

# Persist attempt/exhaustion status for process-death safety.
text = replace_once(
    text,
    '''        retryRequired: Boolean = false,
        resolvedAssets: Int = 0,
    ) {''',
    '''        retryRequired: Boolean = false,
        resolvedAssets: Int = 0,
        retryAttempts: Int = 0,
        retryExhausted: Boolean = false,
    ) {''',
    "persist signature",
)
text = replace_once(
    text,
    '''            .put("resolution_retry_required", retryRequired)
            .put(FreesoundAutoRequirementCodec.JSON_KEY, FreesoundAutoRequirementCodec.toJson(requirements))''',
    '''            .put("resolution_retry_required", retryRequired)
            .put("resolution_retry_attempts", retryAttempts.coerceIn(0, MAX_FREESOUND_RUNTIME_ATTEMPTS))
            .put("resolution_retry_exhausted", retryExhausted)
            .put(FreesoundAutoRequirementCodec.JSON_KEY, FreesoundAutoRequirementCodec.toJson(requirements))''',
    "persist retry metadata",
)
text = replace_once(
    text,
    '''    private suspend fun freesoundEmptyAiRetryDue(content: ChapterContent): Boolean {''',
    '''    private suspend fun freesoundResolutionRetryExhausted(content: ChapterContent): Boolean {
        val cached = library.getChapterTransform(content.chapter.id, KIND_FREESOUND_AUTO_AUDIO) ?: return false
        val root = runCatching { JSONObject(cached.transformedText) }.getOrNull() ?: return false
        return root.optBoolean("resolution_retry_exhausted", false)
    }

    private suspend fun freesoundEmptyAiRetryDue(content: ChapterContent): Boolean {''',
    "read exhausted helper",
)

# Fresh-AI marker persistence gets the retry counts produced by the resolver loop.
text = replace_once(
    text,
    '''                            retryRequired = autoApplied.retryableFailure,
                            resolvedAssets = autoApplied.resolvedAssets,
                        )''',
    '''                            retryRequired = autoApplied.retryableFailure,
                            resolvedAssets = autoApplied.resolvedAssets,
                            retryAttempts = autoApplied.attempts,
                            retryExhausted = autoApplied.retryExhausted,
                        )''',
    "fresh persist counts",
)

# Convert the current single resolver pass into an internal pass and wrap it with
# exactly three attempts. Empty AI requirements are retried by the outer AI-cast loop,
# not by the resolver because there is no query to search.
apply_start = text.index("    private suspend fun applyFreesoundRequirements(")
apply_end = text.index("    /** Canonical XPK assignments", apply_start)
once = text[apply_start:apply_end]
once = once.replace("    private suspend fun applyFreesoundRequirements(\n", "    private suspend fun applyFreesoundRequirementsOnce(\n", 1)
once = replace_once(
    once,
    '''            diagnostics = diagnostics.distinct(),
        )
    }

''',
    '''            diagnostics = diagnostics.distinct(),
            attempts = 1,
            retryExhausted = false,
        )
    }

''',
    "single pass result",
)
wrapper = r'''    private suspend fun applyFreesoundRequirements(
        content: ChapterContent,
        requirements: List<FreesoundAutoRequirement>,
        kinds: Set<AudioAssetKind>,
    ): FreesoundApplyResult {
        if (requirements.isEmpty()) {
            return FreesoundApplyResult(
                musicCreated = false,
                audioCreated = false,
                resolvedAssets = 0,
                warnings = listOf("AI chưa trả yêu cầu Freesound; chưa có truy vấn để retry runtime."),
                retryableFailure = true,
                diagnostics = listOf("RUNTIME_RETRY_SKIPPED reason=AI_REQUIREMENTS_EMPTY"),
                attempts = 0,
                retryExhausted = false,
            )
        }
        if (content.chapter.id in freesoundRetryExhaustedChapters) {
            return FreesoundApplyResult(
                musicCreated = false,
                audioCreated = false,
                resolvedAssets = 0,
                warnings = listOf("Freesound đã thất bại sau 3 lần; cần bắt đầu một lượt phân vai mới."),
                retryableFailure = true,
                diagnostics = listOf("RUNTIME_RETRY_BLOCKED attempts=$MAX_FREESOUND_RUNTIME_ATTEMPTS"),
                attempts = MAX_FREESOUND_RUNTIME_ATTEMPTS,
                retryExhausted = true,
            )
        }

        var latest = FreesoundApplyResult(false, false, 0, emptyList())
        val warnings = mutableListOf<String>()
        val diagnostics = mutableListOf<String>()
        for (attempt in 1..MAX_FREESOUND_RUNTIME_ATTEMPTS) {
            if (attempt > 1) {
                delay(FREESOUND_RUNTIME_RETRY_DELAY_MS)
                freesoundResolver.clearNetworkSearchCache()
            }
            diagnostics += "RUNTIME_RETRY_START attempt=$attempt/$MAX_FREESOUND_RUNTIME_ATTEMPTS"
            latest = applyFreesoundRequirementsOnce(content, requirements, kinds)
            warnings += latest.warnings
            diagnostics += latest.diagnostics
            diagnostics += "RUNTIME_RETRY_RESULT attempt=$attempt resolved=${latest.resolvedAssets} retryRequired=${latest.retryableFailure}"
            if (!latest.retryableFailure) {
                freesoundRetryExhaustedChapters.remove(content.chapter.id)
                return latest.copy(
                    warnings = warnings.distinct(),
                    diagnostics = diagnostics.distinct(),
                    attempts = attempt,
                    retryExhausted = false,
                )
            }
        }

        freesoundRetryExhaustedChapters += content.chapter.id
        return latest.copy(
            warnings = (warnings + "Freesound không tạo được kế hoạch âm thanh hợp lệ sau 3 lần thử.").distinct(),
            retryableFailure = true,
            diagnostics = (diagnostics + "RUNTIME_RETRY_EXHAUSTED attempts=$MAX_FREESOUND_RUNTIME_ATTEMPTS").distinct(),
            attempts = MAX_FREESOUND_RUNTIME_ATTEMPTS,
            retryExhausted = true,
        )
    }

'''
text = text[:apply_start] + wrapper + once + text[apply_end:]
text = replace_once(
    text,
    '''        private const val FREESOUND_AUTO_ENGINE = "freesound-auto-audio-v2"
''',
    '''        private const val FREESOUND_AUTO_ENGINE = "freesound-auto-audio-v2"
        private const val MAX_FREESOUND_RUNTIME_ATTEMPTS = 3
        private const val FREESOUND_RUNTIME_RETRY_DELAY_MS = 1_200L
''',
    "retry constants",
)
write(path, text)


# -----------------------------------------------------------------------------
# Audio runtime: empty Mode-3 assets cannot use the fast-path, and exhausted
# resolver status becomes a hard runtime failure rather than silent playback.
# -----------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/playback/AudioDirectionRuntime.kt"
text = read(path)
text = replace_once(
    text,
    '''        if (!force &&
            preparedChapterId == snapshot.chapterId &&
            preparedSignature.isNotBlank() &&
            validatedFastKey == fastKey &&
            now - validatedFastAtMillis < PLAN_REVALIDATE_INTERVAL_MS
        ) {
            return true
        }''',
    '''        val mode3RuntimeEmpty = StoryAudioModeRouter.usesAiFreesound(sourceMode) &&
            (settings.ambienceEnabled || settings.soundEffectsEnabled) &&
            assetsById.values.none { asset ->
                (settings.ambienceEnabled && asset.kind == AudioAssetKind.AMBIENCE) ||
                    (settings.soundEffectsEnabled && asset.kind == AudioAssetKind.SFX)
            }
        if (!force &&
            !mode3RuntimeEmpty &&
            preparedChapterId == snapshot.chapterId &&
            preparedSignature.isNotBlank() &&
            validatedFastKey == fastKey &&
            now - validatedFastAtMillis < PLAN_REVALIDATE_INTERVAL_MS
        ) {
            return true
        }''',
    "runtime fast path",
)
text = replace_once(
    text,
    '''                        "retryRequired" to outcome.freesoundRetryRequired.toString(),
                        "warningCount" to outcome.warnings.size.toString(),
                    ),
                )
            }''',
    '''                        "retryRequired" to outcome.freesoundRetryRequired.toString(),
                        "retryAttempts" to outcome.freesoundRetryAttempts.toString(),
                        "retryExhausted" to outcome.freesoundRetryExhausted.toString(),
                        "warningCount" to outcome.warnings.size.toString(),
                    ),
                )
                if (outcome.freesoundRetryExhausted) {
                    diagnostic(
                        "FREESOUND_RUNTIME_RETRY_EXHAUSTED",
                        DiagnosticSeverity.ERROR,
                        mapOf(
                            "attempts" to outcome.freesoundRetryAttempts.toString(),
                            "resolvedAssets" to outcome.freesoundResolvedAssets.toString(),
                        ),
                    )
                    markFailure(signature)
                    return false
                }
            }''',
    "runtime exhausted failure",
)
write(path, text)


# -----------------------------------------------------------------------------
# Playback service: automatic cast starts fresh exactly once, never starts audio
# from an incomplete Mode-3 result, and the AI-level fallback also caps at 3.
# -----------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt"
text = read(path)
text = replace_once(
    text,
    '''            val severity = if (
                detail.contains("FAIL", ignoreCase = true) ||
                detail.contains("UNRESOLVED", ignoreCase = true) ||
                detail.contains("STALE", ignoreCase = true) ||
                detail.contains("MISSING", ignoreCase = true) ||
                detail.contains("NO_LAYERS", ignoreCase = true)
            ) DiagnosticSeverity.WARN else DiagnosticSeverity.INFO''',
    '''            // BASIC diagnostics must still expose every Freesound stage while debugging Mode 3.
            val severity = DiagnosticSeverity.WARN''',
    "basic diagnostics",
)
text = replace_once(
    text,
    '''                "retryRequired" to (result?.freesoundRetryRequired ?: false).toString(),
                "traceCount" to (result?.freesoundDiagnostics?.size ?: 0).toString(),''',
    '''                "retryRequired" to (result?.freesoundRetryRequired ?: false).toString(),
                "retryAttempts" to (result?.freesoundRetryAttempts ?: 0).toString(),
                "retryExhausted" to (result?.freesoundRetryExhausted ?: false).toString(),
                "traceCount" to (result?.freesoundDiagnostics?.size ?: 0).toString(),''',
    "plan diagnostics attempts",
)
text = replace_once(
    text,
    '''            while (currentStoryAutoVoiceCastEnabled && PlaybackQueueStore.state.value.chapterId == snapshot.chapterId) {''',
    '''            while (currentStoryAutoVoiceCastEnabled &&
                PlaybackQueueStore.state.value.chapterId == snapshot.chapterId &&
                attempt < MAX_NARRATION_ATTEMPTS
            ) {''',
    "auto loop cap",
)
text = replace_once(
    text,
    '''                val planningAttempt = if (content == null) {
                    Result.failure(IllegalStateException("Không tải được chương để chuẩn bị phân vai."))
                } else {
                    runCatching {
                        container.narrationPlanCoordinator.ensurePlans(
                            content = content,
                            voice = true,
                            music = shouldPlanAutoSceneMusic(),
                            activeTrackId = sceneMusicController.activeTrackId,
                        )
                    }
                }''',
    '''                val planningAttempt = if (content == null) {
                    Result.failure(IllegalStateException("Không tải được chương để chuẩn bị phân vai."))
                } else {
                    runCatching {
                        if (attempt == 1) {
                            container.narrationPlanCoordinator.resetChapterNarrationState(
                                content = content,
                                clearFreesoundCaches = true,
                            )
                        }
                        container.narrationPlanCoordinator.ensurePlans(
                            content = content,
                            voice = true,
                            music = shouldPlanAutoSceneMusic(),
                            force = true,
                            activeTrackId = sceneMusicController.activeTrackId,
                        )
                    }
                }''',
    "fresh auto cast",
)
# Exhausted resolver is terminal for this cast.
text = replace_once(
    text,
    '''                if (assignmentCount > 0) {
                    PlaybackQueueStore.setNarrationAutomation(''',
    '''                if (planResult?.freesoundRetryExhausted == true) {
                    diagnostic(
                        "FREESOUND_MODE3_RETRY_EXHAUSTED",
                        DiagnosticSeverity.ERROR,
                        mapOf(
                            "attempts" to planResult.freesoundRetryAttempts.toString(),
                            "resolvedAssets" to planResult.freesoundResolvedAssets.toString(),
                        ),
                    )
                    withContext(Dispatchers.Main) {
                        if (PlaybackQueueStore.state.value.chapterId != snapshot.chapterId) return@withContext
                        narrationPlanningChapterId = ""
                        pendingPlay = false
                        PlaybackQueueStore.setPlaying(false)
                        PlaybackQueueStore.setNarrationAutomation(
                            stage = NarrationAutomationStage.FAILED,
                            progress = 1f,
                            message = "Freesound thất bại sau 3 lần. Không có kế hoạch âm thanh hợp lệ để phát.",
                        )
                        transitionMessage = "Freesound thất bại sau 3 lần; hãy phân vai lại để tạo lượt mới."
                        updateMediaState()
                        updateNotification()
                    }
                    return@launch
                }

                val mode3Incomplete = StoryAudioModeRouter.usesAiFreesound(storyAudioSourceMode) &&
                    planResult?.freesoundRetryRequired == true
                if (assignmentCount > 0 && !mode3Incomplete) {
                    PlaybackQueueStore.setNarrationAutomation(''',
    "do not play incomplete",
)
text = replace_once(
    text,
    '''                withContext(Dispatchers.Main) {
                    if (PlaybackQueueStore.state.value.chapterId != snapshot.chapterId) return@withContext
                    PlaybackQueueStore.setPlaying(false)
                    PlaybackQueueStore.setNarrationAutomation(
                        stage = NarrationAutomationStage.FAILED,
                        progress = 1f,
                        message = "Phân vai chưa thành công. Sẽ tự thử lại sau 5 giây.$warningSuffix",
                    )
                    transitionMessage = "Phân vai chưa thành công; đang chờ thử lại."
                    updateMediaState()
                    updateNotification()
                }
                delay(NARRATION_RETRY_DELAY_MS)''',
    '''                withContext(Dispatchers.Main) {
                    if (PlaybackQueueStore.state.value.chapterId != snapshot.chapterId) return@withContext
                    PlaybackQueueStore.setPlaying(false)
                    PlaybackQueueStore.setNarrationAutomation(
                        stage = NarrationAutomationStage.FAILED,
                        progress = 1f,
                        message = if (attempt >= MAX_NARRATION_ATTEMPTS) {
                            "Phân vai/Mode 3 thất bại sau $MAX_NARRATION_ATTEMPTS lần.$warningSuffix"
                        } else {
                            "Chưa chuẩn bị xong. Sẽ thử lại sau 5 giây (lần ${attempt + 1}/$MAX_NARRATION_ATTEMPTS).$warningSuffix"
                        },
                    )
                    transitionMessage = if (attempt >= MAX_NARRATION_ATTEMPTS) {
                        "Phân vai/Mode 3 thất bại sau $MAX_NARRATION_ATTEMPTS lần."
                    } else {
                        "Chưa chuẩn bị xong; đang chờ thử lại."
                    }
                    updateMediaState()
                    updateNotification()
                }
                if (attempt >= MAX_NARRATION_ATTEMPTS) {
                    pendingPlay = false
                    return@launch
                }
                delay(NARRATION_RETRY_DELAY_MS)''',
    "auto final failure",
)
# Prefetch is also automatic casting: start from a fresh chapter state.
text = replace_once(
    text,
    '''                val attempt = runCatching {
                    container.narrationPlanCoordinator.ensurePlans(
                        content = chapter,
                        voice = planVoice,
                        music = shouldPlanAutoSceneMusic(),
                        activeTrackId = if (offset == 0) sceneMusicController.activeTrackId else null,
                    )
                }''',
    '''                val attempt = runCatching {
                    if (planVoice) {
                        container.narrationPlanCoordinator.resetChapterNarrationState(
                            content = chapter,
                            clearFreesoundCaches = true,
                        )
                    }
                    container.narrationPlanCoordinator.ensurePlans(
                        content = chapter,
                        voice = planVoice,
                        music = shouldPlanAutoSceneMusic(),
                        force = planVoice,
                        activeTrackId = if (offset == 0) sceneMusicController.activeTrackId else null,
                    )
                }''',
    "fresh prefetch",
)
text = replace_once(
    text,
    '''                    val failed = result == null || (planVoice && assignmentCount <= 0)''',
    '''                    val failed = result == null ||
                        (planVoice && assignmentCount <= 0) ||
                        (StoryAudioModeRouter.usesAiFreesound(storyAudioSourceMode) && result.freesoundRetryRequired)''',
    "prefetch incomplete",
)
text = replace_once(
    text,
    '''        private const val NARRATION_RETRY_DELAY_MS = 5_000L''',
    '''        private const val NARRATION_RETRY_DELAY_MS = 5_000L
        private const val MAX_NARRATION_ATTEMPTS = 3''',
    "auto max constant",
)
write(path, text)


# -----------------------------------------------------------------------------
# Manual PHÂN VAI AI / combined narration: wipe old chapter plans BEFORE the first
# request, force a fresh AI result, and cap outer AI-level retry at 3.
# -----------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt"
text = read(path)
fn = text.index("    private fun planNarrationForCurrentChapter(includeVoice: Boolean, includeMusic: Boolean) {")
loop = text.index("            var attempt = 0", fn)
reset_block = '''            if (includeVoice) {
                val reset = runCatching {
                    container.narrationPlanCoordinator.resetChapterNarrationState(
                        content = original,
                        clearFreesoundCaches = true,
                    )
                }
                if (reset.isFailure) {
                    val message = reset.exceptionOrNull()?.message ?: "Không xóa được dữ liệu phân vai cũ."
                    PlaybackQueueStore.setNarrationAutomation(
                        stage = NarrationAutomationStage.FAILED,
                        progress = 1f,
                        message = message,
                    )
                    mutableState.update { it.copy(aiBusy = false, message = message) }
                    return@launch
                }
            }
'''
text = text[:loop] + reset_block + text[loop:]
text = replace_once(
    text,
    '''            while (state.value.chapterContent?.chapter?.id == original.chapter.id) {''',
    '''            while (state.value.chapterContent?.chapter?.id == original.chapter.id &&
                attempt < MAX_MANUAL_NARRATION_ATTEMPTS
            ) {''',
    "manual loop cap",
)
# Insert explicit exhausted handling before assignment success.
anchor = "                val assignmentCount = container.narrationPlanCoordinator.voicePlanAssignmentCount(original)\n"
pos = text.index(anchor, fn) + len(anchor)
manual_guard = '''                if (result?.freesoundRetryExhausted == true) {
                    val message = "Freesound thất bại sau 3 lần. Không có kế hoạch âm thanh hợp lệ để phát."
                    PlaybackQueueStore.setNarrationAutomation(
                        stage = NarrationAutomationStage.FAILED,
                        progress = 1f,
                        message = message,
                    )
                    mutableState.update { it.copy(aiBusy = false, message = message) }
                    return@launch
                }
                val mode3Incomplete = container.narrationPlanCoordinator.storyAudioSourceMode() ==
                    vn.nghetruyen.app.audio.StoryAudioSourceMode.AI_FREESOUND &&
                    result?.freesoundRetryRequired == true
'''
text = text[:pos] + manual_guard + text[pos:]
text = replace_once(
    text,
    '''                if (assignmentCount > 0) {''',
    '''                if (assignmentCount > 0 && !mode3Incomplete) {''',
    "manual do not play incomplete",
)
text = replace_once(
    text,
    '''                mutableState.update { it.copy(message = null) }
                delay(MANUAL_NARRATION_RETRY_DELAY_MS)
                PlaybackQueueStore.setNarrationAutomation(
                    stage = NarrationAutomationStage.CURRENT_PLANNING,
                    progress = 0.2f,
                    message = "Đang thử phân vai lại lần ${attempt + 1}.",
                )''',
    '''                mutableState.update { it.copy(message = null) }
                if (attempt >= MAX_MANUAL_NARRATION_ATTEMPTS) {
                    val finalMessage = "Phân vai/Mode 3 thất bại sau $MAX_MANUAL_NARRATION_ATTEMPTS lần."
                    PlaybackQueueStore.setNarrationAutomation(
                        stage = NarrationAutomationStage.FAILED,
                        progress = 1f,
                        message = finalMessage,
                    )
                    mutableState.update { it.copy(aiBusy = false, message = finalMessage) }
                    return@launch
                }
                delay(MANUAL_NARRATION_RETRY_DELAY_MS)
                PlaybackQueueStore.setNarrationAutomation(
                    stage = NarrationAutomationStage.CURRENT_PLANNING,
                    progress = 0.2f,
                    message = "Đang thử phân vai lại lần ${attempt + 1}/$MAX_MANUAL_NARRATION_ATTEMPTS.",
                )''',
    "manual final failure",
)
text = replace_once(
    text,
    '''        const val MANUAL_NARRATION_RETRY_DELAY_MS = 5_000L''',
    '''        const val MANUAL_NARRATION_RETRY_DELAY_MS = 5_000L
        const val MAX_MANUAL_NARRATION_ATTEMPTS = 3''',
    "manual max constant",
)
write(path, text)

print("Final Mode 3 V7 patch applied successfully.")
