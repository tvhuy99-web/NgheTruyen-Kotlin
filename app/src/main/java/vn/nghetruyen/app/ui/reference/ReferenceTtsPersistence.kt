package vn.nghetruyen.app.ui.reference

import android.content.Context
import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.audio.ReferenceSonicRuntime
import vn.nghetruyen.app.core.model.VoiceRoleDraft
import vn.nghetruyen.app.data.local.StoryTtsProfileEntity

/** Draft used by the XPK-compatible TTS editor. */
data class ReferenceTtsDraft(
    val enginePackage: String? = null,
    val voiceName: String? = null,
    val languageTag: String = "vi-VN",
    val processingMethod: String = "system",
    val sonicAccurate: Boolean = false,
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val volume: Float = 1f,
)

object ReferenceTtsPersistence {
    private const val PREFS = "reference_tts_profile_extra"
    private const val DEFAULT_METHOD = "default_method"
    private const val DEFAULT_QUALITY = "default_quality"

    suspend fun load(context: Context, storyId: String, hasStoryProfile: Boolean): ReferenceTtsDraft {
        val app = context.applicationContext as NgheTruyenApplication
        val settings = app.container.settingsRepository.snapshot()
        val profile = if (hasStoryProfile && storyId.isNotBlank()) {
            app.container.database.storyTtsProfileDao().get(storyId)
        } else null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val method = if (profile != null) {
            prefs.getString(methodKey(storyId), null)
                ?: if (settings.sonicProcessingEnabled) "sonic" else "system"
        } else {
            prefs.getString(DEFAULT_METHOD, null)
                ?: if (settings.sonicProcessingEnabled) "sonic" else "system"
        }
        val quality = if (profile != null) {
            prefs.getInt(qualityKey(storyId), if (settings.sonicAccurateMode) 1 else 0)
        } else {
            prefs.getInt(DEFAULT_QUALITY, if (settings.sonicAccurateMode) 1 else 0)
        }
        return ReferenceTtsDraft(
            enginePackage = profile?.enginePackage ?: settings.ttsEnginePackage,
            voiceName = profile?.voiceName ?: settings.ttsVoiceName,
            languageTag = profile?.languageTag ?: settings.ttsLanguageTag,
            processingMethod = if (method == "sonic") "sonic" else "system",
            sonicAccurate = quality == 1,
            speed = (profile?.rate ?: settings.ttsRate).coerceIn(0.25f, 3f),
            pitch = (profile?.pitch ?: settings.ttsPitch).coerceIn(0.5f, 2f),
            volume = (profile?.volume ?: settings.ttsVolume).coerceIn(0f, 2f),
        )
    }

