package vn.nghetruyen.source.vbook

import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourceCapabilityBrokers
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourcePlatformFailure
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.diagnostics.DiagnosticSink
import java.util.Base64

data class VBookTtsVoice(
    val id: String,
    val name: String,
    val language: String,
)

data class VBookTtsAudio(
    val base64: String,
) {
    fun bytes(): ByteArray = Base64.getDecoder().decode(base64)
}

data class VBookTranslateLanguage(
    val id: String,
    val name: String,
    /** `from`, `to`, or null for both directions. */
    val type: String?,
)

data class VBookTranslationSegment(
    val srcStart: Int,
    val srcLen: Int,
    val transStart: Int,
    val transLen: Int,
    val type: Int,
)

data class VBookTranslationOutput(
    val translateText: String,
    val segments: List<VBookTranslationSegment> = emptyList(),
)

data class VBookMediaSourceOption(
    val title: String,
    val data: String,
)

data class VBookMediaTrack(
    val type: String,
    val data: String,
    val host: String?,
    val mimeType: String?,
    val headers: Map<String, String>,
    val timeSkip: List<JsonValue>,
    val raw: JsonValue.Obj,
)

/**
 * Content-type provider facade over one immutable vBook package.
 *
 * This is deliberately not an Android StorySource. Comic/media/TTS/translate keep their native
 * vBook contracts and only normalize their return values at this provider boundary.
 */
