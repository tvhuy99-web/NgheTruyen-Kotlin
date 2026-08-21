from pathlib import Path


def read(path):
    return Path(path).read_text(encoding="utf-8")


def write(path, text):
    Path(path).write_text(text, encoding="utf-8")


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 occurrence, found {count}: {old[:120]!r}")
    return text.replace(old, new, 1)


# -----------------------------------------------------------------------------
# FreesoundAutoAudioResolver: fresh-run cache invalidation.
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
    "resolver query cache clear",
)
text = replace_once(
    text,
    '''    private val importer = FreesoundImporter(
        context = appContext,
        repository = repository,
        existingTracksProvider = existingTracksProvider,
    )

    suspend fun resolve(requirements: List<FreesoundAutoRequirement>): FreesoundAutoResolveResult {''',
    '''    private val importer = FreesoundImporter(
        context = appContext,
        repository = repository,
        existingTracksProvider = existingTracksProvider,
    )

    fun clearResolutionCaches() {
        queryCache.clear()
        client.clearSearchCache()
    }

    fun clearNetworkSearchCache() {
        client.clearSearchCache()
    }

    suspend fun resolve(requirements: List<FreesoundAutoRequirement>): FreesoundAutoResolveResult {''',
    "resolver clear methods",
)
write(path, text)


# -----------------------------------------------------------------------------
# NarrationPlanCoordinator: fresh reset + max 3 Freesound resolution attempts.
# -----------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/ai/NarrationPlanCoordinator.kt"
text = read(path)
text = replace_once(
    text,
    "import kotlinx.coroutines.sync.Mutex\n",
    "import kotlinx.coroutines.delay\nimport kotlinx.coroutines.sync.Mutex\n",
    "coordinator delay import",
)
text = replace_once(
    text,
    "import vn.nghetruyen.app.freesound.FreesoundImporter\n",
    "import vn.nghetruyen.app.freesound.FreesoundImporter\nimport vn.nghetruyen.app.freesound.FreesoundRequirementImportance\n",
    "coordinator importance import",
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
    "coordinator result fields",
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
    "coordinator apply fields",
)
text = replace_once(
    text,
    '''    private val planningMutex = Mutex()

    data class Result(''',
    '''    private val planningMutex = Mutex()
    private val freesoundRetryExhaustedChapters = linkedSetOf<String>()

    data class Result(''',
    "coordinator exhausted set",
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
    "coordinator reset API",
)
text = text.replace(
    "!standardFreesoundPlanCurrent(content, freesoundKinds)",
    "!standardFreesoundPlanCurrent(content, freesoundKinds, cachedFreesoundRequirements)",
)
text = text.replace(
    "freesoundRetryRequired = restoredFreesound.retryableFailure,\n",
    "freesoundRetryRequired = restoredFreesound.retryableFailure,\n                freesoundRetryAttempts = restoredFreesound.attempts,\n                freesoundRetryExhausted = restoredFreesound.retryExhausted,\n",
)
text = text.replace(
    "freesoundRetryRequired = autoApplied.retryableFailure,\n",
    "freesoundRetryRequired = autoApplied.retryableFailure,\n                    freesoundRetryAttempts = autoApplied.attempts,\n                    freesoundRetryExhausted = autoApplied.retryExhausted,\n",
)

