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
import org.json.JSONArray
import org.json.JSONObject
import vn.nghetruyen.app.ai.AudioDirectionAiServices
import vn.nghetruyen.app.ai.ChapterAiWorkflow
import vn.nghetruyen.app.ai.XpkAmbienceSfxDirector
import vn.nghetruyen.app.ai.XpkSceneMusicParity
import vn.nghetruyen.app.audio.AmbienceSfxPlan
import vn.nghetruyen.app.audio.AudioAssetClassifier
import vn.nghetruyen.app.audio.AudioAssetKind
import vn.nghetruyen.app.audio.AudioDirectionAsset
import vn.nghetruyen.app.audio.AudioDirectionPreferences
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.data.local.ChapterTransformEntity
import vn.nghetruyen.app.data.repository.LibraryRepository
import java.util.UUID

/**
 * Sidecar audio runtime that follows the canonical PlaybackQueueStore UNIT timeline.
 *
 * It intentionally leaves existing SceneMusicController ownership untouched. The current scene-music
 * plan is supplied to AI as read-only context while this runtime owns only AMBIENCE and SFX. This
 * avoids regressions in the mature music crossfade/continuity path while adding the two new layers.
 */
class AudioDirectionRuntime(
    context: Context,
    private val libraryRepository: LibraryRepository,
    private val preferences: AudioDirectionPreferences,
    private val aiServices: AudioDirectionAiServices,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val ambienceController = SceneAmbienceController(context)
    private val sfxController = SceneSfxController(context)

    private var collectorJob: Job? = null
    private var preparedChapterId = ""
    private var preparedSourceHash = ""
    private var plan = AmbienceSfxPlan()
    private var assetsById: Map<String, AudioDirectionAsset> = emptyMap()
    private var ambienceByUnitId: Map<String, String> = emptyMap()
    private var sfxByUnitId: Map<String, String> = emptyMap()
    private var lastTriggeredSfxKey = ""
    private var lastSfxAtMillis = 0L

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
        val allAssets = rawTracks.map(AudioAssetClassifier::toAsset).filter { it.id.isNotBlank() && it.uri.isNotBlank() }
        val ambienceAssets = allAssets.filter { it.kind == AudioAssetKind.AMBIENCE }
        val sfxAssets = allAssets.filter { it.kind == AudioAssetKind.SFX }
        assetsById = allAssets.associateBy(AudioDirectionAsset::id)

        val validUnits = snapshot.speechChunks.map { it.unitId }.filter(String::isNotBlank)
        val sourceHash = ChapterAiWorkflow.sha256(
            snapshot.paragraphs +
                allAssets.flatMap { listOf(it.id, it.title, it.description, it.kind.name) } +
                listOf(
                    "ambience=${settings.ambienceEnabled}",
                    "sfx=${settings.soundEffectsEnabled}",
                    "timeline=${validUnits.joinToString(",")}",
                ),
        )
        if (!force && preparedChapterId == snapshot.chapterId && preparedSourceHash == sourceHash) return true

        val validAmbienceIds = ambienceAssets.map(AudioDirectionAsset::id).toSet()
        val validSfxIds = sfxAssets.map(AudioDirectionAsset::id).toSet()
        val cache = libraryRepository.getChapterTransform(snapshot.chapterId, KIND_AUDIO_DIRECTION)
        if (!force && cache?.sourceSha256 == sourceHash) {
            val cachedPlan = runCatching {
                XpkAmbienceSfxDirector.decodePersisted(
                    cache.transformedText,
                    validUnits,
                    validAmbienceIds,
                    validSfxIds,
                    settings.ambienceEnabled,
                    settings.soundEffectsEnabled,
                )
            }.getOrNull()
            if (cachedPlan != null) {
                installPlan(snapshot.chapterId, sourceHash, validUnits, cachedPlan)
                return true
            }
        }

        val needsAmbienceAi = settings.ambienceEnabled && ambienceAssets.isNotEmpty()
        val needsSfxAi = settings.soundEffectsEnabled && sfxAssets.isNotEmpty()
        if (!needsAmbienceAi && !needsSfxAi) {
            val empty = AmbienceSfxPlan()
            persistPlan(snapshot, sourceHash, empty, "local", "no-assets")
            installPlan(snapshot.chapterId, sourceHash, validUnits, empty)
            return true
        }

        val previous = libraryRepository.loadPreviousCachedChapter(snapshot.storyId, snapshot.chapterIndex)
        val previousTail = previous?.let {
            XpkSceneMusicParity.continuityTailForPrompt(
                title = it.chapter.title,
                body = it.paragraphs.joinToString("\n"),
                maxUnits = 5,
            )
        }.orEmpty()
        val musicContext = currentMusicScenesContext(snapshot.chapterId)
        val prompt = XpkAmbienceSfxDirector.buildPrompt(
            snapshot = snapshot,
            ambienceAssets = ambienceAssets,
            soundEffectAssets = sfxAssets,
            ambienceEnabled = needsAmbienceAi,
            soundEffectsEnabled = needsSfxAi,
            previousChapterTail = previousTail,
            musicScenesContext = musicContext,
            incomingAmbienceId = ambienceController.activeId(),
        )
        return when (val response = aiServices.direct(snapshot.storyId, prompt)) {
            is AppResult.Failure -> false
            is AppResult.Success -> {
                val parsed = runCatching {
                    XpkAmbienceSfxDirector.parseAndValidate(
                        response.value.content,
                        validUnits,
                        validAmbienceIds,
                        validSfxIds,
                        needsAmbienceAi,
                        needsSfxAi,
                    )
                }.getOrNull() ?: return false
                persistPlan(snapshot, sourceHash, parsed, response.value.provider, response.value.model)
                installPlan(snapshot.chapterId, sourceHash, validUnits, parsed)
                true
            }
        }
    }

    private suspend fun currentMusicScenesContext(chapterId: String): String {
        val transform = libraryRepository.getChapterTransform(chapterId, ChapterAiWorkflow.KIND_SCENE_MUSIC) ?: return "[]"
        return runCatching {
            val root = JSONObject(transform.transformedText)
            (root.optJSONArray("music_scenes") ?: JSONArray()).toString()
        }.getOrDefault("[]")
    }

    private suspend fun persistPlan(
        snapshot: PlaybackSnapshot,
        sourceHash: String,
        newPlan: AmbienceSfxPlan,
        provider: String,
        model: String,
    ) {
        libraryRepository.saveChapterTransform(
            ChapterTransformEntity(
                id = UUID.nameUUIDFromBytes("${snapshot.chapterId}\u0000$KIND_AUDIO_DIRECTION".toByteArray()).toString(),
                storyId = snapshot.storyId,
                chapterId = snapshot.chapterId,
                kind = KIND_AUDIO_DIRECTION,
                provider = provider,
                model = model,
                sourceSha256 = sourceHash,
                transformedText = XpkAmbienceSfxDirector.encode(newPlan),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun installPlan(
        chapterId: String,
        sourceHash: String,
        validUnits: List<String>,
        newPlan: AmbienceSfxPlan,
    ) {
        val order = validUnits.withIndex().associate { it.value to it.index }
        val ambienceMap = mutableMapOf<String, String>()
        newPlan.ambienceScenes.forEach { scene ->
            val start = order[scene.startUnitId] ?: return@forEach
            val end = order[scene.endUnitId] ?: return@forEach
            for (index in start..end) ambienceMap[validUnits[index]] = scene.ambienceId
        }
        plan = newPlan
        ambienceByUnitId = ambienceMap
        sfxByUnitId = newPlan.soundEffectCues.associate { it.unitId to it.effectId }
        preparedChapterId = chapterId
        preparedSourceHash = sourceHash
        lastTriggeredSfxKey = ""
    }

    private fun applyAmbience(unitId: String, settings: AudioDirectionPreferences.Snapshot) {
        if (!settings.ambienceEnabled) {
            ambienceController.stop()
            return
        }
        val ambienceId = ambienceByUnitId[unitId]
        val asset = ambienceId?.let(assetsById::get)
        if (asset == null || asset.kind != AudioAssetKind.AMBIENCE) {
            ambienceController.stop()
            return
        }
        ambienceController.play(asset, settings.ambienceMasterVolume)
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
        val asset = assetsById[effectId]?.takeIf { it.kind == AudioAssetKind.SFX } ?: return
        lastSfxAtMillis = now
        sfxController.play(asset, settings.soundEffectsMasterVolume, settings.maxConcurrentSfx)
    }

    private fun clearPreparedPlan() {
        preparedChapterId = ""
        preparedSourceHash = ""
        plan = AmbienceSfxPlan()
        assetsById = emptyMap()
        ambienceByUnitId = emptyMap()
        sfxByUnitId = emptyMap()
        lastTriggeredSfxKey = ""
    }

    companion object {
        const val KIND_AUDIO_DIRECTION = "AUDIO_DIRECTION"
    }
}
