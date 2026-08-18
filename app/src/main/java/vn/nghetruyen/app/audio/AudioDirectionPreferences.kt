package vn.nghetruyen.app.audio

import android.content.Context
import android.content.SharedPreferences

/**
 * Runtime switches for the AI sound-director layers that do not already have an application setting.
 *
 * Scene music keeps using AppSettings.autoSceneMusicEnabled so the existing switch remains the
 * authoritative music switch. Ambience and one-shot sound effects are deliberately opt-in and
 * default to OFF on every install/restore that has never written these keys.
 *
 * The application installs one process-wide [shared] instance during Application.onCreate. This
 * guarantees [currentSnapshot] is hydrated from disk before AI planning can read it after process
 * recreation, instead of temporarily exposing the in-memory default OFF values.
 */
class AudioDirectionPreferences(context: Context) {
    data class Snapshot(
        val ambienceEnabled: Boolean = false,
        val soundEffectsEnabled: Boolean = false,
        val musicMasterVolume: Float = DEFAULT_MUSIC_VOLUME,
        val ambienceMasterVolume: Float = DEFAULT_AMBIENCE_VOLUME,
        val soundEffectsMasterVolume: Float = DEFAULT_SFX_VOLUME,
        val ambienceNormalizationTargetLufs: Float = DEFAULT_AMBIENCE_NORMALIZATION_TARGET_LUFS,
        val soundEffectsNormalizationTargetLufs: Float = DEFAULT_SFX_NORMALIZATION_TARGET_LUFS,
        val ambienceCrossfadeMillis: Int = DEFAULT_AMBIENCE_CROSSFADE_MS,
        val ambienceLoopOverlapMinMillis: Int = DEFAULT_AMBIENCE_LOOP_OVERLAP_MIN_MS,
        val ambienceLoopOverlapMaxMillis: Int = DEFAULT_AMBIENCE_LOOP_OVERLAP_MAX_MS,
        val minimumSfxGapMillis: Long = DEFAULT_MIN_SFX_GAP_MS,
        val sameEffectCooldownMillis: Long = DEFAULT_SAME_EFFECT_COOLDOWN_MS,
        val maxConcurrentSfx: Int = DEFAULT_MAX_CONCURRENT_SFX,
    )

    private val preferences = context.applicationContext.getSharedPreferences(
        FILE_NAME,
        Context.MODE_PRIVATE,
    )

    init {
        snapshot()
    }

    fun snapshot(): Snapshot {
        val overlapMin = preferences.getInt(KEY_AMBIENCE_LOOP_OVERLAP_MIN_MS, DEFAULT_AMBIENCE_LOOP_OVERLAP_MIN_MS)
            .coerceIn(350, 3_000)
        val overlapMax = preferences.getInt(KEY_AMBIENCE_LOOP_OVERLAP_MAX_MS, DEFAULT_AMBIENCE_LOOP_OVERLAP_MAX_MS)
            .coerceIn(overlapMin, 4_000)
        val value = Snapshot(
            ambienceEnabled = preferences.getBoolean(KEY_AMBIENCE_ENABLED, false),
            soundEffectsEnabled = preferences.getBoolean(KEY_SFX_ENABLED, false),
            musicMasterVolume = preferences.getFloat(KEY_MUSIC_VOLUME, DEFAULT_MUSIC_VOLUME)
                .coerceIn(0f, 1f),
            ambienceMasterVolume = preferences.getFloat(KEY_AMBIENCE_VOLUME, DEFAULT_AMBIENCE_VOLUME)
                .coerceIn(0f, 1f),
            soundEffectsMasterVolume = preferences.getFloat(KEY_SFX_VOLUME, DEFAULT_SFX_VOLUME)
                .coerceIn(0f, 1f),
            ambienceNormalizationTargetLufs = preferences.getFloat(
                KEY_AMBIENCE_NORMALIZATION_TARGET_LUFS,
                DEFAULT_AMBIENCE_NORMALIZATION_TARGET_LUFS,
            ).coerceIn(PcmLoudnessEstimator.MIN_TARGET_LUFS, PcmLoudnessEstimator.MAX_TARGET_LUFS),
            soundEffectsNormalizationTargetLufs = preferences.getFloat(
                KEY_SFX_NORMALIZATION_TARGET_LUFS,
                DEFAULT_SFX_NORMALIZATION_TARGET_LUFS,
            ).coerceIn(PcmLoudnessEstimator.MIN_TARGET_LUFS, PcmLoudnessEstimator.MAX_TARGET_LUFS),
            ambienceCrossfadeMillis = preferences.getInt(KEY_AMBIENCE_CROSSFADE_MS, DEFAULT_AMBIENCE_CROSSFADE_MS)
                .coerceIn(500, 3_000),
            ambienceLoopOverlapMinMillis = overlapMin,
            ambienceLoopOverlapMaxMillis = overlapMax,
            minimumSfxGapMillis = preferences.getLong(KEY_MIN_SFX_GAP_MS, DEFAULT_MIN_SFX_GAP_MS)
                .coerceIn(500L, 15_000L),
            sameEffectCooldownMillis = preferences.getLong(KEY_SAME_EFFECT_COOLDOWN_MS, DEFAULT_SAME_EFFECT_COOLDOWN_MS)
                .coerceIn(1_000L, 30_000L),
            maxConcurrentSfx = preferences.getInt(KEY_MAX_CONCURRENT_SFX, DEFAULT_MAX_CONCURRENT_SFX)
                .coerceIn(1, 4),
        )
        latestSnapshot = value
        return value
    }