standard_start = text.index("    private suspend fun standardFreesoundPlanCurrent(")
apply_start = text.index("    private suspend fun applyFreesoundRequirements(", standard_start)
new_standard = r'''    private suspend fun standardFreesoundPlanCurrent(
        content: ChapterContent,
        kinds: Set<AudioAssetKind>,
        requirements: List<FreesoundAutoRequirement>,
    ): Boolean {
        val requiredKinds = requirements.map(FreesoundAutoRequirement::kind).toSet()
        val enabled = library.listEnabledSceneMusicTracks()
            .filter { FreesoundImporter.soundIdFromManagedUri(it.uri) != null }
        val unitIds = XpkVoiceCastSplitter.buildUnits(content.chapter.title, chapterBody(content)).map { it.id }

        if (AudioAssetKind.MUSIC in kinds) {
            val musicTracks = enabled.filter { AudioAssetClassifier.classify(it) == AudioAssetKind.MUSIC }
            if (AudioAssetKind.MUSIC in requiredKinds && musicTracks.isEmpty()) return false
            val cached = library.getChapterTransform(content.chapter.id, ChapterAiWorkflow.KIND_SCENE_MUSIC) ?: return false
            if (cached.sourceSha256 != musicSourceHash(content, musicTracks)) return false
            if (!isExpectedAudioSourceMode(cached.transformedText, StoryAudioSourceMode.AI_FREESOUND)) return false
            if (!isCurrentTimelineTransform(cached.transformedText, MUSIC_TRANSFORM_ENGINE, content)) return false
            if (AudioAssetKind.MUSIC in requiredKinds) {
                val scenes = runCatching { JSONObject(cached.transformedText).optJSONArray("music_scenes") }.getOrNull()
                    ?: return false
                var hasRealScene = false
                for (index in 0 until scenes.length()) {
                    val trackId = scenes.optJSONObject(index)?.optString("track_id").orEmpty()
                    if (trackId.isNotBlank() && trackId != XpkSceneMusicParity.SILENCE_TRACK_ID) {
                        hasRealScene = true
                        break
                    }
                }
                if (!hasRealScene) return false
            }
        }
        if (AudioAssetKind.AMBIENCE in kinds || AudioAssetKind.SFX in kinds) {
            val ambienceTracks = if (AudioAssetKind.AMBIENCE in kinds) {
                enabled.filter { AudioAssetClassifier.classify(it) == AudioAssetKind.AMBIENCE }
            } else emptyList()
            val sfxTracks = if (AudioAssetKind.SFX in kinds) {
                enabled.filter { AudioAssetClassifier.classify(it) == AudioAssetKind.SFX }
            } else emptyList()
            if (AudioAssetKind.AMBIENCE in requiredKinds && ambienceTracks.isEmpty()) return false
            if (AudioAssetKind.SFX in requiredKinds && sfxTracks.isEmpty()) return false
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
            val decoded = runCatching {
                XpkAmbienceSfxDirector.decodePersisted(
                    text = cached.transformedText,
                    validUnitIds = unitIds,
                    validAmbienceIds = ambienceTracks.map(SceneMusicTrackEntity::id).toSet(),
                    validSfxIds = sfxTracks.map(SceneMusicTrackEntity::id).toSet(),
                    ambienceEnabled = AudioAssetKind.AMBIENCE in kinds,
                    soundEffectsEnabled = AudioAssetKind.SFX in kinds,
                )
            }.getOrNull() ?: return false
            if (AudioAssetKind.AMBIENCE in requiredKinds && decoded.ambienceScenes.isEmpty()) return false
            if (AudioAssetKind.SFX in requiredKinds && decoded.soundEffectCues.isEmpty()) return false
        }
        return true
    }

'''
text = text[:standard_start] + new_standard + text[apply_start:]

