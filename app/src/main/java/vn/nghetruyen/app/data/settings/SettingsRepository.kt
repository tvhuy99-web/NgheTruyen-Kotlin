package vn.nghetruyen.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import vn.nghetruyen.app.core.model.AudioInterruptionMode
import vn.nghetruyen.app.core.model.ReaderDisplaySettings
import vn.nghetruyen.app.core.model.ReaderLayoutMode
import vn.nghetruyen.app.core.model.ReaderThemeMode
import vn.nghetruyen.app.core.model.SceneMusicPlaybackMode

private val Context.dataStore by preferencesDataStore(name = "nghe_truyen_settings")


enum class AiProvider { OPENAI_COMPATIBLE, GEMINI }

data class AiOnlineSettings(
    val provider: AiProvider = AiProvider.OPENAI_COMPATIBLE,
    val enabled: Boolean = false,
    val consentGranted: Boolean = false,
    val endpoint: String = "https://api.openai.com/v1/chat/completions",
    val model: String = "",
    val temperature: Float = 0.2f,
    val translationInstruction: String = "",
    val dailyRequestLimit: Int = 30,
    val dailyInputCharsLimit: Int = 500_000,
    val maxRetries: Int = 2,
    val retryBaseDelayMillis: Int = 1_500,
)

data class AppSettings(
    val selectedSourceId: String = "truyenfull",
    val ttsRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val ttsVolume: Float = 1.0f,
    val ttsEnginePackage: String? = null,
    val autoPlayNextChapter: Boolean = true,
    val aiTranslationEnabled: Boolean = false,
    val ttsVoiceName: String? = null,
    val ttsLanguageTag: String = "vi-VN",
    val audioInterruptionMode: AudioInterruptionMode = AudioInterruptionMode.PAUSE,
    val backgroundMusicUri: String? = null,
    val backgroundMusicEnabled: Boolean = false,
    val backgroundMusicVolume: Float = 0.18f,
    val backgroundMusicDuckFactor: Float = 0.25f,
    val followingUpdatesEnabled: Boolean = false,
    val readerCacheLimitMiB: Int = 64,
    val readerDisplay: ReaderDisplaySettings = ReaderDisplaySettings(),
    val headsetMultiClickEnabled: Boolean = true,
    val headsetSingleClickAction: String = "TOGGLE",
    val headsetDoubleClickAction: String = "NEXT",
    val headsetTripleClickAction: String = "PREVIOUS",
    val headsetLongPressAction: String = "STOP",
    val pauseOnHeadsetDisconnect: Boolean = true,
    val restorePlaybackAfterProcessDeath: Boolean = true,
    val autoVoiceCastEnabled: Boolean = false,
    val autoSceneMusicEnabled: Boolean = false,
    val prefetchNarrationPlansEnabled: Boolean = true,
    val narrationPrefetchWindowChapters: Int = 2,
    val sceneMusicCrossfadeMillis: Int = 1_600,
    val sceneMusicContinueAcrossChapters: Boolean = true,
    val sceneMusicPlaybackMode: SceneMusicPlaybackMode = SceneMusicPlaybackMode.SMART_AVOID_REPEAT,
    val sceneMusicTargetLufs: Float = -18.0f,
    val sceneMusicAvoidRepeatWindow: Int = 4,
    val sonicProcessingEnabled: Boolean = true,
    val sonicDefaultSpeed: Float = 1.0f,
    val sonicDefaultPitch: Float = 1.0f,
    val ttsCacheEnabled: Boolean = true,
    val ttsCacheLimitMiB: Int = 64,
    val normalizeTtsVolumeEnabled: Boolean = true,
    val ttsTargetLufs: Float = -18.0f,
    val aiOnline: AiOnlineSettings = AiOnlineSettings(),
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val source = stringPreferencesKey("selected_source")
        val ttsRate = floatPreferencesKey("tts_rate")
        val ttsPitch = floatPreferencesKey("tts_pitch")
        val ttsVolume = floatPreferencesKey("tts_volume")
        val ttsEngine = stringPreferencesKey("tts_engine_package")
        val autoNext = booleanPreferencesKey("auto_next")
        val aiTranslation = booleanPreferencesKey("ai_translation")
        val ttsVoice = stringPreferencesKey("tts_voice_name")
        val ttsLanguage = stringPreferencesKey("tts_language_tag")
        val audioInterruption = stringPreferencesKey("audio_interruption_mode")
        val backgroundMusicUri = stringPreferencesKey("background_music_uri")
        val backgroundMusicEnabled = booleanPreferencesKey("background_music_enabled")
        val backgroundMusicVolume = floatPreferencesKey("background_music_volume")
        val backgroundMusicDuckFactor = floatPreferencesKey("background_music_duck_factor")
        val followingUpdates = booleanPreferencesKey("following_updates")
        val readerCacheLimitMiB = intPreferencesKey("reader_cache_limit_mib")
        val readerTheme = stringPreferencesKey("reader_theme")
        val readerLayoutMode = stringPreferencesKey("reader_layout_mode")
        val readerFontSize = intPreferencesKey("reader_font_size_sp")
        val readerLineHeight = intPreferencesKey("reader_line_height_percent")
        val readerHorizontalPadding = intPreferencesKey("reader_horizontal_padding_dp")
        val readerParagraphSpacing = intPreferencesKey("reader_paragraph_spacing_dp")
        val readerKeepScreenOn = booleanPreferencesKey("reader_keep_screen_on")
        val readerVolumeKeysNavigate = booleanPreferencesKey("reader_volume_keys_navigate")
        val headsetMultiClickEnabled = booleanPreferencesKey("headset_multi_click_enabled")
        val headsetSingleClickAction = stringPreferencesKey("headset_single_click_action")
        val headsetDoubleClickAction = stringPreferencesKey("headset_double_click_action")
        val headsetTripleClickAction = stringPreferencesKey("headset_triple_click_action")
        val headsetLongPressAction = stringPreferencesKey("headset_long_press_action")
        val pauseOnHeadsetDisconnect = booleanPreferencesKey("pause_on_headset_disconnect")
        val restorePlaybackAfterProcessDeath = booleanPreferencesKey("restore_playback_after_process_death")
        val autoVoiceCastEnabled = booleanPreferencesKey("auto_voice_cast_enabled")
        val autoSceneMusicEnabled = booleanPreferencesKey("auto_scene_music_enabled")
        val prefetchNarrationPlansEnabled = booleanPreferencesKey("prefetch_narration_plans_enabled")
        val narrationPrefetchWindowChapters = intPreferencesKey("narration_prefetch_window_chapters")
        val sceneMusicCrossfadeMillis = intPreferencesKey("scene_music_crossfade_millis")
        val sceneMusicContinueAcrossChapters = booleanPreferencesKey("scene_music_continue_across_chapters")
        val sceneMusicPlaybackMode = stringPreferencesKey("scene_music_playback_mode")
        val sceneMusicTargetLufs = floatPreferencesKey("scene_music_target_lufs")
        val sceneMusicAvoidRepeatWindow = intPreferencesKey("scene_music_avoid_repeat_window")
        val sonicProcessingEnabled = booleanPreferencesKey("sonic_processing_enabled")
        val sonicDefaultSpeed = floatPreferencesKey("sonic_default_speed")
        val sonicDefaultPitch = floatPreferencesKey("sonic_default_pitch")
        val ttsCacheEnabled = booleanPreferencesKey("tts_cache_enabled")
        val ttsCacheLimitMiB = intPreferencesKey("tts_cache_limit_mib")
        val normalizeTtsVolumeEnabled = booleanPreferencesKey("normalize_tts_volume_enabled")
        val ttsTargetLufs = floatPreferencesKey("tts_target_lufs")

        val aiOnlineEnabled = booleanPreferencesKey("ai_online_enabled")
        val aiProvider = stringPreferencesKey("ai_provider")
        val aiConsent = booleanPreferencesKey("ai_consent_granted")
        val aiEndpoint = stringPreferencesKey("ai_endpoint")
        val aiModel = stringPreferencesKey("ai_model") // legacy effective model
        val aiOpenAiModel = stringPreferencesKey("ai_model_openai_compatible")
        val aiGeminiModel = stringPreferencesKey("ai_model_gemini")
        val aiTemperature = floatPreferencesKey("ai_temperature")
        val aiTranslationInstruction = stringPreferencesKey("ai_translation_instruction")
        val aiDailyRequestLimit = intPreferencesKey("ai_daily_request_limit")
        val aiDailyInputCharsLimit = intPreferencesKey("ai_daily_input_chars_limit")
        val aiMaxRetries = intPreferencesKey("ai_max_retries")
        val aiRetryBaseDelayMillis = intPreferencesKey("ai_retry_base_delay_millis")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val aiProvider = runCatching {
            AiProvider.valueOf(prefs[Keys.aiProvider] ?: AiProvider.OPENAI_COMPATIBLE.name)
        }.getOrDefault(AiProvider.OPENAI_COMPATIBLE)
        val legacyModel = prefs[Keys.aiModel].orEmpty().trim()
        val aiModel = when (aiProvider) {
            AiProvider.GEMINI -> prefs[Keys.aiGeminiModel]
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: legacyModel.takeIf { it.startsWith("gemini-", ignoreCase = true) }
                ?: DEFAULT_GEMINI_MODEL
            AiProvider.OPENAI_COMPATIBLE -> prefs[Keys.aiOpenAiModel]
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: legacyModel.takeUnless { it.startsWith("gemini-", ignoreCase = true) }
                .orEmpty()
        }
        AppSettings(
            selectedSourceId = prefs[Keys.source] ?: "truyenfull",
            ttsRate = normalizeRate(prefs[Keys.ttsRate] ?: 1.0f),
            ttsPitch = normalizePitch(prefs[Keys.ttsPitch] ?: 1.0f),
            ttsVolume = normalizeVolume(prefs[Keys.ttsVolume] ?: 1.0f),
            ttsEnginePackage = prefs[Keys.ttsEngine]?.takeIf(String::isNotBlank),
            autoPlayNextChapter = prefs[Keys.autoNext] ?: true,
            aiTranslationEnabled = prefs[Keys.aiTranslation] ?: false,
            ttsVoiceName = prefs[Keys.ttsVoice]?.takeIf(String::isNotBlank),
            ttsLanguageTag = prefs[Keys.ttsLanguage]?.takeIf(String::isNotBlank) ?: "vi-VN",
            audioInterruptionMode = runCatching {
                AudioInterruptionMode.valueOf(prefs[Keys.audioInterruption] ?: AudioInterruptionMode.PAUSE.name)
            }.getOrDefault(AudioInterruptionMode.PAUSE),
            backgroundMusicUri = prefs[Keys.backgroundMusicUri]?.takeIf(String::isNotBlank),
            backgroundMusicEnabled = prefs[Keys.backgroundMusicEnabled] ?: false,
            backgroundMusicVolume = normalizeMusicVolume(prefs[Keys.backgroundMusicVolume] ?: 0.18f),
            backgroundMusicDuckFactor = normalizeDuckFactor(prefs[Keys.backgroundMusicDuckFactor] ?: 0.25f),
            followingUpdatesEnabled = prefs[Keys.followingUpdates] ?: false,
            readerCacheLimitMiB = normalizeCacheLimit(prefs[Keys.readerCacheLimitMiB] ?: 64),
            readerDisplay = ReaderDisplaySettings(
                theme = runCatching { ReaderThemeMode.valueOf(prefs[Keys.readerTheme] ?: "SYSTEM") }
                    .getOrDefault(ReaderThemeMode.SYSTEM),
                layoutMode = runCatching { ReaderLayoutMode.valueOf(prefs[Keys.readerLayoutMode] ?: "SCROLL") }
                    .getOrDefault(ReaderLayoutMode.SCROLL),
                fontSizeSp = normalizeFontSize(prefs[Keys.readerFontSize] ?: 20),
                lineHeightPercent = normalizeLineHeight(prefs[Keys.readerLineHeight] ?: 155),
                horizontalPaddingDp = normalizeHorizontalPadding(prefs[Keys.readerHorizontalPadding] ?: 12),
                paragraphSpacingDp = normalizeParagraphSpacing(prefs[Keys.readerParagraphSpacing] ?: 8),
                keepScreenOn = prefs[Keys.readerKeepScreenOn] ?: false,
                volumeKeysNavigate = prefs[Keys.readerVolumeKeysNavigate] ?: false,
            ),
            headsetMultiClickEnabled = prefs[Keys.headsetMultiClickEnabled] ?: true,
            headsetSingleClickAction = normalizeMediaAction(prefs[Keys.headsetSingleClickAction], "TOGGLE"),
            headsetDoubleClickAction = normalizeMediaAction(prefs[Keys.headsetDoubleClickAction], "NEXT"),
            headsetTripleClickAction = normalizeMediaAction(prefs[Keys.headsetTripleClickAction], "PREVIOUS"),
            headsetLongPressAction = normalizeMediaAction(prefs[Keys.headsetLongPressAction], "STOP"),
            pauseOnHeadsetDisconnect = prefs[Keys.pauseOnHeadsetDisconnect] ?: true,
            restorePlaybackAfterProcessDeath = prefs[Keys.restorePlaybackAfterProcessDeath] ?: true,
            autoVoiceCastEnabled = prefs[Keys.autoVoiceCastEnabled] ?: false,
            autoSceneMusicEnabled = prefs[Keys.autoSceneMusicEnabled] ?: false,
            prefetchNarrationPlansEnabled = prefs[Keys.prefetchNarrationPlansEnabled] ?: true,
            narrationPrefetchWindowChapters = normalizePrefetchWindow(prefs[Keys.narrationPrefetchWindowChapters] ?: 2),
            sceneMusicCrossfadeMillis = normalizeCrossfadeMillis(prefs[Keys.sceneMusicCrossfadeMillis] ?: 1_600),
            sceneMusicContinueAcrossChapters = prefs[Keys.sceneMusicContinueAcrossChapters] ?: true,
            sceneMusicPlaybackMode = runCatching {
                SceneMusicPlaybackMode.valueOf(prefs[Keys.sceneMusicPlaybackMode] ?: SceneMusicPlaybackMode.SMART_AVOID_REPEAT.name)
            }.getOrDefault(SceneMusicPlaybackMode.SMART_AVOID_REPEAT),
            sceneMusicTargetLufs = normalizeTargetLufs(prefs[Keys.sceneMusicTargetLufs] ?: -18.0f),
            sceneMusicAvoidRepeatWindow = normalizeRepeatWindow(prefs[Keys.sceneMusicAvoidRepeatWindow] ?: 4),
            sonicProcessingEnabled = prefs[Keys.sonicProcessingEnabled] ?: true,
            sonicDefaultSpeed = normalizeSonic(prefs[Keys.sonicDefaultSpeed] ?: 1.0f),
            sonicDefaultPitch = normalizeSonic(prefs[Keys.sonicDefaultPitch] ?: 1.0f),
            ttsCacheEnabled = prefs[Keys.ttsCacheEnabled] ?: true,
            ttsCacheLimitMiB = normalizeTtsCacheLimit(prefs[Keys.ttsCacheLimitMiB] ?: 64),
            normalizeTtsVolumeEnabled = prefs[Keys.normalizeTtsVolumeEnabled] ?: true,
            ttsTargetLufs = normalizeTtsTargetLufs(prefs[Keys.ttsTargetLufs] ?: -18.0f),

            aiOnline = AiOnlineSettings(
                provider = aiProvider,
                enabled = prefs[Keys.aiOnlineEnabled] ?: false,
                consentGranted = prefs[Keys.aiConsent] ?: false,
                endpoint = prefs[Keys.aiEndpoint]?.takeIf(String::isNotBlank)
                    ?: "https://api.openai.com/v1/chat/completions",
                model = aiModel,
                temperature = (prefs[Keys.aiTemperature] ?: 0.2f).coerceIn(0f, 1f),
                translationInstruction = prefs[Keys.aiTranslationInstruction].orEmpty().take(2000),
                dailyRequestLimit = normalizeAiRequestLimit(prefs[Keys.aiDailyRequestLimit] ?: 30),
                dailyInputCharsLimit = normalizeAiCharLimit(prefs[Keys.aiDailyInputCharsLimit] ?: 500_000),
                maxRetries = normalizeAiRetries(prefs[Keys.aiMaxRetries] ?: 2),
                retryBaseDelayMillis = normalizeAiBackoff(prefs[Keys.aiRetryBaseDelayMillis] ?: 1_500),
            ),
        )
    }

    suspend fun snapshot(): AppSettings = settings.first()
    suspend fun selectSource(id: String) { context.dataStore.edit { it[Keys.source] = id } }
    suspend fun setTtsRate(value: Float) { context.dataStore.edit { it[Keys.ttsRate] = normalizeRate(value) } }
    suspend fun setTtsPitch(value: Float) { context.dataStore.edit { it[Keys.ttsPitch] = normalizePitch(value) } }
    suspend fun setTtsVolume(value: Float) { context.dataStore.edit { it[Keys.ttsVolume] = normalizeVolume(value) } }
    suspend fun setTtsEngine(packageName: String?) {
        context.dataStore.edit { prefs ->
            if (packageName.isNullOrBlank()) prefs.remove(Keys.ttsEngine) else prefs[Keys.ttsEngine] = packageName
            prefs.remove(Keys.ttsVoice)
        }
    }
    suspend fun setAutoPlayNextChapter(enabled: Boolean) { context.dataStore.edit { it[Keys.autoNext] = enabled } }
    suspend fun setTtsVoice(name: String?, languageTag: String) {
        context.dataStore.edit { prefs ->
            if (name.isNullOrBlank()) prefs.remove(Keys.ttsVoice) else prefs[Keys.ttsVoice] = name
            prefs[Keys.ttsLanguage] = languageTag.ifBlank { "vi-VN" }
        }
    }
    suspend fun setAudioInterruptionMode(mode: AudioInterruptionMode) {
        context.dataStore.edit { it[Keys.audioInterruption] = mode.name }
    }
    suspend fun setBackgroundMusic(uri: String?) {
        context.dataStore.edit { prefs ->
            if (uri.isNullOrBlank()) {
                prefs.remove(Keys.backgroundMusicUri)
                prefs[Keys.backgroundMusicEnabled] = false
            } else {
                prefs[Keys.backgroundMusicUri] = uri
            }
        }
    }
    suspend fun setBackgroundMusicEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.backgroundMusicEnabled] = enabled }
    }
    suspend fun setBackgroundMusicVolume(value: Float) {
        context.dataStore.edit { it[Keys.backgroundMusicVolume] = normalizeMusicVolume(value) }
    }
    suspend fun setBackgroundMusicDuckFactor(value: Float) {
        context.dataStore.edit { it[Keys.backgroundMusicDuckFactor] = normalizeDuckFactor(value) }
    }
    suspend fun setFollowingUpdatesEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.followingUpdates] = enabled }
    }
    suspend fun setReaderCacheLimitMiB(value: Int) {
        context.dataStore.edit { it[Keys.readerCacheLimitMiB] = normalizeCacheLimit(value) }
    }
    suspend fun setReaderTheme(value: ReaderThemeMode) {
        context.dataStore.edit { it[Keys.readerTheme] = value.name }
    }
    suspend fun setReaderLayoutMode(value: ReaderLayoutMode) {
        context.dataStore.edit { it[Keys.readerLayoutMode] = value.name }
    }
    suspend fun setReaderFontSizeSp(value: Int) {
        context.dataStore.edit { it[Keys.readerFontSize] = normalizeFontSize(value) }
    }
    suspend fun setReaderLineHeightPercent(value: Int) {
        context.dataStore.edit { it[Keys.readerLineHeight] = normalizeLineHeight(value) }
    }
    suspend fun setReaderHorizontalPaddingDp(value: Int) {
        context.dataStore.edit { it[Keys.readerHorizontalPadding] = normalizeHorizontalPadding(value) }
    }
    suspend fun setReaderParagraphSpacingDp(value: Int) {
        context.dataStore.edit { it[Keys.readerParagraphSpacing] = normalizeParagraphSpacing(value) }
    }
    suspend fun setReaderKeepScreenOn(enabled: Boolean) {
        context.dataStore.edit { it[Keys.readerKeepScreenOn] = enabled }
    }
    suspend fun setReaderVolumeKeysNavigate(enabled: Boolean) {
        context.dataStore.edit { it[Keys.readerVolumeKeysNavigate] = enabled }
    }
    suspend fun setHeadsetMultiClickEnabled(enabled: Boolean) { context.dataStore.edit { it[Keys.headsetMultiClickEnabled] = enabled } }
    suspend fun setHeadsetSingleClickAction(value: String) { context.dataStore.edit { it[Keys.headsetSingleClickAction] = normalizeMediaAction(value, "TOGGLE") } }
    suspend fun setHeadsetDoubleClickAction(value: String) { context.dataStore.edit { it[Keys.headsetDoubleClickAction] = normalizeMediaAction(value, "NEXT") } }
    suspend fun setHeadsetTripleClickAction(value: String) { context.dataStore.edit { it[Keys.headsetTripleClickAction] = normalizeMediaAction(value, "PREVIOUS") } }
    suspend fun setHeadsetLongPressAction(value: String) { context.dataStore.edit { it[Keys.headsetLongPressAction] = normalizeMediaAction(value, "STOP") } }
    suspend fun setPauseOnHeadsetDisconnect(enabled: Boolean) { context.dataStore.edit { it[Keys.pauseOnHeadsetDisconnect] = enabled } }
    suspend fun setRestorePlaybackAfterProcessDeath(enabled: Boolean) { context.dataStore.edit { it[Keys.restorePlaybackAfterProcessDeath] = enabled } }
    suspend fun setAutoVoiceCastEnabled(enabled: Boolean) { context.dataStore.edit { it[Keys.autoVoiceCastEnabled] = enabled } }
    suspend fun setAutoSceneMusicEnabled(enabled: Boolean) { context.dataStore.edit { it[Keys.autoSceneMusicEnabled] = enabled } }
    suspend fun setPrefetchNarrationPlansEnabled(enabled: Boolean) { context.dataStore.edit { it[Keys.prefetchNarrationPlansEnabled] = enabled } }
    suspend fun setNarrationPrefetchWindowChapters(value: Int) { context.dataStore.edit { it[Keys.narrationPrefetchWindowChapters] = normalizePrefetchWindow(value) } }
    suspend fun setSceneMusicCrossfadeMillis(value: Int) { context.dataStore.edit { it[Keys.sceneMusicCrossfadeMillis] = normalizeCrossfadeMillis(value) } }
    suspend fun setSceneMusicContinueAcrossChapters(enabled: Boolean) { context.dataStore.edit { it[Keys.sceneMusicContinueAcrossChapters] = enabled } }
    suspend fun setSceneMusicPlaybackMode(value: SceneMusicPlaybackMode) { context.dataStore.edit { it[Keys.sceneMusicPlaybackMode] = value.name } }
    suspend fun setSceneMusicTargetLufs(value: Float) { context.dataStore.edit { it[Keys.sceneMusicTargetLufs] = normalizeTargetLufs(value) } }
    suspend fun setSceneMusicAvoidRepeatWindow(value: Int) { context.dataStore.edit { it[Keys.sceneMusicAvoidRepeatWindow] = normalizeRepeatWindow(value) } }
    suspend fun setSonicProcessingEnabled(enabled: Boolean) { context.dataStore.edit { it[Keys.sonicProcessingEnabled] = enabled } }
    suspend fun setSonicDefaultSpeed(value: Float) { context.dataStore.edit { it[Keys.sonicDefaultSpeed] = normalizeSonic(value) } }
    suspend fun setSonicDefaultPitch(value: Float) { context.dataStore.edit { it[Keys.sonicDefaultPitch] = normalizeSonic(value) } }
    suspend fun setTtsCacheEnabled(enabled: Boolean) { context.dataStore.edit { it[Keys.ttsCacheEnabled] = enabled } }
    suspend fun setTtsCacheLimitMiB(value: Int) { context.dataStore.edit { it[Keys.ttsCacheLimitMiB] = normalizeTtsCacheLimit(value) } }
    suspend fun setNormalizeTtsVolumeEnabled(enabled: Boolean) { context.dataStore.edit { it[Keys.normalizeTtsVolumeEnabled] = enabled } }
    suspend fun setTtsTargetLufs(value: Float) { context.dataStore.edit { it[Keys.ttsTargetLufs] = normalizeTtsTargetLufs(value) } }

    suspend fun setAiProvider(provider: AiProvider) {
        context.dataStore.edit { prefs -> prefs[Keys.aiProvider] = provider.name }
    }
    suspend fun setAiOnlineEnabled(enabled: Boolean) { context.dataStore.edit { it[Keys.aiOnlineEnabled] = enabled } }
    suspend fun setAiConsent(granted: Boolean) { context.dataStore.edit { it[Keys.aiConsent] = granted } }
    suspend fun setAiEndpoint(value: String) { context.dataStore.edit { it[Keys.aiEndpoint] = value.trim().take(500) } }
    suspend fun setAiModel(value: String) {
        context.dataStore.edit { prefs ->
            val normalized = value.trim().take(200)
            val provider = runCatching {
                AiProvider.valueOf(prefs[Keys.aiProvider] ?: AiProvider.OPENAI_COMPATIBLE.name)
            }.getOrDefault(AiProvider.OPENAI_COMPATIBLE)
            when (provider) {
                AiProvider.GEMINI -> prefs[Keys.aiGeminiModel] = normalized.ifBlank { DEFAULT_GEMINI_MODEL }
                AiProvider.OPENAI_COMPATIBLE -> prefs[Keys.aiOpenAiModel] = normalized
            }
            prefs[Keys.aiModel] = normalized
        }
    }
    suspend fun setAiTemperature(value: Float) { context.dataStore.edit { it[Keys.aiTemperature] = value.coerceIn(0f, 1f) } }
    suspend fun setAiTranslationInstruction(value: String) { context.dataStore.edit { it[Keys.aiTranslationInstruction] = value.trim().take(2000) } }
    suspend fun setAiDailyRequestLimit(value: Int) { context.dataStore.edit { it[Keys.aiDailyRequestLimit] = normalizeAiRequestLimit(value) } }
    suspend fun setAiDailyInputCharsLimit(value: Int) { context.dataStore.edit { it[Keys.aiDailyInputCharsLimit] = normalizeAiCharLimit(value) } }
    suspend fun setAiMaxRetries(value: Int) { context.dataStore.edit { it[Keys.aiMaxRetries] = normalizeAiRetries(value) } }
    suspend fun setAiRetryBaseDelayMillis(value: Int) { context.dataStore.edit { it[Keys.aiRetryBaseDelayMillis] = normalizeAiBackoff(value) } }

    suspend fun restore(settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.source] = settings.selectedSourceId
            prefs[Keys.ttsRate] = normalizeRate(settings.ttsRate)
            prefs[Keys.ttsPitch] = normalizePitch(settings.ttsPitch)
            prefs[Keys.ttsVolume] = normalizeVolume(settings.ttsVolume)
            if (settings.ttsEnginePackage.isNullOrBlank()) prefs.remove(Keys.ttsEngine)
            else prefs[Keys.ttsEngine] = settings.ttsEnginePackage
            prefs[Keys.autoNext] = settings.autoPlayNextChapter
            prefs[Keys.aiTranslation] = settings.aiTranslationEnabled
            if (settings.ttsVoiceName.isNullOrBlank()) prefs.remove(Keys.ttsVoice)
            else prefs[Keys.ttsVoice] = settings.ttsVoiceName
            prefs[Keys.ttsLanguage] = settings.ttsLanguageTag.ifBlank { "vi-VN" }
            prefs[Keys.audioInterruption] = settings.audioInterruptionMode.name
            if (settings.backgroundMusicUri.isNullOrBlank()) prefs.remove(Keys.backgroundMusicUri)
            else prefs[Keys.backgroundMusicUri] = settings.backgroundMusicUri
            prefs[Keys.backgroundMusicEnabled] = settings.backgroundMusicEnabled && !settings.backgroundMusicUri.isNullOrBlank()
            prefs[Keys.backgroundMusicVolume] = normalizeMusicVolume(settings.backgroundMusicVolume)
            prefs[Keys.backgroundMusicDuckFactor] = normalizeDuckFactor(settings.backgroundMusicDuckFactor)
            prefs[Keys.followingUpdates] = settings.followingUpdatesEnabled
            prefs[Keys.readerCacheLimitMiB] = normalizeCacheLimit(settings.readerCacheLimitMiB)
            prefs[Keys.readerTheme] = settings.readerDisplay.theme.name
            prefs[Keys.readerLayoutMode] = settings.readerDisplay.layoutMode.name
            prefs[Keys.readerFontSize] = normalizeFontSize(settings.readerDisplay.fontSizeSp)
            prefs[Keys.readerLineHeight] = normalizeLineHeight(settings.readerDisplay.lineHeightPercent)
            prefs[Keys.readerHorizontalPadding] = normalizeHorizontalPadding(settings.readerDisplay.horizontalPaddingDp)
            prefs[Keys.readerParagraphSpacing] = normalizeParagraphSpacing(settings.readerDisplay.paragraphSpacingDp)
            prefs[Keys.readerKeepScreenOn] = settings.readerDisplay.keepScreenOn
            prefs[Keys.readerVolumeKeysNavigate] = settings.readerDisplay.volumeKeysNavigate
            prefs[Keys.headsetMultiClickEnabled] = settings.headsetMultiClickEnabled
            prefs[Keys.headsetSingleClickAction] = normalizeMediaAction(settings.headsetSingleClickAction, "TOGGLE")
            prefs[Keys.headsetDoubleClickAction] = normalizeMediaAction(settings.headsetDoubleClickAction, "NEXT")
            prefs[Keys.headsetTripleClickAction] = normalizeMediaAction(settings.headsetTripleClickAction, "PREVIOUS")
            prefs[Keys.headsetLongPressAction] = normalizeMediaAction(settings.headsetLongPressAction, "STOP")
            prefs[Keys.pauseOnHeadsetDisconnect] = settings.pauseOnHeadsetDisconnect
            prefs[Keys.restorePlaybackAfterProcessDeath] = settings.restorePlaybackAfterProcessDeath
            prefs[Keys.autoVoiceCastEnabled] = settings.autoVoiceCastEnabled
            prefs[Keys.autoSceneMusicEnabled] = settings.autoSceneMusicEnabled
            prefs[Keys.prefetchNarrationPlansEnabled] = settings.prefetchNarrationPlansEnabled
            prefs[Keys.narrationPrefetchWindowChapters] = normalizePrefetchWindow(settings.narrationPrefetchWindowChapters)
            prefs[Keys.sceneMusicCrossfadeMillis] = normalizeCrossfadeMillis(settings.sceneMusicCrossfadeMillis)
            prefs[Keys.sceneMusicContinueAcrossChapters] = settings.sceneMusicContinueAcrossChapters
            prefs[Keys.sceneMusicPlaybackMode] = settings.sceneMusicPlaybackMode.name
            prefs[Keys.sceneMusicTargetLufs] = normalizeTargetLufs(settings.sceneMusicTargetLufs)
            prefs[Keys.sceneMusicAvoidRepeatWindow] = normalizeRepeatWindow(settings.sceneMusicAvoidRepeatWindow)
            prefs[Keys.sonicProcessingEnabled] = settings.sonicProcessingEnabled
            prefs[Keys.sonicDefaultSpeed] = normalizeSonic(settings.sonicDefaultSpeed)
            prefs[Keys.sonicDefaultPitch] = normalizeSonic(settings.sonicDefaultPitch)
            prefs[Keys.ttsCacheEnabled] = settings.ttsCacheEnabled
            prefs[Keys.ttsCacheLimitMiB] = normalizeTtsCacheLimit(settings.ttsCacheLimitMiB)
            prefs[Keys.normalizeTtsVolumeEnabled] = settings.normalizeTtsVolumeEnabled
            prefs[Keys.ttsTargetLufs] = normalizeTtsTargetLufs(settings.ttsTargetLufs)

            prefs[Keys.aiProvider] = settings.aiOnline.provider.name
            prefs[Keys.aiOnlineEnabled] = settings.aiOnline.enabled
            prefs[Keys.aiConsent] = settings.aiOnline.consentGranted
            prefs[Keys.aiEndpoint] = settings.aiOnline.endpoint.trim().take(500)
            val restoredModel = settings.aiOnline.model.trim().take(200)
            when (settings.aiOnline.provider) {
                AiProvider.GEMINI -> prefs[Keys.aiGeminiModel] = restoredModel.ifBlank { DEFAULT_GEMINI_MODEL }
                AiProvider.OPENAI_COMPATIBLE -> prefs[Keys.aiOpenAiModel] = restoredModel
            }
            prefs[Keys.aiModel] = restoredModel
            prefs[Keys.aiTemperature] = settings.aiOnline.temperature.coerceIn(0f, 1f)
            prefs[Keys.aiTranslationInstruction] = settings.aiOnline.translationInstruction.trim().take(2000)
            prefs[Keys.aiDailyRequestLimit] = normalizeAiRequestLimit(settings.aiOnline.dailyRequestLimit)
            prefs[Keys.aiDailyInputCharsLimit] = normalizeAiCharLimit(settings.aiOnline.dailyInputCharsLimit)
            prefs[Keys.aiMaxRetries] = normalizeAiRetries(settings.aiOnline.maxRetries)
            prefs[Keys.aiRetryBaseDelayMillis] = normalizeAiBackoff(settings.aiOnline.retryBaseDelayMillis)
        }
    }

    companion object {
        const val DEFAULT_GEMINI_MODEL = "gemini-3.6-flash"
        val CACHE_LIMIT_OPTIONS_MIB = listOf(16, 32, 64, 128, 256)

        fun normalizeCacheLimit(value: Int): Int = CACHE_LIMIT_OPTIONS_MIB.minBy { option ->
            kotlin.math.abs(option - value)
        }
        fun normalizeRate(value: Float): Float = value.coerceIn(0.5f, 2.0f)
        fun normalizePitch(value: Float): Float = value.coerceIn(0.5f, 2.0f)
        fun normalizeVolume(value: Float): Float = value.coerceIn(0.05f, 1.0f)
        fun normalizeMusicVolume(value: Float): Float = value.coerceIn(0.0f, 0.6f)
        fun normalizeDuckFactor(value: Float): Float = value.coerceIn(0.05f, 1.0f)
        fun normalizeFontSize(value: Int): Int = value.coerceIn(14, 36)
        fun normalizeLineHeight(value: Int): Int = value.coerceIn(110, 220)
        fun normalizeHorizontalPadding(value: Int): Int = value.coerceIn(4, 40)
        fun normalizeParagraphSpacing(value: Int): Int = value.coerceIn(0, 32)
        fun normalizeCrossfadeMillis(value: Int): Int = value.coerceIn(0, 8_000)
        fun normalizePrefetchWindow(value: Int): Int = value.coerceIn(1, 5)
        fun normalizeTargetLufs(value: Float): Float = value.coerceIn(-30f, -10f)
        fun normalizeRepeatWindow(value: Int): Int = value.coerceIn(0, 12)
        fun normalizeSonic(value: Float): Float = value.coerceIn(0.5f, 2.0f)
        fun normalizeTtsCacheLimit(value: Int): Int = listOf(16, 32, 64, 128, 256, 512).minBy { kotlin.math.abs(it - value) }
        fun normalizeTtsTargetLufs(value: Float): Float = value.coerceIn(-30f, -10f)
        fun normalizeMediaAction(value: String?, fallback: String): String {
            val allowed = setOf("PLAY", "PAUSE", "TOGGLE", "NEXT", "PREVIOUS", "FORWARD", "REWIND", "STOP")
            return value?.trim()?.uppercase()?.takeIf(allowed::contains) ?: fallback
        }
        fun normalizeAiRequestLimit(value: Int): Int = value.coerceIn(1, 500)
        fun normalizeAiCharLimit(value: Int): Int = value.coerceIn(10_000, 5_000_000)
        fun normalizeAiRetries(value: Int): Int = value.coerceIn(0, 5)
        fun normalizeAiBackoff(value: Int): Int = value.coerceIn(250, 30_000)
    }
}