    suspend fun save(
        context: Context,
        storyId: String,
        draft: ReferenceTtsDraft,
        useStoryProfile: Boolean,
    ) {
        val app = context.applicationContext as NgheTruyenApplication
        val settings = app.container.settingsRepository
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val normalized = normalize(draft)
        if (useStoryProfile && storyId.isNotBlank()) {
            app.container.database.storyTtsProfileDao().upsert(
                StoryTtsProfileEntity(
                    storyId = storyId,
                    enginePackage = normalized.enginePackage,
                    voiceName = normalized.voiceName,
                    languageTag = normalized.languageTag,
                    rate = normalized.speed,
                    pitch = normalized.pitch,
                    volume = normalized.volume,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            prefs.edit()
                .putString(methodKey(storyId), normalized.processingMethod)
                .putInt(qualityKey(storyId), if (normalized.sonicAccurate) 1 else 0)
                .apply()
            // Apply the story override to the active reader session. It is restored on reader exit.
            settings.setSonicProcessingEnabled(normalized.processingMethod == "sonic")
            settings.setSonicAccurateMode(normalized.sonicAccurate)
        } else {
            if (storyId.isNotBlank()) {
                app.container.database.storyTtsProfileDao().delete(storyId)
                prefs.edit().remove(methodKey(storyId)).remove(qualityKey(storyId)).apply()
            }
            settings.setTtsEngine(normalized.enginePackage)
            settings.setTtsVoice(normalized.voiceName, normalized.languageTag)
            settings.setTtsRate(normalized.speed)
            settings.setTtsPitch(normalized.pitch)
            settings.setTtsVolume(normalized.volume)
            settings.setSonicProcessingEnabled(normalized.processingMethod == "sonic")
            settings.setSonicAccurateMode(normalized.sonicAccurate)
            prefs.edit()
                .putString(DEFAULT_METHOD, normalized.processingMethod)
                .putInt(DEFAULT_QUALITY, if (normalized.sonicAccurate) 1 else 0)
                .apply()
        }
        applyRuntime(normalized)
    }

    suspend fun activateStoryOverride(context: Context, storyId: String, hasStoryProfile: Boolean) {
        val app = context.applicationContext as NgheTruyenApplication
        val settings = app.container.settingsRepository
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (hasStoryProfile && storyId.isNotBlank() && prefs.contains(methodKey(storyId))) {
            val method = prefs.getString(methodKey(storyId), "system") ?: "system"
            val quality = prefs.getInt(qualityKey(storyId), 0) == 1
            settings.setSonicProcessingEnabled(method == "sonic")
            settings.setSonicAccurateMode(quality)
            val profile = app.container.database.storyTtsProfileDao().get(storyId)
            if (profile != null) {
                ReferenceSonicRuntime.accurateMode = quality
                ReferenceSonicRuntime.outputGain = if (method == "sonic") profile.volume.coerceIn(0f, 2f) else 1f
            }
        } else {
            restoreGlobal(context)
        }
    }

    suspend fun restoreGlobal(context: Context) {
        val app = context.applicationContext as NgheTruyenApplication
        val settings = app.container.settingsRepository
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val snapshot = settings.snapshot()
        val method = prefs.getString(DEFAULT_METHOD, null)
            ?: if (snapshot.sonicProcessingEnabled) "sonic" else "system"
        val quality = prefs.getInt(DEFAULT_QUALITY, if (snapshot.sonicAccurateMode) 1 else 0) == 1
        settings.setSonicProcessingEnabled(method == "sonic")
        settings.setSonicAccurateMode(quality)
        ReferenceSonicRuntime.accurateMode = quality
        ReferenceSonicRuntime.outputGain = if (method == "sonic") snapshot.ttsVolume.coerceIn(0f, 2f) else 1f
    }

    fun previewDraft(draft: ReferenceTtsDraft): VoiceRoleDraft {
        val normalized = normalize(draft)
        val sonic = normalized.processingMethod == "sonic"
        val baseRate = if (sonic) normalized.speed.coerceIn(0.5f, 2f) else normalized.speed
        val sonicSpeed = if (sonic) (normalized.speed / baseRate).coerceIn(0.5f, 2f) else 1f
        ReferenceSonicRuntime.accurateMode = normalized.sonicAccurate
        ReferenceSonicRuntime.outputGain = if (sonic) normalized.volume else 1f
        return VoiceRoleDraft(
            roleName = "Người kể chuyện",
            isNarrator = true,
            enginePackage = normalized.enginePackage,
            voiceName = normalized.voiceName,
            languageTag = normalized.languageTag,
            rate = baseRate,
            pitch = normalized.pitch,
            volume = normalized.volume.coerceAtMost(1f),
            sonicSpeed = sonicSpeed,
            sonicPitch = 1f,
        )
    }

    private fun applyRuntime(draft: ReferenceTtsDraft) {
        ReferenceSonicRuntime.accurateMode = draft.sonicAccurate
        ReferenceSonicRuntime.outputGain = if (draft.processingMethod == "sonic") draft.volume.coerceIn(0f, 2f) else 1f
    }

    private fun normalize(value: ReferenceTtsDraft): ReferenceTtsDraft {
        val method = if (value.processingMethod == "sonic") "sonic" else "system"
        val maxVolume = if (method == "sonic") 2f else 1f
        return value.copy(
            languageTag = value.languageTag.ifBlank { "vi-VN" },
            processingMethod = method,
            speed = value.speed.coerceIn(0.25f, 3f),
            pitch = value.pitch.coerceIn(0.5f, 2f),
            volume = value.volume.coerceIn(0f, maxVolume),
        )
    }

    private fun methodKey(storyId: String) = "story:$storyId:method"
    private fun qualityKey(storyId: String) = "story:$storyId:quality"
}