apply_start = text.index("    private suspend fun applyFreesoundRequirements(")
apply_end = text.index("    /** Canonical XPK assignments", apply_start)
once = text[apply_start:apply_end]
once = once.replace(
    "    private suspend fun applyFreesoundRequirements(\n",
    "    private suspend fun applyFreesoundRequirementsOnce(\n",
    1,
)
once = replace_once(
    once,
    '''        var musicCreated = false
        var audioCreated = false
''',
    '''        var musicCreated = false
        var audioCreated = false
        var musicCueCount = 0
        var ambienceCueCount = 0
        var sfxCueCount = 0
''',
    "coordinator cue counters",
)
once = replace_once(
    once,
    '''            diagnostics += "PLAN_MUSIC rawCues=${rawCues.size} validatedCues=${validated.size} managedMusicTracks=${musicTracks.size}"
''',
    '''            musicCueCount = validated.count { it.trackId != XpkSceneMusicParity.SILENCE_TRACK_ID }
            diagnostics += "PLAN_MUSIC rawCues=${rawCues.size} validatedCues=${validated.size} realCues=$musicCueCount managedMusicTracks=${musicTracks.size}"
''',
    "coordinator music cue count",
)
once = replace_once(
    once,
    '''            diagnostics += "PLAN_AUDIO ambienceCandidates=$originalAmbienceCount ambienceValidated=${validatedAmbience.ambienceScenes.size} ambienceTracks=${ambienceTracks.size} sfxCandidates=$originalSfxCount sfxValidated=${validatedSfx.soundEffectCues.size} sfxTracks=${sfxTracks.size}"
''',
    '''            ambienceCueCount = validatedAmbience.ambienceScenes.size
            sfxCueCount = validatedSfx.soundEffectCues.size
            diagnostics += "PLAN_AUDIO ambienceCandidates=$originalAmbienceCount ambienceValidated=$ambienceCueCount ambienceTracks=${ambienceTracks.size} sfxCandidates=$originalSfxCount sfxValidated=$sfxCueCount sfxTracks=${sfxTracks.size}"
''',
    "coordinator audio cue counts",
)
old_return = '''        diagnostics += "PLAN_BUILD_DONE musicCreated=$musicCreated audioCreated=$audioCreated resolvedAssets=${resolved.resolvedCount} retryableFailure=${resolved.retryableFailure}"
        return FreesoundApplyResult(
            musicCreated = musicCreated,
            audioCreated = audioCreated,
            resolvedAssets = resolved.resolvedCount,
            warnings = warnings.distinct(),
            retryableFailure = resolved.retryableFailure,
            diagnostics = diagnostics.distinct(),
        )
    }

'''
new_return = '''        val unresolvedCount = resolved.resolved.count { it.trackId.isNullOrBlank() }
        val requiredUnresolvedCount = resolved.resolved.count {
            it.trackId.isNullOrBlank() && it.need.importance == FreesoundRequirementImportance.REQUIRED
        }
        val requestedKinds = requirements.map(FreesoundAutoRequirement::kind).toSet()
        val missingPlanKinds = buildList {
            if (AudioAssetKind.MUSIC in requestedKinds && musicCueCount <= 0) add(AudioAssetKind.MUSIC.name)
            if (AudioAssetKind.AMBIENCE in requestedKinds && ambienceCueCount <= 0) add(AudioAssetKind.AMBIENCE.name)
            if (AudioAssetKind.SFX in requestedKinds && sfxCueCount <= 0) add(AudioAssetKind.SFX.name)
        }
        val incomplete = resolved.retryableFailure ||
            (requirements.isNotEmpty() && resolved.resolvedCount <= 0) ||
            requiredUnresolvedCount > 0 ||
            missingPlanKinds.isNotEmpty()
        diagnostics += "PLAN_BUILD_DONE musicCreated=$musicCreated audioCreated=$audioCreated resolvedAssets=${resolved.resolvedCount} unresolved=$unresolvedCount requiredUnresolved=$requiredUnresolvedCount missingPlanKinds=${missingPlanKinds.joinToString(",")} retryRequired=$incomplete"
        return FreesoundApplyResult(
            musicCreated = musicCreated,
            audioCreated = audioCreated,
            resolvedAssets = resolved.resolvedCount,
            warnings = warnings.distinct(),
            retryableFailure = incomplete,
            diagnostics = diagnostics.distinct(),
            attempts = 1,
        )
    }

'''
once = replace_once(once, old_return, new_return, "coordinator apply return")

