package vn.nghetruyen.app.audio

import android.content.Context

/**
 * Mutually exclusive story-audio source modes. The default deliberately matches the pre-existing
 * AI/local-library behaviour so installing an update never opts a user into Freesound automation.
 */
enum class StoryAudioSourceMode(
    val label: String,
    val description: String,
) {
    LOCAL_MANUAL(
        label = "1. Thư viện trong máy (thủ công)",
        description = "Chỉ dùng âm thanh trong máy; không để AI tự chọn nhạc, môi trường hoặc SFX.",
    ),
    AI_LOCAL(
        label = "2. AI chọn từ thư viện trong máy",
        description = "Giữ nguyên cơ chế hiện tại: AI chọn MUSIC, AMBIENCE và SFX từ thư viện local đang bật.",
    ),
    AI_FREESOUND(
        label = "3. AI tự động tìm trên Freesound",
        description = "Cùng lượt AI phân vai xác định âm thanh cần dùng; ứng dụng tự tìm Freesound, tải, chuẩn hóa rồi phát từ file local.",
    ),
}

class StoryAudioSourceModeStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun get(): StoryAudioSourceMode = runCatching {
        StoryAudioSourceMode.valueOf(
            preferences.getString(KEY_MODE, StoryAudioSourceMode.AI_LOCAL.name)
                ?: StoryAudioSourceMode.AI_LOCAL.name,
        )
    }.getOrDefault(StoryAudioSourceMode.AI_LOCAL)

    fun set(mode: StoryAudioSourceMode) {
        preferences.edit().putString(KEY_MODE, mode.name).apply()
    }

    companion object {
        private const val PREFERENCES = "story_audio_source_mode_v1"
        private const val KEY_MODE = "mode"
    }
}

object StoryAudioModeRouter {
    fun usesManualLocal(mode: StoryAudioSourceMode): Boolean = mode == StoryAudioSourceMode.LOCAL_MANUAL
    fun usesAiLocal(mode: StoryAudioSourceMode): Boolean = mode == StoryAudioSourceMode.AI_LOCAL
    fun usesAiFreesound(mode: StoryAudioSourceMode): Boolean = mode == StoryAudioSourceMode.AI_FREESOUND

    /** Voice-cast AI is independent from the story-audio source mode. */
    fun shouldUseLocalAudioCatalogs(mode: StoryAudioSourceMode): Boolean = usesAiLocal(mode)

    fun shouldRequestFreesoundRequirements(mode: StoryAudioSourceMode): Boolean = usesAiFreesound(mode)
}
