package vn.nghetruyen.app.audio

import android.content.Context

/**
 * Mutually exclusive story-audio source modes. The default deliberately matches the pre-existing
 * AI/local-library behaviour so installing an update never opts a user into Freesound automation.
 *
 * The physical asset library is shared by Mode 2 and Mode 3 for MUSIC, AMBIENCE and SFX. Source
 * provenance (user-added vs Freesound-downloaded) is still preserved for management/diagnostics,
 * but it does not make an otherwise suitable enabled asset ineligible for AI selection.
 */
enum class StoryAudioSourceMode(
    val label: String,
    val description: String,
) {
    LOCAL_MANUAL(
        label = "1. Thư viện trong máy (thủ công)",
        description = "Chỉ dùng âm thanh trong máy theo cách thủ công; AI không tự chọn MUSIC, AMBIENCE hoặc SFX và không tải Freesound.",
    ),
    AI_LOCAL(
        label = "2. AI chọn từ toàn bộ thư viện trong máy",
        description = "AI chọn MUSIC, AMBIENCE và SFX từ mọi asset đang bật, gồm file tự thêm và file Freesound đã tải trước đó; không tìm hoặc tải mới từ mạng.",
    ),
    AI_FREESOUND(
        label = "3. AI tự động — thư viện + Freesound",
        description = "AI ưu tiên MUSIC, AMBIENCE và SFX phù hợp đã có trong toàn bộ thư viện; chỉ tìm, tải và chuẩn hóa Freesound khi thư viện chưa có asset đủ phù hợp.",
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

    /** Legacy sequential/shuffle/plain-background playback belongs exclusively to Mode 1. */
    fun allowsManualPlaylist(mode: StoryAudioSourceMode): Boolean = usesManualLocal(mode)

    /**
     * Only Mode 2 sends the full local catalog to the AI prompt. Mode 3 intentionally keeps its
     * compact semantic-requirement contract and lets the resolver satisfy those requirements from
     * the shared library before it is allowed to use Freesound network search.
     */
    fun shouldUseLocalAudioCatalogs(mode: StoryAudioSourceMode): Boolean = usesAiLocal(mode)

    fun shouldRequestFreesoundRequirements(mode: StoryAudioSourceMode): Boolean = usesAiFreesound(mode)

    /**
     * These are AI-request contracts, not physical-library isolation rules. Mode 2 receives the
     * catalog directly; Mode 3 requests semantic needs only and resolves them library-first.
     */
    fun isValidAiAudioContract(
        mode: StoryAudioSourceMode,
        hasLocalAudioCatalog: Boolean,
        requestsFreesoundRequirements: Boolean,
    ): Boolean = when (mode) {
        StoryAudioSourceMode.LOCAL_MANUAL -> !hasLocalAudioCatalog && !requestsFreesoundRequirements
        StoryAudioSourceMode.AI_LOCAL -> !requestsFreesoundRequirements
        StoryAudioSourceMode.AI_FREESOUND -> !hasLocalAudioCatalog && requestsFreesoundRequirements
    }
}