wrapper = r'''    private suspend fun applyFreesoundRequirements(
        content: ChapterContent,
        requirements: List<FreesoundAutoRequirement>,
        kinds: Set<AudioAssetKind>,
    ): FreesoundApplyResult {
        val chapterId = content.chapter.id
        if (requirements.isEmpty()) {
            freesoundRetryExhaustedChapters += chapterId
            return FreesoundApplyResult(
                musicCreated = false,
                audioCreated = false,
                resolvedAssets = 0,
                warnings = listOf("AI không gửi yêu cầu Freesound nào; không có dữ liệu để resolve âm thanh."),
                retryableFailure = true,
                diagnostics = listOf("RESOLVE_ABORT requirements=0 reason=AI_REQUIREMENTS_EMPTY"),
                attempts = 0,
                retryExhausted = true,
            )
        }
        if (chapterId in freesoundRetryExhaustedChapters) {
            return FreesoundApplyResult(
                musicCreated = false,
                audioCreated = false,
                resolvedAssets = 0,
                warnings = listOf("Freesound đã thất bại sau $MAX_FREESOUND_RUNTIME_ATTEMPTS lần; cần bắt đầu một lượt phân vai mới."),
                retryableFailure = true,
                diagnostics = listOf("RESOLVE_BLOCKED retryExhausted=true maxAttempts=$MAX_FREESOUND_RUNTIME_ATTEMPTS"),
                attempts = MAX_FREESOUND_RUNTIME_ATTEMPTS,
                retryExhausted = true,
            )
        }

        var latest = FreesoundApplyResult(false, false, 0, emptyList())
        val allWarnings = mutableListOf<String>()
        val allDiagnostics = mutableListOf<String>()
        for (attempt in 1..MAX_FREESOUND_RUNTIME_ATTEMPTS) {
            if (attempt > 1) {
                delay(FREESOUND_RUNTIME_RETRY_DELAY_MS)
                freesoundResolver.clearNetworkSearchCache()
            }
            allDiagnostics += "RUNTIME_RETRY_START attempt=$attempt/$MAX_FREESOUND_RUNTIME_ATTEMPTS"
            latest = applyFreesoundRequirementsOnce(content, requirements, kinds)
            allWarnings += latest.warnings
            allDiagnostics += latest.diagnostics
            allDiagnostics += "RUNTIME_RETRY_RESULT attempt=$attempt resolved=${latest.resolvedAssets} retryRequired=${latest.retryableFailure}"
            if (!latest.retryableFailure) {
                freesoundRetryExhaustedChapters.remove(chapterId)
                return latest.copy(
                    warnings = allWarnings.distinct(),
                    diagnostics = allDiagnostics.distinct(),
                    attempts = attempt,
                    retryExhausted = false,
                )
            }
        }

        freesoundRetryExhaustedChapters += chapterId
        val failure = "Freesound không tạo được kế hoạch âm thanh hợp lệ sau $MAX_FREESOUND_RUNTIME_ATTEMPTS lần thử."
        return latest.copy(
            warnings = (allWarnings + failure).distinct(),
            retryableFailure = true,
            diagnostics = (allDiagnostics + "RUNTIME_RETRY_EXHAUSTED attempts=$MAX_FREESOUND_RUNTIME_ATTEMPTS").distinct(),
            attempts = MAX_FREESOUND_RUNTIME_ATTEMPTS,
            retryExhausted = true,
        )
    }

'''
text = text[:apply_start] + wrapper + once + text[apply_end:]
text = replace_once(
    text,
    '''            .put("resolution_retry_required", retryRequired)
            .put(FreesoundAutoRequirementCodec.JSON_KEY, FreesoundAutoRequirementCodec.toJson(requirements))''',
    '''            .put("resolution_retry_required", retryRequired)
            .put("resolution_retry_exhausted", content.chapter.id in freesoundRetryExhaustedChapters)
            .put(FreesoundAutoRequirementCodec.JSON_KEY, FreesoundAutoRequirementCodec.toJson(requirements))''',
    "coordinator persist exhausted",
)
text = replace_once(
    text,
    '''                    if (outcome.value.freesoundRequirements.isEmpty()) {
                        warnings += "AI không yêu cầu MUSIC/AMBIENCE/SFX Freesound nào cho chương này; các lớp Mode 3 tương ứng sẽ im lặng."
                    }
                    autoApplied = applyFreesoundRequirements(content, outcome.value.freesoundRequirements, freesoundKinds)
''',
    '''                    if (outcome.value.freesoundRequirements.isEmpty()) {
                        warnings += "AI không yêu cầu MUSIC/AMBIENCE/SFX Freesound nào cho chương này; lượt Mode 3 được đánh dấu thất bại thay vì chấp nhận kế hoạch rỗng."
                    }
                    autoApplied = applyFreesoundRequirements(content, outcome.value.freesoundRequirements, freesoundKinds)
''',
    "coordinator empty requirements warning",
)
text = replace_once(
    text,
    '''        private const val FREESOUND_AUTO_ENGINE = "freesound-auto-audio-v2"
''',
    '''        private const val FREESOUND_AUTO_ENGINE = "freesound-auto-audio-v2"
        private const val MAX_FREESOUND_RUNTIME_ATTEMPTS = 3
        private const val FREESOUND_RUNTIME_RETRY_DELAY_MS = 1_200L
''',
    "coordinator retry constants",
)
write(path, text)


# -----------------------------------------------------------------------------
# AudioDirectionRuntime: never accept missing files / empty Mode-3 runtime plan.
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
        }
