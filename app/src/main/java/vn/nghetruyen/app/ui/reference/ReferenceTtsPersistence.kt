package vn.nghetruyen.app.ui.reference

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.abs
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
    /** Android/system TTS volume. */
    val volume: Float = 1f,
    /** Sonic gain controlled by the same visible volume slider when Sonic is selected. */
    val sonicVolume: Float = 1f,
)

object ReferenceTtsPersistence {
    private const val PREFS = "reference_tts_profile_extra"
    private const val DEFAULT_METHOD = "default_method"
    private const val DEFAULT_QUALITY = "default_quality"
    private const val DEFAULT_SPEED = "default_sonic_speed"
    private const val DEFAULT_PITCH = "default_sonic_pitch"
    private const val DEFAULT_SYSTEM_VOLUME = "default_system_volume"
    private const val DEFAULT_SONIC_VOLUME = "default_sonic_volume"

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
        val sonic = method == "sonic"
        val speed = when {
            profile != null && sonic -> prefs.getFloat(speedKey(storyId), profile.rate).coerceIn(0.25f, 3f)
            profile != null -> profile.rate.coerceIn(0.25f, 3f)
            sonic -> prefs.getFloat(DEFAULT_SPEED, settings.sonicDefaultSpeed).coerceIn(0.25f, 3f)
            else -> settings.ttsRate.coerceIn(0.25f, 3f)
        }
        val pitch = when {
            profile != null && sonic -> prefs.getFloat(pitchKey(storyId), profile.pitch).coerceIn(0.5f, 2f)
            profile != null -> profile.pitch.coerceIn(0.5f, 2f)
            sonic -> prefs.getFloat(DEFAULT_PITCH, settings.sonicDefaultPitch).coerceIn(0.5f, 2f)
            else -> settings.ttsPitch.coerceIn(0.5f, 2f)
        }
        val systemVolume = if (profile != null) {
            prefs.getFloat(
                systemVolumeKey(storyId),
                if (!sonic) profile.volume else prefs.getFloat(DEFAULT_SYSTEM_VOLUME, settings.ttsVolume.coerceAtMost(1f)),
            )
        } else {
            prefs.getFloat(
                DEFAULT_SYSTEM_VOLUME,
                if (!sonic) settings.ttsVolume else 1f,
            )
        }.coerceIn(0f, 1f)
        val sonicVolume = if (profile != null) {
            prefs.getFloat(
                sonicVolumeKey(storyId),
                if (sonic) profile.volume else prefs.getFloat(DEFAULT_SONIC_VOLUME, 1f),
            )
        } else {
            prefs.getFloat(
                DEFAULT_SONIC_VOLUME,
                if (sonic) settings.ttsVolume else 1f,
            )
        }.coerceIn(0f, 2f)
        return ReferenceTtsDraft(
            enginePackage = profile?.enginePackage ?: settings.ttsEnginePackage,
            voiceName = profile?.voiceName ?: settings.ttsVoiceName,
            languageTag = profile?.languageTag ?: settings.ttsLanguageTag,
            processingMethod = if (sonic) "sonic" else "system",
            sonicAccurate = quality == 1,
            speed = speed,
            pitch = pitch,
            volume = systemVolume,
            sonicVolume = sonicVolume,
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
        val sonic = normalized.processingMethod == "sonic"
        val activeVolume = activeVolume(normalized)
        if (useStoryProfile && storyId.isNotBlank()) {
            val beforeOverride = settings.snapshot()
            preserveGlobalSonicDefaults(
                prefs,
                beforeOverride.sonicDefaultSpeed,
                beforeOverride.sonicDefaultPitch,
            )
            app.container.database.storyTtsProfileDao().upsert(
                StoryTtsProfileEntity(
                    storyId = storyId,
                    enginePackage = normalized.enginePackage,
                    voiceName = normalized.voiceName,
                    languageTag = normalized.languageTag,
                    // Android TTS remains neutral while the visible controls feed Sonic.
                    rate = if (sonic) 1f else normalized.speed,
                    pitch = if (sonic) 1f else normalized.pitch,
                    volume = activeVolume,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            val storyEditor = prefs.edit()
                .putString(methodKey(storyId), normalized.processingMethod)
                .putInt(qualityKey(storyId), if (normalized.sonicAccurate) 1 else 0)
                .putFloat(systemVolumeKey(storyId), normalized.volume)
                .putFloat(sonicVolumeKey(storyId), normalized.sonicVolume)
            if (sonic) {
                storyEditor
                    .putFloat(speedKey(storyId), normalized.speed)
                    .putFloat(pitchKey(storyId), normalized.pitch)
            } else {
                storyEditor
                    .remove(speedKey(storyId))
                    .remove(pitchKey(storyId))
            }
            storyEditor.apply()
            settings.setSonicProcessingEnabled(sonic)
            settings.setSonicAccurateMode(normalized.sonicAccurate)
            if (sonic) {
                settings.setSonicDefaultSpeed(normalized.speed)
                settings.setSonicDefaultPitch(normalized.pitch)
            }
        } else {
            if (storyId.isNotBlank()) {
                app.container.database.storyTtsProfileDao().delete(storyId)
                prefs.edit()
                    .remove(methodKey(storyId))
                    .remove(qualityKey(storyId))
                    .remove(speedKey(storyId))
                    .remove(pitchKey(storyId))
                    .remove(systemVolumeKey(storyId))
                    .remove(sonicVolumeKey(storyId))
                    .apply()
            }
            val beforeGlobalSave = settings.snapshot()
            preserveGlobalSonicDefaults(
                prefs,
                beforeGlobalSave.sonicDefaultSpeed,
                beforeGlobalSave.sonicDefaultPitch,
            )
            settings.setTtsEngine(normalized.enginePackage)
            settings.setTtsVoice(normalized.voiceName, normalized.languageTag)
            settings.setTtsRate(if (sonic) 1f else normalized.speed)
            settings.setTtsPitch(if (sonic) 1f else normalized.pitch)
            settings.setTtsVolume(activeVolume)
            settings.setSonicProcessingEnabled(sonic)
            settings.setSonicAccurateMode(normalized.sonicAccurate)
            if (sonic) {
                settings.setSonicDefaultSpeed(normalized.speed)
                settings.setSonicDefaultPitch(normalized.pitch)
            }
            val editor = prefs.edit()
                .putString(DEFAULT_METHOD, normalized.processingMethod)
                .putInt(DEFAULT_QUALITY, if (normalized.sonicAccurate) 1 else 0)
                .putFloat(DEFAULT_SYSTEM_VOLUME, normalized.volume)
                .putFloat(DEFAULT_SONIC_VOLUME, normalized.sonicVolume)
            if (sonic) {
                editor
                    .putFloat(DEFAULT_SPEED, normalized.speed)
                    .putFloat(DEFAULT_PITCH, normalized.pitch)
            }
            editor.apply()
        }
        applyRuntime(normalized)
    }

    suspend fun activateStoryOverride(context: Context, storyId: String, hasStoryProfile: Boolean) {
        val app = context.applicationContext as NgheTruyenApplication
        val settings = app.container.settingsRepository
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (hasStoryProfile && storyId.isNotBlank() && prefs.contains(methodKey(storyId))) {
            val beforeOverride = settings.snapshot()
            preserveGlobalSonicDefaults(
                prefs,
                beforeOverride.sonicDefaultSpeed,
                beforeOverride.sonicDefaultPitch,
            )
            val method = prefs.getString(methodKey(storyId), "system") ?: "system"
            val quality = prefs.getInt(qualityKey(storyId), 0) == 1
            val sonic = method == "sonic"
            settings.setSonicProcessingEnabled(sonic)
            settings.setSonicAccurateMode(quality)
            val profile = app.container.database.storyTtsProfileDao().get(storyId)
            if (profile != null) {
                if (sonic) {
                    // Migrate older reference profiles that stored Sonic speed/pitch in
                    // Android rate/pitch, then keep Android TTS neutral.
                    val speed = prefs.getFloat(speedKey(storyId), profile.rate).coerceIn(0.25f, 3f)
                    val pitch = prefs.getFloat(pitchKey(storyId), profile.pitch).coerceIn(0.5f, 2f)
                    val sonicVolume = prefs.getFloat(sonicVolumeKey(storyId), profile.volume).coerceIn(0f, 2f)
                    val systemVolume = prefs.getFloat(
                        systemVolumeKey(storyId),
                        prefs.getFloat(DEFAULT_SYSTEM_VOLUME, beforeOverride.ttsVolume.coerceAtMost(1f)),
                    ).coerceIn(0f, 1f)
                    prefs.edit()
                        .putFloat(speedKey(storyId), speed)
                        .putFloat(pitchKey(storyId), pitch)
                        .putFloat(sonicVolumeKey(storyId), sonicVolume)
                        .putFloat(systemVolumeKey(storyId), systemVolume)
                        .apply()
                    settings.setSonicDefaultSpeed(speed)
                    settings.setSonicDefaultPitch(pitch)
                    if (abs(profile.rate - 1f) >= 0.005f || abs(profile.pitch - 1f) >= 0.005f) {
                        app.container.database.storyTtsProfileDao().upsert(
                            profile.copy(rate = 1f, pitch = 1f, volume = sonicVolume, updatedAt = System.currentTimeMillis()),
                        )
                    }
                    ReferenceSonicRuntime.outputGain = sonicVolume
                } else {
                    val systemVolume = prefs.getFloat(systemVolumeKey(storyId), profile.volume).coerceIn(0f, 1f)
                    val sonicVolume = prefs.getFloat(
                        sonicVolumeKey(storyId),
                        prefs.getFloat(DEFAULT_SONIC_VOLUME, 1f),
                    ).coerceIn(0f, 2f)
                    prefs.edit()
                        .putFloat(systemVolumeKey(storyId), systemVolume)
                        .putFloat(sonicVolumeKey(storyId), sonicVolume)
                        .apply()
                    ReferenceSonicRuntime.outputGain = 1f
                }
                ReferenceSonicRuntime.accurateMode = quality
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
        val sonic = method == "sonic"
        val systemVolume = prefs.getFloat(
            DEFAULT_SYSTEM_VOLUME,
            if (!sonic) snapshot.ttsVolume else 1f,
        ).coerceIn(0f, 1f)
        val sonicVolume = prefs.getFloat(
            DEFAULT_SONIC_VOLUME,
            if (sonic) snapshot.ttsVolume else 1f,
        ).coerceIn(0f, 2f)
        settings.setSonicProcessingEnabled(sonic)
        settings.setSonicAccurateMode(quality)
        settings.setTtsVolume(if (sonic) sonicVolume else systemVolume)
        if (prefs.contains(DEFAULT_SPEED)) {
            settings.setSonicDefaultSpeed(
                prefs.getFloat(DEFAULT_SPEED, 1f).coerceIn(0.25f, 3f),
            )
        }
        if (prefs.contains(DEFAULT_PITCH)) {
            settings.setSonicDefaultPitch(
                prefs.getFloat(DEFAULT_PITCH, 1f).coerceIn(0.5f, 2f),
            )
        }
        ReferenceSonicRuntime.accurateMode = quality
        ReferenceSonicRuntime.outputGain = if (sonic) sonicVolume else 1f
    }

    fun previewDraft(draft: ReferenceTtsDraft): VoiceRoleDraft {
        val normalized = normalize(draft)
        val sonic = normalized.processingMethod == "sonic"
        val activeVolume = activeVolume(normalized)
        ReferenceSonicRuntime.accurateMode = normalized.sonicAccurate
        ReferenceSonicRuntime.outputGain = if (sonic) normalized.sonicVolume else 1f
        return VoiceRoleDraft(
            roleName = "Người kể chuyện",
            isNarrator = true,
            enginePackage = normalized.enginePackage,
            voiceName = normalized.voiceName,
            languageTag = normalized.languageTag,
            rate = if (sonic) 1f else normalized.speed,
            pitch = if (sonic) 1f else normalized.pitch,
            volume = if (sonic) activeVolume.coerceAtMost(1f) else activeVolume,
            sonicVolume = normalized.sonicVolume,
            sonicSpeed = if (sonic) normalized.speed else 1f,
            sonicPitch = if (sonic) normalized.pitch else 1f,
            processingMethod = normalized.processingMethod,
            sonicAccurate = normalized.sonicAccurate,
        )
    }

    private fun applyRuntime(draft: ReferenceTtsDraft) {
        ReferenceSonicRuntime.accurateMode = draft.sonicAccurate
        ReferenceSonicRuntime.outputGain = if (draft.processingMethod == "sonic") draft.sonicVolume.coerceIn(0f, 2f) else 1f
    }

    private fun activeVolume(value: ReferenceTtsDraft): Float =
        if (value.processingMethod == "sonic") value.sonicVolume.coerceIn(0f, 2f)
        else value.volume.coerceIn(0f, 1f)

    private fun normalize(value: ReferenceTtsDraft): ReferenceTtsDraft {
        val method = if (value.processingMethod == "sonic") "sonic" else "system"
        return value.copy(
            languageTag = value.languageTag.ifBlank { "vi-VN" },
            processingMethod = method,
            speed = value.speed.coerceIn(0.25f, 3f),
            pitch = value.pitch.coerceIn(0.5f, 2f),
            volume = value.volume.coerceIn(0f, 1f),
            sonicVolume = value.sonicVolume.coerceIn(0f, 2f),
        )
    }

    private fun preserveGlobalSonicDefaults(prefs: SharedPreferences, speed: Float, pitch: Float) {
        val editor = prefs.edit()
        var changed = false
        if (!prefs.contains(DEFAULT_SPEED)) {
            editor.putFloat(DEFAULT_SPEED, speed.coerceIn(0.25f, 3f))
            changed = true
        }
        if (!prefs.contains(DEFAULT_PITCH)) {
            editor.putFloat(DEFAULT_PITCH, pitch.coerceIn(0.5f, 2f))
            changed = true
        }
        if (changed) editor.apply()
    }

    private fun methodKey(storyId: String) = "story:$storyId:method"
    private fun qualityKey(storyId: String) = "story:$storyId:quality"
    private fun speedKey(storyId: String) = "story:$storyId:sonic_speed"
    private fun pitchKey(storyId: String) = "story:$storyId:sonic_pitch"
    private fun systemVolumeKey(storyId: String) = "story:$storyId:system_volume"
    private fun sonicVolumeKey(storyId: String) = "story:$storyId:sonic_volume"
}
