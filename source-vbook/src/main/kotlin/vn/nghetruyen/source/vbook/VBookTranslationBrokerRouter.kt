package vn.nghetruyen.source.vbook

import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceTranslationBroker
import vn.nghetruyen.source.api.SourceTranslationRequest
import vn.nghetruyen.source.api.SourceTranslationResponse

/** Keeps vBook's offline Quick Translator ABI separate from generic translation/AI brokers. */
class VBookTranslationBrokerRouter(
    private val generic: SourceTranslationBroker,
    private val quick: SourceTranslationBroker,
) : SourceTranslationBroker {
    override fun translate(
        manifest: SourceManifest,
        request: SourceTranslationRequest,
    ): SourcePlatformResult<SourceTranslationResponse> {
        val target = request.targetLanguage.trim().lowercase()
        val markedQuickRequest = request.instruction.startsWith(QUICK_TRANSLATOR_PREFIX)
        if (!markedQuickRequest && target !in QUICK_TARGETS) {
            return generic.translate(manifest, request)
        }
        val options = if (markedQuickRequest) {
            parseOptions(request.instruction.removePrefix(QUICK_TRANSLATOR_PREFIX))
        } else {
            request.options
        }
        return quick.translate(
            manifest,
            request.copy(
                targetLanguage = target.ifBlank { "vp" },
                instruction = "",
                options = options,
            ),
        )
    }

    private fun parseOptions(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val root = runCatching { JsonCodec.parse(raw, maxDepth = 16, maxNodes = 512) as? JsonValue.Obj }
            .getOrNull() ?: return emptyMap()
        return root.values.entries.take(32).mapNotNull { (key, value) ->
            val text = when (value) {
                is JsonValue.Str -> value.value
                is JsonValue.Bool -> value.value.toString()
                is JsonValue.Num -> value.raw
                JsonValue.Null -> ""
                else -> null
            }
            text?.let { key to it.take(512) }
        }.toMap(LinkedHashMap())
    }

    companion object {
        const val QUICK_TRANSLATOR_PREFIX = "__NGHE_VBOOK_QT_V1__:"
        val QUICK_TARGETS = setOf("vp", "hv")
    }
}