''',
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
        }
''',
    "runtime fast-path empty repair",
)
text = replace_once(
    text,
    '''            if (StoryAudioModeRouter.usesAiFreesound(sourceMode)) {
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
''',
    '''            if (StoryAudioModeRouter.usesAiFreesound(sourceMode)) {
                diagnostic(
                    "FREESOUND_RUNTIME_AUDIO_PREPARE_RESULT",
                    if (outcome.freesoundResolvedAssets > 0 && !outcome.freesoundRetryRequired) DiagnosticSeverity.INFO else DiagnosticSeverity.WARN,
                    mapOf(
                        "resolvedAssets" to outcome.freesoundResolvedAssets.toString(),
                        "audioPlanCreated" to outcome.audioPlanCreated.toString(),
                        "retryRequired" to outcome.freesoundRetryRequired.toString(),
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
            }
''',
    "runtime exhausted handling",
)
text = replace_once(
    text,
    '''            !StoryAudioModeRouter.usesAiFreesound(sourceMode) ||
                FreesoundImporter.soundIdFromManagedUri(track.uri) != null
''',
    '''            !StoryAudioModeRouter.usesAiFreesound(sourceMode) ||
                (FreesoundImporter.soundIdFromManagedUri(track.uri) != null &&
                    FreesoundImporter.managedFileExists(appContext, track.uri))
''',
    "runtime managed file filter",
)
text = replace_once(
    text,
    '''                    "filesExist" to assets.all { FreesoundImporter.managedFileExists(appContext, it.uri) }.toString(),
''',
    '''                    "filesExist" to (assets.isNotEmpty() && assets.all { FreesoundImporter.managedFileExists(appContext, it.uri) }).toString(),
''',
    "runtime empty filesExist",
)
write(path, text)


# -----------------------------------------------------------------------------
# ReaderPlaybackService: auto-cast is fresh, stops after 3 AI attempts, and does not
# start playback when Mode 3 has exhausted its three Freesound attempts.
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
            ) DiagnosticSeverity.WARN else DiagnosticSeverity.INFO
''',
    '''            val severity = DiagnosticSeverity.WARN
''',
    "service basic diagnostics visibility",
)
text = replace_once(
    text,
    '''                "retryRequired" to (result?.freesoundRetryRequired ?: false).toString(),
                "traceCount" to (result?.freesoundDiagnostics?.size ?: 0).toString(),
''',
    '''                "retryRequired" to (result?.freesoundRetryRequired ?: false).toString(),
                "retryAttempts" to (result?.freesoundRetryAttempts ?: 0).toString(),
                "retryExhausted" to (result?.freesoundRetryExhausted ?: false).toString(),
                "traceCount" to (result?.freesoundDiagnostics?.size ?: 0).toString(),
