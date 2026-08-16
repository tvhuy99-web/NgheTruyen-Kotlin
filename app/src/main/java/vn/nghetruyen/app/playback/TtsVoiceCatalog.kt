package vn.nghetruyen.app.playback

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.TtsEngineOption
import vn.nghetruyen.app.core.model.TtsVoiceOption
import vn.nghetruyen.app.startup.StartupWorkGate
import java.util.Locale

class TtsVoiceCatalog(context: Context) {
    private val appContext = context.applicationContext

    suspend fun loadEngines(): AppResult<List<TtsEngineOption>> {
        if (StartupWorkGate.isBeforeFirstFrame()) return AppResult.Success(emptyList())
        return withEngine(null) { engine ->
            val defaultPackage = engine.defaultEngine
            engine.engines.orEmpty()
                .map { info ->
                    TtsEngineOption(
                        packageName = info.name,
                        label = info.label?.toString()?.ifBlank { info.name } ?: info.name,
                        isDefault = info.name == defaultPackage,
                    )
                }
                .distinctBy(TtsEngineOption::packageName)
                .sortedWith(compareByDescending<TtsEngineOption> { it.isDefault }.thenBy { it.label.lowercase() })
        }
    }

    suspend fun load(enginePackage: String? = null): AppResult<List<TtsVoiceOption>> {
        if (StartupWorkGate.isBeforeFirstFrame()) return AppResult.Success(emptyList())
        return withEngine(enginePackage) { engine ->
            engine.voices.orEmpty()
                .map { voice ->
                    val locale = voice.locale
                    TtsVoiceOption(
                        name = voice.name,
                        displayName = buildString {
                            append(locale.getDisplayName(Locale.forLanguageTag("vi-VN")).ifBlank { locale.toLanguageTag() })
                            append(" • ")
                            append(voice.name.substringAfterLast('.'))
                            if (voice.isNetworkConnectionRequired) append(" • mạng")
                        },
                        languageTag = locale.toLanguageTag().ifBlank { "und" },
                        networkRequired = voice.isNetworkConnectionRequired,
                        quality = voice.quality,
                        enginePackage = enginePackage ?: engine.defaultEngine,
                    )
                }
                .distinctBy(TtsVoiceOption::name)
                .sortedWith(
                    compareByDescending<TtsVoiceOption> { it.languageTag.startsWith("vi", ignoreCase = true) }
                        .thenBy { it.networkRequired }
                        .thenByDescending { it.quality }
                        .thenBy { it.displayName },
                )
        }
    }

    private suspend fun <T> withEngine(
        enginePackage: String?,
        block: (TextToSpeech) -> T,
    ): AppResult<T> = withContext(Dispatchers.Main.immediate) {
        val initialized = CompletableDeferred<Int>()
        val engine = if (enginePackage.isNullOrBlank()) {
            TextToSpeech(appContext) { status -> if (!initialized.isCompleted) initialized.complete(status) }
        } else {
            TextToSpeech(appContext, { status -> if (!initialized.isCompleted) initialized.complete(status) }, enginePackage)
        }
        try {
            val status = withTimeout(INIT_TIMEOUT_MILLIS) { initialized.await() }
            if (status != TextToSpeech.SUCCESS) {
                return@withContext AppResult.Failure(
                    "TTS_INIT_FAILED",
                    if (enginePackage.isNullOrBlank()) "Không khởi tạo được TTS mặc định."
                    else "Không khởi tạo được bộ máy TTS $enginePackage.",
                )
            }
            AppResult.Success(block(engine))
        } catch (error: Exception) {
            AppResult.Failure(
                code = "TTS_SCAN_FAILED",
                message = error.message ?: "Không quét được bộ máy và giọng TTS.",
                cause = error,
            )
        } finally {
            engine.shutdown()
        }
    }

    companion object {
        private const val INIT_TIMEOUT_MILLIS = 20_000L
    }
}
