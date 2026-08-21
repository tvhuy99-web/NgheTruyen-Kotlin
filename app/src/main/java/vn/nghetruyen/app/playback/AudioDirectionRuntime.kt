package vn.nghetruyen.app.playback

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.ai.ChapterAiWorkflow
import vn.nghetruyen.app.ai.NarrationPlanCoordinator
import vn.nghetruyen.app.audio.AmbienceSfxPlan
import vn.nghetruyen.app.audio.AudioAssetClassifier
import vn.nghetruyen.app.audio.AudioAssetKind
import vn.nghetruyen.app.audio.AudioAssetVariantFamily
import vn.nghetruyen.app.audio.AudioDirectionAsset
import vn.nghetruyen.app.audio.AudioDirectionPreferences
import vn.nghetruyen.app.audio.PcmLoudnessEstimator
import vn.nghetruyen.app.audio.SoundEffectCue
import vn.nghetruyen.app.audio.StoryAudioModeRouter
import vn.nghetruyen.app.audio.StoryAudioSourceMode
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity
import vn.nghetruyen.app.data.repository.LibraryRepository
import vn.nghetruyen.app.freesound.FreesoundImporter
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity

/**
 * Playback consumer for the unified XPK chapter plan. This class never calls an AI provider itself:
 * when a plan is absent/stale it asks [NarrationPlanCoordinator] to prepare the whole active chapter
 * once, then consumes the persisted AMBIENCE/SFX portion on the canonical UNIT timeline.
 *
 * A start/stop session owns all collector and preference-listener coroutines. Cheap playback emissions
 * (for example every TTS paragraph) reuse the already validated chapter plan and only revalidate assets
 * periodically, avoiding repeated DB reads and chapter hashing on the hot playback path.
 */