    fun setAmbienceEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_AMBIENCE_ENABLED, enabled).apply()
        snapshot()
    }

    fun setSoundEffectsEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_SFX_ENABLED, enabled).apply()
        snapshot()
    }

    fun setMusicMasterVolume(value: Float) {
        preferences.edit().putFloat(KEY_MUSIC_VOLUME, value.coerceIn(0f, 1f)).apply()
        snapshot()
    }

    fun setAmbienceMasterVolume(value: Float) {
        preferences.edit().putFloat(KEY_AMBIENCE_VOLUME, value.coerceIn(0f, 1f)).apply()
        snapshot()
    }

    fun setSoundEffectsMasterVolume(value: Float) {
        preferences.edit().putFloat(KEY_SFX_VOLUME, value.coerceIn(0f, 1f)).apply()
        snapshot()
    }

    fun setAmbienceNormalizationTargetLufs(value: Float) {
        preferences.edit().putFloat(
            KEY_AMBIENCE_NORMALIZATION_TARGET_LUFS,
            value.coerceIn(PcmLoudnessEstimator.MIN_TARGET_LUFS, PcmLoudnessEstimator.MAX_TARGET_LUFS),
        ).apply()
        snapshot()
    }

    fun setSoundEffectsNormalizationTargetLufs(value: Float) {
        preferences.edit().putFloat(
            KEY_SFX_NORMALIZATION_TARGET_LUFS,
            value.coerceIn(PcmLoudnessEstimator.MIN_TARGET_LUFS, PcmLoudnessEstimator.MAX_TARGET_LUFS),
        ).apply()
        snapshot()
    }

    fun setAmbienceCrossfadeMillis(value: Int) {
        preferences.edit().putInt(KEY_AMBIENCE_CROSSFADE_MS, value.coerceIn(500, 3_000)).apply()
        snapshot()
    }

    fun setAmbienceLoopOverlapRange(minMillis: Int, maxMillis: Int) {
        val safeMin = minMillis.coerceIn(350, 3_000)
        val safeMax = maxMillis.coerceIn(safeMin, 4_000)
        preferences.edit()
            .putInt(KEY_AMBIENCE_LOOP_OVERLAP_MIN_MS, safeMin)
            .putInt(KEY_AMBIENCE_LOOP_OVERLAP_MAX_MS, safeMax)
            .apply()
        snapshot()
    }

    fun setMinimumSfxGapMillis(value: Long) {
        preferences.edit().putLong(KEY_MIN_SFX_GAP_MS, value.coerceIn(500L, 15_000L)).apply()
        snapshot()
    }

    fun setSameEffectCooldownMillis(value: Long) {
        preferences.edit().putLong(KEY_SAME_EFFECT_COOLDOWN_MS, value.coerceIn(1_000L, 30_000L)).apply()
        snapshot()
    }

    fun setMaxConcurrentSfx(value: Int) {
        preferences.edit().putInt(KEY_MAX_CONCURRENT_SFX, value.coerceIn(1, 4)).apply()
        snapshot()
    }

    fun addChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun removeChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        private const val FILE_NAME = "ai_sound_director"
        private const val KEY_AMBIENCE_ENABLED = "ambience_enabled"
        private const val KEY_SFX_ENABLED = "sound_effects_enabled"
        private const val KEY_MUSIC_VOLUME = "music_master_volume"
        private const val KEY_AMBIENCE_VOLUME = "ambience_master_volume"
        private const val KEY_SFX_VOLUME = "sound_effects_master_volume"
        private const val KEY_AMBIENCE_NORMALIZATION_TARGET_LUFS = "ambience_normalization_target_lufs"
        private const val KEY_SFX_NORMALIZATION_TARGET_LUFS = "sfx_normalization_target_lufs"
        private const val KEY_AMBIENCE_CROSSFADE_MS = "ambience_crossfade_ms"
        private const val KEY_AMBIENCE_LOOP_OVERLAP_MIN_MS = "ambience_loop_overlap_min_ms"
        private const val KEY_AMBIENCE_LOOP_OVERLAP_MAX_MS = "ambience_loop_overlap_max_ms"
        private const val KEY_MIN_SFX_GAP_MS = "minimum_sfx_gap_ms"
        private const val KEY_SAME_EFFECT_COOLDOWN_MS = "same_effect_cooldown_ms"
        private const val KEY_MAX_CONCURRENT_SFX = "max_concurrent_sfx"

        @Volatile
        private var latestSnapshot: Snapshot = Snapshot()

        @Volatile
        private var sharedInstance: AudioDirectionPreferences? = null

        fun shared(context: Context): AudioDirectionPreferences =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: AudioDirectionPreferences(context.applicationContext).also {
                    sharedInstance = it
                }
            }

        fun currentSnapshot(): Snapshot = latestSnapshot

        const val DEFAULT_MUSIC_VOLUME = 1.0f
        const val DEFAULT_AMBIENCE_VOLUME = 0.63095734f
        const val DEFAULT_SFX_VOLUME = 0.63095734f
        const val DEFAULT_AMBIENCE_NORMALIZATION_TARGET_LUFS = -27f
        const val DEFAULT_SFX_NORMALIZATION_TARGET_LUFS = -20f
        const val DEFAULT_AMBIENCE_CROSSFADE_MS = 1_600
        const val DEFAULT_AMBIENCE_LOOP_OVERLAP_MIN_MS = 900
        const val DEFAULT_AMBIENCE_LOOP_OVERLAP_MAX_MS = 2_200
        const val DEFAULT_MIN_SFX_GAP_MS = 2_200L
        const val DEFAULT_SAME_EFFECT_COOLDOWN_MS = 6_000L
        const val DEFAULT_MAX_CONCURRENT_SFX = 2
    }
}
