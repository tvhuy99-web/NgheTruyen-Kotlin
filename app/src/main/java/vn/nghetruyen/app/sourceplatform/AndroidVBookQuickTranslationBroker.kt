package vn.nghetruyen.app.sourceplatform

import kotlinx.coroutines.runBlocking
import vn.nghetruyen.app.ai.vietphrase.VietPhraseDictionaryKind
import vn.nghetruyen.app.ai.vietphrase.VietPhraseEngine
import vn.nghetruyen.app.ai.vietphrase.VietPhraseOptions
import vn.nghetruyen.app.ai.vietphrase.VietPhraseRule
import vn.nghetruyen.app.data.repository.LibraryRepository
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformFailure
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceTranslationBroker
import vn.nghetruyen.source.api.SourceTranslationRequest
import vn.nghetruyen.source.api.SourceTranslationResponse

/**
 * Offline implementation for vBook `Qt.translate` base modes.
 *
 * `vp` uses the user's enabled VietPhrase dictionaries and `hv` uses only the Hán-Việt
 * (ChinesePhienAmWords) layer. Advanced vBook extras such as NER and traditional-to-simplified
 * conversion are deliberately not fabricated here; candidate validation marks those subfeatures
 * explicitly unsupported until a reference-compatible backend exists.
 */
class AndroidVBookQuickTranslationBroker(
    private val libraryRepository: LibraryRepository,
) : SourceTranslationBroker {
    @Volatile private var cachedRules: List<VietPhraseRule>? = null
    @Volatile private var cachedVp: VietPhraseEngine? = null
    @Volatile private var cachedHv: VietPhraseEngine? = null

    override fun translate(
        manifest: SourceManifest,
        request: SourceTranslationRequest,
    ): SourcePlatformResult<SourceTranslationResponse> = runCatching {
        require(request.sourceId == manifest.id) { "VBOOK_QT_SOURCE_ID_MISMATCH" }
        require(request.text.toByteArray(Charsets.UTF_8).size <= MAX_INPUT_BYTES) { "VBOOK_QT_INPUT_TOO_LARGE" }
        val target = request.targetLanguage.trim().lowercase()
        require(target == "vp" || target == "hv") { "VBOOK_QT_TARGET_UNSUPPORTED:$target" }

        val rules = runBlocking { libraryRepository.listAllVietPhraseRules() }
        val engine = engineFor(target, rules)
        val translated = engine.translate(
            request.text,
            VietPhraseOptions(
                storyId = request.storyId?.takeIf(String::isNotBlank),
                useRules = target == "vp",
                oneMeaning = true,
                normalizePunctuation = true,
                capitalizeSentences = false,
                fallbackHanViet = true,
                traceLimit = 0,
            ),
        ).let { value ->
            if (request.options["first_capitalize"].toBooleanCompat()) capitalizeFirstLetter(value) else value
        }
        require(translated.toByteArray(Charsets.UTF_8).size <= request.maxOutputBytes) { "VBOOK_QT_OUTPUT_TOO_LARGE" }
        SourceTranslationResponse(
            translatedText = translated,
            provider = if (target == "hv") "han-viet-offline" else "vietphrase-offline",
            traceId = request.traceId,
        )
    }.fold(
        onSuccess = { SourcePlatformResult.Success(it) },
        onFailure = { error -> SourcePlatformResult.Failure(SourcePlatformFailure(
            code = SourceErrorCode.TRANSLATION_UNAVAILABLE,
            message = error.message ?: "VBOOK_QT_FAILED",
            traceId = request.traceId,
            cause = error,
        )) },
    )

    @Synchronized
    private fun engineFor(target: String, rules: List<VietPhraseRule>): VietPhraseEngine {
        if (cachedRules != rules) {
            cachedRules = rules.toList()
            cachedVp = VietPhraseEngine(rules)
            cachedHv = VietPhraseEngine(rules.filter { it.kind == VietPhraseDictionaryKind.PHIEN_AM })
        }
        return if (target == "hv") requireNotNull(cachedHv) else requireNotNull(cachedVp)
    }

    private fun String?.toBooleanCompat(): Boolean = this?.equals("true", ignoreCase = true) == true || this == "1"

    private fun capitalizeFirstLetter(value: String): String {
        val index = value.indexOfFirst { it.isLetter() }
        if (index < 0) return value
        return buildString(value.length) {
            append(value, 0, index)
            append(value[index].uppercaseChar())
            append(value, index + 1, value.length)
        }
    }

    companion object {
        private const val MAX_INPUT_BYTES = 512 * 1024
    }
}

/**
 * Narrow integration bridge for the legacy SourcePlatformManager constructor.
 * It is installed once by AppContainer and can later be removed when the manager takes the
 * dedicated quickTranslation broker directly.
 */
object AndroidVBookQuickTranslationRegistry : SourceTranslationBroker {
    @Volatile private var delegate: SourceTranslationBroker = SourceTranslationBroker.DENY_ALL

    fun install(libraryRepository: LibraryRepository) {
        delegate = AndroidVBookQuickTranslationBroker(libraryRepository)
    }

    override fun translate(
        manifest: SourceManifest,
        request: SourceTranslationRequest,
    ): SourcePlatformResult<SourceTranslationResponse> = delegate.translate(manifest, request)
}
