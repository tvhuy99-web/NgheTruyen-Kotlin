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
import vn.nghetruyen.app.audio.AudioDirectionAsset
import vn.nghetruyen.app.audio.AudioDirectionLimits
import vn.nghetruyen.app.audio.AudioDirectionPreferences
import vn.nghetruyen.app.data.repository.LibraryRepository

/**
 * Playback consumer for the unified XPK chapter plan. This class never calls an AI provider itself:
 * when a plan is absent/stale it asks [NarrationPlanCoordinator] to prepare the whole active chapter
 * once, then consumes the persisted AMBIENCE/SFX portion on the canonical UNIT timeline.
 */
class AudioDirectionRuntime(
    context: Context,
    private val libraryRepository: LibraryRepository,
    private val preferences: AudioDirectionPreferences,
    private val narrationPlanCoordinator: NarrationPlanCoordinator,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val ambienceController = SceneAmbienceController(context)
    private val sfxController = SceneSfxController(context)

    private var collectorJob: Job? = null
    private var preparedChapterId = ""
    private var preparedSignature = ""
    private var failedSignature = ""
    private var failedAtMillis = 0L
    private var assetsById: Map<String, AudioDirectionAsset> = emptyMap()
    private var ambienceByUnitId: Map<String, List<String>> = emptyMap()
    private var sfxByUnitId: Map<String, String> = emptyMap()
    private var lastTriggeredSfxKey = ""
    private var lastSfxAtMillis = 0L
    private val lastEffectAtMillis = linkedMapOf<String, Long>()

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        scope.launch { mutex.withLock { handleSnapshot(PlaybackQueueStore.state.value, forcePreferences = true) } }
    }

    fun start() {
        if (collectorJob != null) return
        preferences.addChangeListener(preferenceListener)
        collectorJob = scope.launch {
            PlaybackQueueStore.state.collect { snapshot ->
                mutex.withLock { handleSnapshot(snapshot, forcePreferences = false) }
            }
        }
    }

    fun stop() {
        preferences.removeChangeListener(preferenceListener)
        collectorJob?.cancel()
        collectorJob = null
        ambienceController.stop()
        sfxController.stopAll()
        clearPreparedPlan()
    }

    private suspend fun handleSnapshot(snapshot: PlaybackSnapshot, forcePreferences: Boolean) {
        val settings = preferences.snapshot()
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

        val prepared = ensurePlan(snapshot, settings, forcePreferences)
        if (!prepared) {
            ambienceController.stop()
            sfxController.stopAll()
            return
        }

        if (!snapshot.isPlaying) {
            ambienceController.pause()
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
        val rawTracks = libraryRepository.listEnabledSceneMusicTracks()
        val allAssets = rawTracks.map(AudioAssetClassifier::toAsset)
            .filter { it.id.isNotBlank() && it.uri.isNotBlank() }
        val activeAudioAssets = allAssets.filter { asset ->
            (settings.ambienceEnabled && asset.kind == AudioAssetKind.AMBIENCE) ||
                (settings.soundEffectsEnabled && asset.kind == AudioAssetKind.SFX)
        }
        assetsById = allAssets.associateBy(AudioDirectionAsset::id)

        val validUnits = snapshot.speechChunks.map { it.unitId }.filter(String::isNotBlank)
        val signature = ChapterAiWorkflow.sha256(
            snapshot.paragraphs +
                activeAudioAssets.flatMap { asset ->
                    val row = rawTracks.firstOrNull { it.id == asset.id }
                    listOf(asset.id, asset.title, asset.description, asset.kind.name, row?.updatedAt?.toString().orEmpty())
                } +
                listOf(
                    "chapter=${snapshot.chapterId}",
                    "ambience=${settings.ambienceEnabled}",
                    "sfx=${settings.soundEffectsEnabled}",
                    "timeline=${validUnits.joinToString(",")}",
                ),
        )
        if (!force && preparedChapterId == snapshot.chapterId && preparedSignature == signature) return true
        if (!force && failedSignature == signature &&
            System.currentTimeMillis() - failedAtMillis < FAILURE_RETRY_COOLDOWN_MS
        ) return false

        if (activeAudioAssets.isEmpty()) {
            clearFailure()
            installPlan(snapshot.chapterId, signature, validUnits, AmbienceSfxPlan())
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
        return true
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
            masterVolume = settings.ambienceMasterVolume,
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
        sfxController.play(asset, settings.soundEffectsMasterVolume, settings.maxConcurrentSfx)
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
        ambienceByUnitId = emptyMap()
        sfxByUnitId = emptyMap()
        lastTriggeredSfxKey = ""
        lastSfxAtMillis = 0L
        lastEffectAtMillis.clear()
    }

    companion object {
        const val KIND_AUDIO_DIRECTION = NarrationPlanCoordinator.KIND_AUDIO_DIRECTION
        private const val FAILURE_RETRY_COOLDOWN_MS = 60_000L
        private const val MAX_EFFECT_HISTORY = 64
    }
}