class VBookProviderSession(
    artifactIdentity: String,
    packageBytes: ByteArray,
    brokers: SourceCapabilityBrokers,
    private val configReader: VBookConfigReader = VBookConfigReader { emptyMap() },
    diagnostics: DiagnosticSink = DiagnosticSink.NONE,
) {
    private val pkg = VBookPackageReader.read(packageBytes)
    private val resources = VBookPackageResourceProvider(pkg)
    val manifest: VBookExtensionManifest = VBookManifestParser.parse(pkg.pluginJson())
    val contentType: VBookContentType = manifest.metadata.type
    val sourceId: String = VBookHostManifestFactory.stableSourceId(artifactIdentity)
    private val configKey = artifactIdentity
    private val hostManifest = VBookHostManifestFactory.create(artifactIdentity, manifest, resources)
    private val runtime = VBookCompatibilityRuntime(brokers, diagnostics)

    fun comicPages(chapterUrl: String, traceId: String = ""): SourcePlatformResult<List<String>> {
        if (contentType != VBookContentType.COMIC) return wrongType(VBookContentType.COMIC, traceId)
        val role = when {
            manifest.script(VBookScriptRole.PAGE) != null -> VBookScriptRole.PAGE
            manifest.script(VBookScriptRole.CHAP) != null -> VBookScriptRole.CHAP
            else -> return SourcePlatformResult.Success(listOf(chapterUrl))
        }
        return execute(role, input = chapterUrl, traceId = traceId).flatMap { result ->
            val pages = (result.data as? JsonValue.Arr)?.values?.mapNotNull { (it as? JsonValue.Str)?.value }
                ?: return@flatMap invalid("VBOOK_COMIC_PAGE_ARRAY_REQUIRED", traceId)
            if (pages.any(String::isBlank)) return@flatMap invalid("VBOOK_COMIC_PAGE_URL_BLANK", traceId)
            SourcePlatformResult.Success(pages)
        }
    }

    /** `chap` is optional for video/audio. Without it the episode URL is passed straight to track. */
    fun mediaSources(episodeUrl: String, traceId: String = ""): SourcePlatformResult<List<VBookMediaSourceOption>> {
        if (contentType !in setOf(VBookContentType.VIDEO, VBookContentType.AUDIO)) {
            return invalid("VBOOK_MEDIA_CONTENT_TYPE_REQUIRED", traceId)
        }
        if (manifest.script(VBookScriptRole.CHAP) == null) {
            return SourcePlatformResult.Success(listOf(VBookMediaSourceOption("Mặc định", episodeUrl)))
        }
        return execute(VBookScriptRole.CHAP, input = episodeUrl, traceId = traceId).flatMap { result ->
            val array = result.data as? JsonValue.Arr ?: return@flatMap invalid("VBOOK_MEDIA_SOURCE_ARRAY_REQUIRED", traceId)
            val sources = array.values.mapIndexedNotNull { index, raw ->
                val obj = raw as? JsonValue.Obj ?: return@mapIndexedNotNull null
                val data = obj.string("data")?.takeIf(String::isNotBlank) ?: return@mapIndexedNotNull null
                VBookMediaSourceOption(
                    title = obj.string("title")?.takeIf(String::isNotBlank) ?: "Server ${index + 1}",
                    data = data,
                )
            }
            if (sources.isEmpty()) invalid("VBOOK_MEDIA_SOURCE_EMPTY", traceId) else SourcePlatformResult.Success(sources)
        }
    }

    fun resolveTrack(data: String, traceId: String = ""): SourcePlatformResult<VBookMediaTrack> {
        if (contentType !in setOf(VBookContentType.VIDEO, VBookContentType.AUDIO)) {
            return invalid("VBOOK_MEDIA_CONTENT_TYPE_REQUIRED", traceId)
        }
        return execute(VBookScriptRole.TRACK, input = data, traceId = traceId).flatMap { result ->
            val obj = result.data as? JsonValue.Obj ?: return@flatMap invalid("VBOOK_MEDIA_TRACK_OBJECT_REQUIRED", traceId)
            val type = obj.string("type")?.takeIf(String::isNotBlank)
                ?: return@flatMap invalid("VBOOK_MEDIA_TRACK_TYPE_REQUIRED", traceId)
            val resolved = obj.string("data")?.takeIf(String::isNotBlank)
                ?: return@flatMap invalid("VBOOK_MEDIA_TRACK_DATA_REQUIRED", traceId)
            val headers = obj.obj("headers")?.values.orEmpty().mapNotNull { (key, value) ->
                scalarString(value)?.let { key to it }
            }.toMap(LinkedHashMap())
            SourcePlatformResult.Success(VBookMediaTrack(
                type = type,
                data = resolved,
                host = obj.string("host"),
                mimeType = obj.string("mimeType"),
                headers = headers,
                timeSkip = obj.array("timeSkip")?.values.orEmpty(),
                raw = obj,
            ))
        }
    }

    fun voices(traceId: String = ""): SourcePlatformResult<List<VBookTtsVoice>> {
        if (contentType != VBookContentType.TTS) return wrongType(VBookContentType.TTS, traceId)
        return execute(VBookScriptRole.VOICE, traceId = traceId).flatMap { result ->
            val array = result.data as? JsonValue.Arr ?: return@flatMap invalid("VBOOK_TTS_VOICE_ARRAY_REQUIRED", traceId)
            val voices = array.values.mapNotNull { raw ->
                val obj = raw as? JsonValue.Obj ?: return@mapNotNull null
                val id = obj.string("id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                VBookTtsVoice(
                    id = id,
                    name = obj.string("name")?.takeIf(String::isNotBlank) ?: id,
                    language = obj.string("language").orEmpty(),
                )
            }
            if (voices.isEmpty()) invalid("VBOOK_TTS_VOICE_EMPTY", traceId) else SourcePlatformResult.Success(voices)
        }
    }

    fun synthesize(text: String, voiceId: String, traceId: String = ""): SourcePlatformResult<VBookTtsAudio> {
        if (contentType != VBookContentType.TTS) return wrongType(VBookContentType.TTS, traceId)
        return execute(VBookScriptRole.TTS, text = text, voiceId = voiceId, traceId = traceId).flatMap { result ->
            val base64 = (result.data as? JsonValue.Str)?.value?.takeIf(String::isNotBlank)
                ?: return@flatMap invalid("VBOOK_TTS_BASE64_REQUIRED", traceId)
            if (runCatching { Base64.getDecoder().decode(base64) }.isFailure) {
                return@flatMap invalid("VBOOK_TTS_BASE64_INVALID", traceId)
            }
            SourcePlatformResult.Success(VBookTtsAudio(base64))
        }
    }

    fun languages(traceId: String = ""): SourcePlatformResult<List<VBookTranslateLanguage>> {
        if (contentType != VBookContentType.TRANSLATE) return wrongType(VBookContentType.TRANSLATE, traceId)
        return execute(VBookScriptRole.LANGUAGE, traceId = traceId).flatMap { result ->
            val array = result.data as? JsonValue.Arr ?: return@flatMap invalid("VBOOK_TRANSLATE_LANGUAGE_ARRAY_REQUIRED", traceId)
            val values = array.values.mapNotNull { raw ->
                val obj = raw as? JsonValue.Obj ?: return@mapNotNull null
                val id = obj.string("id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val type = obj.string("type")?.lowercase()?.takeIf { it in setOf("from", "to") }
                VBookTranslateLanguage(
                    id = id,
                    name = obj.string("name")?.takeIf(String::isNotBlank) ?: id,
                    type = type,
                )
            }
            if (values.isEmpty()) invalid("VBOOK_TRANSLATE_LANGUAGE_EMPTY", traceId) else SourcePlatformResult.Success(values)
        }
    }

    fun translate(
        text: String,
        from: String,
        to: String,
        source: String = "",
        traceId: String = "",
    ): SourcePlatformResult<VBookTranslationOutput> {
        if (contentType != VBookContentType.TRANSLATE) return wrongType(VBookContentType.TRANSLATE, traceId)
        return execute(
            VBookScriptRole.TRANSLATE,
            text = text,
            from = from,
            to = to,
            source = source,
            traceId = traceId,
        ).flatMap { result -> parseTranslation(result.data, traceId) }
    }

    private fun parseTranslation(data: JsonValue, traceId: String): SourcePlatformResult<VBookTranslationOutput> {
        return when (data) {
            is JsonValue.Str -> SourcePlatformResult.Success(VBookTranslationOutput(data.value))
            is JsonValue.Obj -> {
                val text = data.string("translateText") ?: data.string("text")
                    ?: return invalid("VBOOK_TRANSLATE_TEXT_REQUIRED", traceId)
                val segments = data.array("segments")?.values.orEmpty().mapNotNull { raw ->
                    val obj = raw as? JsonValue.Obj ?: return@mapNotNull null
                    val srcStart = obj.int("srcStart") ?: return@mapNotNull null
                    val srcLen = obj.int("srcLen") ?: return@mapNotNull null
                    val transStart = obj.int("transStart") ?: return@mapNotNull null
                    val transLen = obj.int("transLen") ?: return@mapNotNull null
                    val type = obj.int("type") ?: 0
                    if (minOf(srcStart, srcLen, transStart, transLen) < 0) return@mapNotNull null
                    VBookTranslationSegment(srcStart, srcLen, transStart, transLen, type)
                }
                SourcePlatformResult.Success(VBookTranslationOutput(text, segments))
            }
            else -> invalid("VBOOK_TRANSLATE_RESULT_INVALID", traceId)
        }
    }

    private fun execute(
        role: VBookScriptRole,
        input: String = "",
        text: String = "",
        voiceId: String = "",
        from: String = "",
        to: String = "",
        source: String = "",
        traceId: String = "",
    ): SourcePlatformResult<VBookCompatibilityRuntime.ExecutionResult> = runtime.executeDeclared(
        sourceManifest = hostManifest,
        resources = resources,
        role = role,
        input = input,
        text = text,
        voiceId = voiceId,
        from = from,
        to = to,
        source = source,
        persistedConfig = configReader.read(configKey),
        traceId = traceId,
    )

    private fun wrongType(expected: VBookContentType, traceId: String): SourcePlatformResult.Failure =
        invalid("VBOOK_PROVIDER_TYPE_REQUIRED:$expected:actual=$contentType", traceId)

    private fun invalid(message: String, traceId: String): SourcePlatformResult.Failure = SourcePlatformResult.Failure(
        SourcePlatformFailure(SourceErrorCode.VBOOK_SCRIPT_ERROR, message, traceId),
    )

    private fun scalarString(value: JsonValue): String? = when (value) {
        is JsonValue.Str -> value.value
        is JsonValue.Num -> value.raw
        is JsonValue.Bool -> value.value.toString()
        JsonValue.Null -> null
        else -> null
    }

    private inline fun <T, R> SourcePlatformResult<T>.flatMap(transform: (T) -> SourcePlatformResult<R>): SourcePlatformResult<R> =
        when (this) {
            is SourcePlatformResult.Success -> transform(value)
            is SourcePlatformResult.Failure -> this
        }
}
