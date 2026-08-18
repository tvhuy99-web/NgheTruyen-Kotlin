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
import vn.nghetruyen.app.ai.ChapterAiWorkflow
import vn.nghetruyen.app.ai.NarrationPlanCoordinator
import vn.nghetruyen.app.audio.AmbienceSfxPlan
import vn.nghetruyen.app.audio.AudioAssetClassifier
import vn.nghetruyen.app.audio.AudioAssetKind
import vn.nghetruyen.app.audio.AudioAssetVariantFamily
import vn.nghetruyen.app.audio.AudioDirectionAsset
import vn.nghetruyen.app.audio.AudioDirectionLimits
import vn.nghetruyen.app.audio.AudioDirectionPreferences
import vn.nghetruyen.app.audio.PcmLoudnessEstimator
import vn.nghetruyen.app.data.repository.LibraryRepository

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
    private val mutex = Mutex()
    private val ambienceController = SceneAmbienceController(context)
    private val sfxController = SceneSfxController(context)

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
    private var sfxByUnitId: Map<String, String> = emptyMap()
    private var lastTriggeredSfxKey = ""
    private var lastSfxAtMillis = 0L
    private val lastEffectAtMillis = linkedMapOf<String, Long>()
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
            if (forcePreferences) clearPreparedPlan()
            return
        }
        if (snapshot.chapterId.isBlank() || snapshot.speechChunks.none { it.unitId.isNotBlank() }) {
            ambienceController.stop()
            sfxController.stopAll()
            return
        }

        if (!snapshot.isPlaying) {
            ambienceController.pause()
            sfxController.stopAll()
            if (planningSwitchChanged) clearPreparedPlan()
            return
        }

        val prepared = ensurePlan(snapshot, settings, planningSwitchChanged)
        if (!prepared) {
            ambienceController.stop()
            sfxController.stopAll()
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
        val fastKey = buildFastKey(snapshot, settings)
        if (!force &&
            preparedChapterId == snapshot.chapterId &&
            preparedSignature.isNotBlank() &&
            validatedFastKey == fastKey &&
            now - validatedFastAtMillis < PLAN_REVALIDATE_INTERVAL_MS
        ) {
            return true
        }

        val rawTracks = libraryRepository.listEnabledSceneMusicTracks()
        val rawTracksById = rawTracks.associateBy { it.id }
        val allAssets = rawTracks.map { track ->
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
        }.filter { it.id.isNotBlank() && it.uri.isNotBlank() }
        val activeAudioAssets = allAssets.filter { asset ->
            (settings.ambienceEnabled && asset.kind == AudioAssetKind.AMBIENCE) ||
                (settings.soundEffectsEnabled && asset.kind == AudioAssetKind.SFX)
        }
        assetsById = allAssets.associateBy(AudioDirectionAsset::id)
        ambienceVariantsById = buildAmbienceVariants(allAssets)

        val validUnits = snapshot.speechChunks.map { it.unitId }.filter(String::isNotBlank)
        val signatureParts = ArrayList<String>(activeAudioAssets.size * 5 + 5)
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
        signatureParts += "ambience=${settings.ambienceEnabled}"
        signatureParts += "sfx=${settings.soundEffectsEnabled}"
        signatureParts += "timeline=${validUnits.joinToString(",")}"
        val signature = ChapterAiWorkflow.sha256(signatureParts)

        if (!force && preparedChapterId == snapshot.chapterId && preparedSignature == signature) {
            markFastValidation(fastKey, now)
            return true
        }
        if (!force && failedSignature == signature && now - failedAtMillis < FAILURE_RETRY_COOLDOWN_MS) {
            return false
        }

        if (activeAudioAssets.isEmpty()) {
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
        if (force || plan == null) {
            val outcome = runCatching {
                narrationPlanCoordinator.ensureActivePlans(content = content, force = force)
            }.getOrNull()
            if (outcome == null) {
                markFailure(signature)
                return false
            }
            plan = narrationPlanCoordinator.loadAudioDirectionPlan(content)
        }
        if (plan == null) {
            markFailure(signature)
            return false
        }

        clearFailure()
        installPlan(snapshot.chapterId, signature, validUnits, plan)
        markFastValidation(fastKey, now)
        return true
    }

    private fun buildFastKey(
        snapshot: PlaybackSnapshot,
        settings: AudioDirectionPreferences.Snapshot,
    ): String = buildString(96) {
        append(snapshot.chapterId)
        append('|').append(System.identityHashCode(snapshot.paragraphs))
        append(':').append(snapshot.paragraphs.size)
        append('|').append(System.identityHashCode(snapshot.speechChunks))
        append(':').append(snapshot.speechChunks.size)
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
                if (scene.ambienceId !in ids && ids.size < AudioDirectionLimits.MAX_CONCURRENT_AMBIENCE) {
                    ids += scene.ambienceId
                }
            }
        }
        ambienceByUnitId = ambienceMap.mapValues { (_, ids) -> ids.toList() }
        sfxByUnitId = newPlan.soundEffectCues.associate { it.unitId to it.effectId }
        preparedChapterId = chapterId
        preparedSignature = signature
        lastTriggeredSfxKey = ""
        lastSfxAtMillis = 0L
        lastEffectAtMillis.clear()
    }

    private fun applyAmbience(unitId: String, settings: AudioDirectionPreferences.Snapshot) {
        if (!settings.ambienceEnabled) {
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
            return
        }
        val effectId = sfxByUnitId[unitId] ?: return
        val triggerKey = "$chapterId:$unitId"
        if (triggerKey == lastTriggeredSfxKey) return
        lastTriggeredSfxKey = triggerKey

        val now = System.currentTimeMillis()
        if (now - lastSfxAtMillis < settings.minimumSfxGapMillis) return
        val sameEffectLast = lastEffectAtMillis[effectId] ?: 0L
        if (now - sameEffectLast < settings.sameEffectCooldownMillis) return

        val asset = assetsById[effectId]?.takeIf { it.kind == AudioAssetKind.SFX } ?: return
        lastSfxAtMillis = now
        lastEffectAtMillis[effectId] = now
        if (lastEffectAtMillis.size > MAX_EFFECT_HISTORY) {
            val cutoff = now - settings.sameEffectCooldownMillis * 2
            lastEffectAtMillis.entries.removeAll { it.value < cutoff }
        }

        // SFX cues are intentionally sparse and semantic; each accepted cue is therefore treated as
        // an important foreground event and briefly ducks the two background buses, never narration.
        ambienceController.duckForImportantSfx(SFX_DUCK_FACTOR, SFX_DUCK_HOLD_MS)
        SceneMusicSfxDuckBus.duck(SFX_DUCK_FACTOR, SFX_DUCK_HOLD_MS)
        sfxController.play(asset, 1f, settings.maxConcurrentSfx)
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
        lastTriggeredSfxKey = ""
        lastSfxAtMillis = 0L
        lastEffectAtMillis.clear()
        validatedFastKey = ""
        validatedFastAtMillis = 0L
        cachedParagraphChapterId = ""
        cachedParagraphIdentity = 0
        cachedParagraphCount = -1
        cachedParagraphHash = ""
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