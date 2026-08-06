package vn.nghetruyen.app.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class TtsSynthesisVoice(
    val voiceName: String?,
    val languageTag: String,
    val rate: Float,
    val pitch: Float,
)

/** Small coroutine wrapper around Android's asynchronous TTS file API. */
class TtsFileSynthesizer(
    context: Context,
    enginePackage: String? = null,
) : Closeable {
    private val initialized = CompletableDeferred<Int>()
    private val completions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val engine: TextToSpeech = if (enginePackage.isNullOrBlank()) {
        TextToSpeech(context.applicationContext) { status -> initialized.completeOnce(status) }
    } else {
        TextToSpeech(context.applicationContext, { status -> initialized.completeOnce(status) }, enginePackage)
    }

    init {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { utteranceId?.let { completions.remove(it)?.complete(true) } }
            override fun onError(utteranceId: String?) { utteranceId?.let { completions.remove(it)?.complete(false) } }
            override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)
        })
    }

    suspend fun configure(voice: TtsSynthesisVoice) {
        val status = withTimeout(INIT_TIMEOUT_MILLIS) { initialized.await() }
        if (status != TextToSpeech.SUCCESS) throw IOException("Không khởi tạo được bộ máy TTS đã chọn.")
        withContext(Dispatchers.Main) {
            val locale = Locale.forLanguageTag(voice.languageTag.ifBlank { "vi-VN" })
            val languageResult = engine.setLanguage(locale)
            if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                val fallback = engine.setLanguage(Locale.getDefault())
                if (fallback == TextToSpeech.LANG_MISSING_DATA || fallback == TextToSpeech.LANG_NOT_SUPPORTED) {
                    throw IOException("Thiết bị thiếu dữ liệu giọng đọc phù hợp.")
                }
            }
            val selectedName = voice.voiceName?.takeIf(String::isNotBlank)
            if (selectedName != null) {
                val selected = engine.voices.orEmpty().firstOrNull { it.name == selectedName }
                    ?: throw IOException("Giọng TTS đã chọn không còn tồn tại trong bộ máy này.")
                if (engine.setVoice(selected) == TextToSpeech.ERROR) {
                    throw IOException("Không sử dụng được giọng TTS đã chọn.")
                }
            }
            if (engine.setSpeechRate(voice.rate.coerceIn(0.5f, 2.0f)) == TextToSpeech.ERROR) {
                throw IOException("Bộ máy TTS không chấp nhận tốc độ đọc.")
            }
            if (engine.setPitch(voice.pitch.coerceIn(0.5f, 2.0f)) == TextToSpeech.ERROR) {
                throw IOException("Bộ máy TTS không chấp nhận cao độ.")
            }
        }
    }

    suspend fun synthesize(text: String, output: File, utteranceId: String) {
        val normalized = text.trim()
        require(normalized.isNotBlank()) { "Không thể tổng hợp đoạn văn trống." }
        require(normalized.length <= TextToSpeech.getMaxSpeechInputLength()) {
            "Đoạn văn vượt giới hạn của bộ máy TTS."
        }
        output.parentFile?.mkdirs()
        if (output.exists() && !output.delete()) throw IOException("Không thể thay tệp âm thanh tạm.")
        val completion = CompletableDeferred<Boolean>()
        completions[utteranceId] = completion
        val queued = withContext(Dispatchers.Main) {
            engine.synthesizeToFile(normalized, Bundle(), output, utteranceId)
        }
        if (queued == TextToSpeech.ERROR) {
            completions.remove(utteranceId)
            throw IOException("Bộ máy TTS từ chối yêu cầu xuất tệp.")
        }
        val success = try {
            withTimeout(SYNTHESIS_TIMEOUT_MILLIS) { completion.await() }
        } finally {
            completions.remove(utteranceId)
        }
        if (!success || !output.isFile || output.length() < 44L) {
            throw IOException("Bộ máy TTS không tạo được tệp WAV hợp lệ.")
        }
    }

    override fun close() {
        completions.values.forEach { it.cancel() }
        completions.clear()
        engine.stop()
        engine.shutdown()
    }

    private fun CompletableDeferred<Int>.completeOnce(value: Int) {
        if (!isCompleted) complete(value)
    }

    companion object {
        private const val INIT_TIMEOUT_MILLIS = 20_000L
        private const val SYNTHESIS_TIMEOUT_MILLIS = 120_000L
    }
}
