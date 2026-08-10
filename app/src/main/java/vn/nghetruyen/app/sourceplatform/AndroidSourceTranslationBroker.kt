package vn.nghetruyen.app.sourceplatform

import kotlinx.coroutines.runBlocking
import vn.nghetruyen.app.ai.TranslationEngine
import vn.nghetruyen.app.ai.TranslationRequest
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformFailure
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceTranslationBroker
import vn.nghetruyen.source.api.SourceTranslationRequest
import vn.nghetruyen.source.api.SourceTranslationResponse
import vn.nghetruyen.source.vbook.VBookTranslationBrokerRouter

/**
 * Generic source translation uses the configured AI provider. vBook Quick Translator targets
 * (`vp`/`hv`) are intercepted before the disclosure/AI path and stay offline.
 */
class AndroidSourceTranslationBroker(
    private val engine: TranslationEngine,
    private val quickTranslation: SourceTranslationBroker = AndroidVBookQuickTranslationRegistry,
) : SourceTranslationBroker {
    override fun translate(
        manifest: SourceManifest,
        request: SourceTranslationRequest,
    ): SourcePlatformResult<SourceTranslationResponse> {
        if (request.targetLanguage.trim().lowercase() in VBookTranslationBrokerRouter.QUICK_TARGETS) {
            return quickTranslation.translate(manifest, request)
        }
        return translateOnline(manifest, request)
    }

    private fun translateOnline(
        manifest: SourceManifest,
        request: SourceTranslationRequest,
    ): SourcePlatformResult<SourceTranslationResponse> = runCatching {
        require(request.sourceId == manifest.id) { "SOURCE_TRANSLATION_SOURCE_ID_MISMATCH" }
        require(manifest.privacy.sendsContentToThirdParty) { "SOURCE_TRANSLATION_DISCLOSURE_REQUIRED" }
        require(request.text.isNotBlank()) { "SOURCE_TRANSLATION_TEXT_REQUIRED" }
        require(request.text.toByteArray(Charsets.UTF_8).size <= MAX_INPUT_BYTES) { "SOURCE_TRANSLATION_INPUT_TOO_LARGE" }
        val translated = runBlocking {
            engine.translate(TranslationRequest(
                storyId = request.storyId?.takeIf(String::isNotBlank) ?: "source:${manifest.id}",
                chapterId = request.chapterId?.takeIf(String::isNotBlank) ?: request.traceId,
                sourceText = request.text,
                instruction = buildString {
                    append("Dịch sang ").append(request.targetLanguage)
                    request.sourceLanguage?.takeIf(String::isNotBlank)?.let { append(" từ ").append(it) }
                    request.instruction.takeIf(String::isNotBlank)?.let { append(". ").append(it.take(2_000)) }
                },
            ))
        }
        val value = when (translated) {
            is AppResult.Success -> translated.value
            is AppResult.Failure -> error("${translated.code}:${translated.message}")
        }
        require(value.toByteArray(Charsets.UTF_8).size <= request.maxOutputBytes) { "SOURCE_TRANSLATION_OUTPUT_TOO_LARGE" }
        SourceTranslationResponse(
            translatedText = value,
            segments = value.split(Regex("\\n{2,}")).filter(String::isNotBlank).take(2_000),
            provider = "configured-ai",
            traceId = request.traceId,
        )
    }.fold(
        { SourcePlatformResult.Success(it) },
        { error -> SourcePlatformResult.Failure(SourcePlatformFailure(
            code = SourceErrorCode.TRANSLATION_UNAVAILABLE,
            message = error.message ?: "SOURCE_TRANSLATION_FAILED",
            traceId = request.traceId,
            cause = error,
        )) },
    )

    companion object { private const val MAX_INPUT_BYTES = 512 * 1024 }
}