''',
    "service plan result attempts",
)
text = replace_once(
    text,
    '''            while (currentStoryAutoVoiceCastEnabled && PlaybackQueueStore.state.value.chapterId == snapshot.chapterId) {
''',
    '''            while (currentStoryAutoVoiceCastEnabled &&
                PlaybackQueueStore.state.value.chapterId == snapshot.chapterId &&
                attempt < MAX_NARRATION_ATTEMPTS
            ) {
''',
    "service auto loop cap",
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
                }
''',
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
                }
''',
    "service fresh auto planning",
)
text = replace_once(
    text,
    '''                if (assignmentCount > 0) {
                    PlaybackQueueStore.setNarrationAutomation(
''',
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
                            message = "Freesound thất bại sau ${planResult.freesoundRetryAttempts.coerceAtLeast(3)} lần. Không có kế hoạch âm thanh hợp lệ để phát.",
                        )
                        transitionMessage = "Freesound thất bại sau 3 lần; hãy phân vai lại để tạo lượt mới."
                        updateMediaState()
                        updateNotification()
                    }
                    return@launch
                }

                if (assignmentCount > 0) {
                    PlaybackQueueStore.setNarrationAutomation(
''',
    "service exhausted before ready",
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
                delay(NARRATION_RETRY_DELAY_MS)
''',
    '''                withContext(Dispatchers.Main) {
                    if (PlaybackQueueStore.state.value.chapterId != snapshot.chapterId) return@withContext
                    PlaybackQueueStore.setPlaying(false)
                    PlaybackQueueStore.setNarrationAutomation(
                        stage = NarrationAutomationStage.FAILED,
                        progress = 1f,
                        message = if (attempt >= MAX_NARRATION_ATTEMPTS) {
                            "Phân vai thất bại sau $MAX_NARRATION_ATTEMPTS lần.$warningSuffix"
                        } else {
                            "Phân vai chưa thành công. Sẽ tự thử lại sau 5 giây (lần ${attempt + 1}/$MAX_NARRATION_ATTEMPTS).$warningSuffix"
                        },
                    )
                    transitionMessage = if (attempt >= MAX_NARRATION_ATTEMPTS) {
                        "Phân vai thất bại sau $MAX_NARRATION_ATTEMPTS lần."
                    } else {
                        "Phân vai chưa thành công; đang chờ thử lại."
                    }
                    updateMediaState()
                    updateNotification()
                }
                if (attempt >= MAX_NARRATION_ATTEMPTS) {
                    pendingPlay = false
                    return@launch
                }
                delay(NARRATION_RETRY_DELAY_MS)
''',
    "service stop after 3",
)
text = replace_once(
    text,
    '''                val attempt = runCatching {
                    container.narrationPlanCoordinator.ensurePlans(
                        content = chapter,
                        voice = planVoice,
                        music = shouldPlanAutoSceneMusic(),
                        activeTrackId = if (offset == 0) sceneMusicController.activeTrackId else null,
                    )
                }
''',
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
                }
''',
    "service fresh prefetch",
)
needle = '''                    !StoryAudioModeRouter.usesAiFreesound(storyAudioSourceMode) ||
                        FreesoundImporter.soundIdFromManagedUri(track.uri) != null
'''
count = text.count(needle)
if count < 1:
    raise SystemExit(f"service music file filter: expected >=1 occurrence, found {count}")
text = text.replace(
    needle,
    '''                    !StoryAudioModeRouter.usesAiFreesound(storyAudioSourceMode) ||
                        (FreesoundImporter.soundIdFromManagedUri(track.uri) != null &&
                            FreesoundImporter.managedFileExists(this@ReaderPlaybackService, track.uri))
''',
)
text = replace_once(
    text,
    '''        private const val NARRATION_RETRY_DELAY_MS = 5_000L
''',
    '''        private const val NARRATION_RETRY_DELAY_MS = 5_000L
        private const val MAX_NARRATION_ATTEMPTS = 3
''',
    "service max attempts constant",
)
write(path, text)


# -----------------------------------------------------------------------------
# AppViewModel: manual PHÂN VAI AI always starts from a clean chapter state and
# never retries more than three times.
# -----------------------------------------------------------------------------
path = "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt"
text = read(path)
fn_start = text.index("    private fun planNarrationForCurrentChapter(includeVoice: Boolean, includeMusic: Boolean) {")
var_attempt = text.index("            var attempt = 0", fn_start)
insert = '''            if (includeVoice) {
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
text = text[:var_attempt] + insert + text[var_attempt:]
assignment_anchor = text.index("                val assignmentCount = container.narrationPlanCoordinator.voicePlanAssignmentCount(original)", fn_start)
if_block = text.index("                if (assignmentCount > 0) {", assignment_anchor)
manual_guard = '''                if (result?.freesoundRetryExhausted == true) {
                    val message = "Freesound thất bại sau ${result.freesoundRetryAttempts.coerceAtLeast(3)} lần. Không có kế hoạch âm thanh hợp lệ để phát."
                    PlaybackQueueStore.setNarrationAutomation(
                        stage = NarrationAutomationStage.FAILED,
                        progress = 1f,
                        message = message,
                    )
                    mutableState.update { it.copy(aiBusy = false, message = message) }
                    return@launch
                }

'''
text = text[:if_block] + manual_guard + text[if_block:]
old_retry = '''                mutableState.update { it.copy(message = null) }
                delay(MANUAL_NARRATION_RETRY_DELAY_MS)
                PlaybackQueueStore.setNarrationAutomation(
                    stage = NarrationAutomationStage.CURRENT_PLANNING,
                    progress = 0.2f,
                    message = "Đang thử phân vai lại lần ${attempt + 1}.",
                )
'''
new_retry = '''                mutableState.update { it.copy(message = null) }
                if (attempt >= MAX_MANUAL_NARRATION_ATTEMPTS) {
                    val finalMessage = "Phân vai thất bại sau $MAX_MANUAL_NARRATION_ATTEMPTS lần."
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
                )
'''
text = replace_once(text, old_retry, new_retry, "viewmodel stop after 3")
text = replace_once(
    text,
    '''        const val MANUAL_NARRATION_RETRY_DELAY_MS = 5_000L
''',
    '''        const val MANUAL_NARRATION_RETRY_DELAY_MS = 5_000L
        const val MAX_MANUAL_NARRATION_ATTEMPTS = 3
''',
    "viewmodel max attempts constant",
)
write(path, text)

print("Mode 3 V7 fresh-plan + max-3 retry patch applied successfully.")