class AudioDirectionRuntime(
    context: Context,
    private val libraryRepository: LibraryRepository,
    private val preferences: AudioDirectionPreferences,
    private val narrationPlanCoordinator: NarrationPlanCoordinator,
) {
    private data class RuntimeSfxCue(val key: String, val cue: SoundEffectCue)

    private val appContext = context.applicationContext
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

    private var runtimeJob: Job? = null
    @Volatile private var runtimeScope: CoroutineScope? = null
    private var collectorJob: Job? = null
    private var preparedChapterId = ""
    private var preparedSignature = ""
    private var failedSignature = ""
    private var failedAtMillis = 0L
    private var assetsById: Map<String, AudioDirectionAsset> = emptyMap()
    private var ambienceVariantsById: Map<String, List<AudioDirectionAsset>> = emptyMap()
    private var ambienceByUnitId: Map<String, List<String>> = emptyMap()
    private var sfxByUnitId: Map<String, List<RuntimeSfxCue>> = emptyMap()
    private var sfxCueByKey: Map<String, RuntimeSfxCue> = emptyMap()
    private var boundedSfxCueKeys: Set<String> = emptySet()
    private var allowedBoundedSfxKeysByUnitId: Map<String, Set<String>> = emptyMap()
    private var lastTriggeredSfxKey = ""
    private var lastSettings: AudioDirectionPreferences.Snapshot? = null
    private var validatedFastKey = ""
    private var validatedFastAtMillis = 0L
    private var cachedParagraphChapterId = ""
    private var cachedParagraphIdentity = 0
    private var cachedParagraphCount = -1
    private var cachedParagraphHash = ""

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runtimeScope?.launch {
            mutex.withLock { handleSnapshot(PlaybackQueueStore.state.value, forcePreferences = true) }
        }
    }

    fun start() {
        if (runtimeJob?.isActive == true) return
        val job = SupervisorJob()
        val sessionScope = CoroutineScope(job + Dispatchers.IO)
        runtimeJob = job
        runtimeScope = sessionScope
        preferences.addChangeListener(preferenceListener)
        collectorJob = sessionScope.launch {
            PlaybackQueueStore.state.collect { snapshot ->
                mutex.withLock { handleSnapshot(snapshot, forcePreferences = false) }
            }
        }
    }

    fun stop() {
        preferences.removeChangeListener(preferenceListener)
        runtimeScope = null
        runtimeJob?.cancel()
        runtimeJob = null
        collectorJob = null
        ambienceController.stop()
        sfxController.stopAll()
        lastSettings = null
        clearPreparedPlan()
    }

    private suspend fun handleSnapshot(snapshot: PlaybackSnapshot, forcePreferences: Boolean) {
        val settings = preferences.snapshot()
        val previousSettings = lastSettings
        lastSettings = settings
        val planningSwitchChanged = forcePreferences && (
            previousSettings == null ||
                previousSettings.ambienceEnabled != settings.ambienceEnabled ||
                previousSettings.soundEffectsEnabled != settings.soundEffectsEnabled
            )

        if (!settings.ambienceEnabled && !settings.soundEffectsEnabled) {
            ambienceController.stop()
            sfxController.stopAll()
            lastTriggeredSfxKey = ""
            if (forcePreferences) clearPreparedPlan()
            return
        }
        if (snapshot.chapterId.isBlank() || snapshot.speechChunks.none { it.unitId.isNotBlank() }) {
            ambienceController.stop()
            sfxController.stopAll()
            lastTriggeredSfxKey = ""
            return
        }

        if (!snapshot.isPlaying) {
            ambienceController.pause()
            sfxController.stopAll()
            lastTriggeredSfxKey = ""
            if (planningSwitchChanged) clearPreparedPlan()
            return
        }

        val prepared = ensurePlan(snapshot, settings, planningSwitchChanged)
        if (!prepared) {
            ambienceController.stop()
            sfxController.stopAll()
            lastTriggeredSfxKey = ""
            return
        }

        val unitId = snapshot.currentUnitId.orEmpty()
        if (unitId.isBlank()) return
        applyAmbience(unitId, settings)
        applySfx(snapshot.chapterId, unitId, settings)
    }

    private suspend fun ensurePlan(
        snapshot: PlaybackSnapshot,
        settings: AudioDirectionPreferences.Snapshot,
        force: Boolean,
    ): Boolean {
        val now = System.currentTimeMillis()
        val sourceMode = narrationPlanCoordinator.storyAudioSourceMode()
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
        val mode3RuntimeEmpty = StoryAudioModeRouter.usesAiFreesound(sourceMode) &&
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

        var rawTracks = libraryRepository.listEnabledSceneMusicTracks()
        var allAssets = buildRuntimeAssets(rawTracks, settings, sourceMode)
        var activeAudioAssets = activeAudioAssets(allAssets, settings)
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

        val validUnits = snapshot.speechChunks.map { it.unitId }.filter(String::isNotBlank)
        var signature = buildPlanSignature(
            snapshot = snapshot,
            settings = settings,
            sourceMode = sourceMode,
            rawTracks = rawTracks,
            activeAudioAssets = activeAudioAssets,
            validUnits = validUnits,
        )

        if (!force && preparedChapterId == snapshot.chapterId && preparedSignature == signature) {
            markFastValidation(fastKey, now)
            return true
        }
        if (!force && failedSignature == signature && now - failedAtMillis < FAILURE_RETRY_COOLDOWN_MS) {
            return false
        }

        if (activeAudioAssets.isEmpty() && !StoryAudioModeRouter.usesAiFreesound(sourceMode)) {
            clearFailure()
            installPlan(snapshot.chapterId, signature, validUnits, AmbienceSfxPlan())
            markFastValidation(fastKey, now)
            return true
        }

        val content = libraryRepository.loadCachedChapter(snapshot.chapterId)
        if (content == null) {
            markFailure(signature)
            return false
        }

        var plan = narrationPlanCoordinator.loadAudioDirectionPlan(content)
        val mustPrepareFreesound = StoryAudioModeRouter.usesAiFreesound(sourceMode) && activeAudioAssets.isEmpty()
        if (force || plan == null || mustPrepareFreesound) {
            val outcome = runCatching {
                narrationPlanCoordinator.ensureActivePlans(content = content, force = force)
            }.getOrNull()
            if (outcome == null) {
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

            // Mode 3 can download and normalize assets during ensureActivePlans(). The first playback
            // pass must immediately consume that new library state instead of keeping the pre-download
            // snapshot until a later paragraph/revalidation.
            rawTracks = libraryRepository.listEnabledSceneMusicTracks()
            allAssets = buildRuntimeAssets(rawTracks, settings, sourceMode)
            activeAudioAssets = activeAudioAssets(allAssets, settings)
            updateRuntimeAssets(allAssets)
            signature = buildPlanSignature(
                snapshot = snapshot,
                settings = settings,
                sourceMode = sourceMode,
                rawTracks = rawTracks,
                activeAudioAssets = activeAudioAssets,
                validUnits = validUnits,
            )
            plan = narrationPlanCoordinator.loadAudioDirectionPlan(content)
        }
        if (plan == null) {
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
        markFastValidation(fastKey, now)
        return true
    }

    private fun buildRuntimeAssets(
        rawTracks: List<SceneMusicTrackEntity>,
        settings: AudioDirectionPreferences.Snapshot,
        sourceMode: StoryAudioSourceMode,
    ): List<AudioDirectionAsset> = rawTracks
        .asSequence()
        .filter { track ->
            !StoryAudioModeRouter.usesAiFreesound(sourceMode) ||
                (FreesoundImporter.soundIdFromManagedUri(track.uri) != null &&
                    FreesoundImporter.managedFileExists(appContext, track.uri))
        }
        .map { track ->
        val asset = AudioAssetClassifier.toAsset(track)
        if (asset.kind == AudioAssetKind.MUSIC) {
            asset
        } else {
            val targetLufs = when (asset.kind) {
                AudioAssetKind.AMBIENCE -> settings.ambienceNormalizationTargetLufs
                AudioAssetKind.SFX -> settings.soundEffectsNormalizationTargetLufs
                AudioAssetKind.MUSIC -> track.normalizationTargetLufs
            }
            val gainDb = if (
                track.normalizationVersion >= PcmLoudnessEstimator.VERSION &&
                track.normalizationError.isBlank() &&
                track.loudnessLufsEstimate.isFinite() &&
                track.peakDbfs.isFinite()
            ) {
                PcmLoudnessEstimator.calculateNormalization(
                    track.loudnessLufsEstimate,
                    track.peakDbfs,
                    targetLufs,
                ).gainDb
            } else {
                0f
            }
            asset.copy(normalizationGainDb = gainDb)
        }
    }.filter { it.id.isNotBlank() && it.uri.isNotBlank() }.toList()

    private fun activeAudioAssets(
        allAssets: List<AudioDirectionAsset>,
        settings: AudioDirectionPreferences.Snapshot,
    ): List<AudioDirectionAsset> = allAssets.filter { asset ->
        (settings.ambienceEnabled && asset.kind == AudioAssetKind.AMBIENCE) ||
            (settings.soundEffectsEnabled && asset.kind == AudioAssetKind.SFX)
    }

    private fun updateRuntimeAssets(allAssets: List<AudioDirectionAsset>) {
        assetsById = allAssets.associateBy(AudioDirectionAsset::id)
        ambienceVariantsById = buildAmbienceVariants(allAssets)
    }

    private fun buildPlanSignature(
        snapshot: PlaybackSnapshot,
        settings: AudioDirectionPreferences.Snapshot,
        sourceMode: StoryAudioSourceMode,
        rawTracks: List<SceneMusicTrackEntity>,
        activeAudioAssets: List<AudioDirectionAsset>,
        validUnits: List<String>,
    ): String {
        val rawTracksById = rawTracks.associateBy { it.id }
        val signatureParts = ArrayList<String>(activeAudioAssets.size * 5 + 6)
        signatureParts += "content=${paragraphFingerprint(snapshot)}"
        activeAudioAssets.forEach { asset ->
            val row = rawTracksById[asset.id]
            signatureParts += asset.id
            signatureParts += asset.title
            signatureParts += asset.description
            signatureParts += asset.kind.name
            signatureParts += row?.updatedAt?.toString().orEmpty()
        }
        signatureParts += "chapter=${snapshot.chapterId}"
        signatureParts += "mode=${sourceMode.name}"
        signatureParts += "ambience=${settings.ambienceEnabled}"
        signatureParts += "sfx=${settings.soundEffectsEnabled}"
        signatureParts += "timeline=${validUnits.joinToString(",")}"
        return ChapterAiWorkflow.sha256(signatureParts)
    }

    private fun buildFastKey(
        snapshot: PlaybackSnapshot,
        settings: AudioDirectionPreferences.Snapshot,
        sourceMode: StoryAudioSourceMode,
    ): String = buildString(112) {
        append(snapshot.chapterId)
        append('|').append(System.identityHashCode(snapshot.paragraphs))
        append(':').append(snapshot.paragraphs.size)
        append('|').append(System.identityHashCode(snapshot.speechChunks))
        append(':').append(snapshot.speechChunks.size)
        append('|').append(sourceMode.name)
        append('|').append(settings.ambienceEnabled)
        append('|').append(settings.soundEffectsEnabled)
        append('|').append(settings.ambienceNormalizationTargetLufs)
        append('|').append(settings.soundEffectsNormalizationTargetLufs)
    }

    private fun paragraphFingerprint(snapshot: PlaybackSnapshot): String {
        val identity = System.identityHashCode(snapshot.paragraphs)
        if (cachedParagraphChapterId == snapshot.chapterId &&
            cachedParagraphIdentity == identity &&
            cachedParagraphCount == snapshot.paragraphs.size &&
            cachedParagraphHash.isNotBlank()
        ) {
            return cachedParagraphHash
        }
        return ChapterAiWorkflow.sha256(snapshot.paragraphs).also { hash ->
            cachedParagraphChapterId = snapshot.chapterId
            cachedParagraphIdentity = identity
            cachedParagraphCount = snapshot.paragraphs.size
            cachedParagraphHash = hash
        }
    }

    private fun markFastValidation(key: String, now: Long = System.currentTimeMillis()) {
        validatedFastKey = key
        validatedFastAtMillis = now
    }

    private fun installPlan(
        chapterId: String,
        signature: String,
        validUnits: List<String>,
        newPlan: AmbienceSfxPlan,
    ) {
        val order = validUnits.withIndex().associate { it.value to it.index }
        val ambienceMap = linkedMapOf<String, MutableList<String>>()
        newPlan.ambienceScenes.forEach { scene ->
            val start = order[scene.startUnitId] ?: return@forEach
            val end = order[scene.endUnitId] ?: return@forEach
            for (index in start..end) {
                val unitId = validUnits[index]
                val ids = ambienceMap.getOrPut(unitId) { mutableListOf() }
                if (scene.ambienceId !in ids) ids += scene.ambienceId
            }
        }
        ambienceByUnitId = ambienceMap.mapValues { (_, ids) -> ids.toList() }

        sfxController.stopAll()
        val runtimeCues = newPlan.soundEffectCues.mapIndexed { index, cue ->
            RuntimeSfxCue(
                key = "$chapterId:${cue.unitId}:${cue.effectId}:$index",
                cue = cue,
            )
        }
        sfxByUnitId = runtimeCues.groupBy { it.cue.unitId }
        sfxCueByKey = runtimeCues.associateBy(RuntimeSfxCue::key)
        val boundedKeys = linkedSetOf<String>()
        val allowedByUnit = linkedMapOf<String, MutableSet<String>>()
        runtimeCues.forEach { runtimeCue ->
            val cue = runtimeCue.cue
            val stopUnitId = cue.stopUnitId ?: return@forEach
            val start = order[cue.unitId] ?: return@forEach
            val stopExclusive = order[stopUnitId] ?: return@forEach
            if (stopExclusive <= start) return@forEach
            boundedKeys += runtimeCue.key
            for (index in start until stopExclusive) {
                allowedByUnit.getOrPut(validUnits[index]) { linkedSetOf() } += runtimeCue.key
            }
        }
        boundedSfxCueKeys = boundedKeys
        allowedBoundedSfxKeysByUnitId = allowedByUnit.mapValues { (_, keys) -> keys.toSet() }

        preparedChapterId = chapterId
        preparedSignature = signature
        lastTriggeredSfxKey = ""
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
    }

    private fun applyAmbience(unitId: String, settings: AudioDirectionPreferences.Snapshot) {
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
                    "filesExist" to (assets.isNotEmpty() &&
                        assets.all { FreesoundImporter.managedFileExists(appContext, it.uri) }).toString(),
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

    private fun applySfx(
        chapterId: String,
        unitId: String,
        settings: AudioDirectionPreferences.Snapshot,
    ) {
        if (!settings.soundEffectsEnabled) {
            sfxController.stopAll()
            lastTriggeredSfxKey = ""
            return
        }
        val triggerKey = "$chapterId:$unitId"
        if (triggerKey == lastTriggeredSfxKey) return
        lastTriggeredSfxKey = triggerKey

        val allowedBoundedKeys = allowedBoundedSfxKeysByUnitId[unitId].orEmpty()
        boundedSfxCueKeys.asSequence()
            .filterNot(allowedBoundedKeys::contains)
            .forEach(sfxController::stopCue)

        val candidates = mutableListOf<RuntimeSfxCue>()

        allowedBoundedKeys.asSequence()
            .mapNotNull(sfxCueByKey::get)
            .filter { runtimeCue ->
                runtimeCue.cue.loopUntilStop &&
                    runtimeCue.cue.unitId != unitId &&
                    !sfxController.isCueActive(runtimeCue.key)
            }
            .forEach(candidates::add)

        sfxByUnitId[unitId].orEmpty().forEach { runtimeCue ->
            if (candidates.none { it.key == runtimeCue.key }) candidates += runtimeCue
        }
        if (candidates.isEmpty()) return
        if (mode3Active()) {
            diagnostic(
                "FREESOUND_RUNTIME_SFX_CANDIDATES",
                DiagnosticSeverity.INFO,
                mapOf(
                    "unitId" to unitId,
                    "candidateCount" to candidates.size.toString(),
                    "appLevelConcurrencyQuota" to "none",
                ),
            )
        }

        var startedAny = false
        candidates.forEach { runtimeCue ->
            if (sfxController.isCueActive(runtimeCue.key)) return@forEach
            val cue = runtimeCue.cue
            val asset = assetsById[cue.effectId]?.takeIf { it.kind == AudioAssetKind.SFX } ?: return@forEach
            val started = sfxController.play(
                asset = asset,
                masterVolume = 1f,
                maxConcurrent = Int.MAX_VALUE,
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
            if (started) startedAny = true
        }
        if (!startedAny) return


        ambienceController.duckForImportantSfx(SFX_DUCK_FACTOR, SFX_DUCK_HOLD_MS)
        SceneMusicSfxDuckBus.duck(SFX_DUCK_FACTOR, SFX_DUCK_HOLD_MS)
    }

    private fun buildAmbienceVariants(allAssets: List<AudioDirectionAsset>): Map<String, List<AudioDirectionAsset>> {
        val ambience = allAssets.filter { it.kind == AudioAssetKind.AMBIENCE }
        val groups = ambience.groupBy { AudioAssetVariantFamily.key(it.title) }
        return buildMap {
            ambience.forEach { asset ->
                val key = AudioAssetVariantFamily.key(asset.title)
                val family = groups[key].orEmpty()
                put(asset.id, if (family.size > 1) family else listOf(asset))
            }
        }
    }

    private fun markFailure(signature: String) {
        failedSignature = signature
        failedAtMillis = System.currentTimeMillis()
    }

    private fun clearFailure() {
        failedSignature = ""
        failedAtMillis = 0L
    }

    private fun clearPreparedPlan() {
        preparedChapterId = ""
        preparedSignature = ""
        clearFailure()
        assetsById = emptyMap()
        ambienceVariantsById = emptyMap()
        ambienceByUnitId = emptyMap()
        sfxByUnitId = emptyMap()
        sfxCueByKey = emptyMap()
        boundedSfxCueKeys = emptySet()
        allowedBoundedSfxKeysByUnitId = emptyMap()
        lastTriggeredSfxKey = ""
        validatedFastKey = ""
        validatedFastAtMillis = 0L
        cachedParagraphChapterId = ""
        cachedParagraphIdentity = 0
        cachedParagraphCount = -1
        cachedParagraphHash = ""
        lastAmbienceTraceState = ""
    }

    companion object {
        const val KIND_AUDIO_DIRECTION = NarrationPlanCoordinator.KIND_AUDIO_DIRECTION
        private const val FAILURE_RETRY_COOLDOWN_MS = 60_000L
        private const val PLAN_REVALIDATE_INTERVAL_MS = 5_000L
        private const val MAX_EFFECT_HISTORY = 64
        private const val SFX_DUCK_FACTOR = 0.72f
        private const val SFX_DUCK_HOLD_MS = 650L
    }
}
