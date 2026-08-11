package vn.nghetruyen.app.sourceplatform

import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue

/**
 * Decodes the string/envelope layers introduced by WebView evaluateJavascript plus the vBook
 * compatibility dispatcher. The decoder is intentionally target-aware: Chromium is only selected
 * for the generated compatibility UI_ACTION, so success means reaching the exact dispatcher data
 * object consumed by VBookCompatibilityRuntime.
 */
internal object ChromiumVBookDispatchDecoder {
    fun decode(raw: String): JsonValue.Obj {
        var current = JsonCodec.parse(raw, maxDepth = MAX_DEPTH, maxNodes = MAX_NODES)
        repeat(MAX_LAYERS) {
            val snapshot = current
            val obj = snapshot as? JsonValue.Obj
            val evaluationError = obj?.string(EVAL_ERROR_KEY)
            if (!evaluationError.isNullOrBlank()) error("CHROMIUM_EVAL_ERROR:$evaluationError")
            if (obj != null && obj.values.containsKey(RAW_RESULT_KEY)) return obj

            current = when (snapshot) {
                is JsonValue.Str -> runCatching {
                    JsonCodec.parse(snapshot.value, maxDepth = MAX_DEPTH, maxNodes = MAX_NODES)
                }.getOrElse {
                    error("CHROMIUM_DISPATCH_STRING_JSON_REQUIRED")
                }

                is JsonValue.Obj -> unwrapEnvelope(snapshot)
                else -> error("CHROMIUM_DISPATCH_RESULT_OBJECT_REQUIRED:${snapshot.javaClass.simpleName}")
            }
        }
        error("CHROMIUM_DISPATCH_RESULT_DEPTH_EXCEEDED")
    }

    private fun unwrapEnvelope(obj: JsonValue.Obj): JsonValue {
        val code = obj.int("code")
        if (code != null && code != 0) {
            error("VBOOK_RESPONSE_ERROR:${obj.string("data") ?: obj.string("error").orEmpty()}")
        }
        if (obj.bool("success") == false) {
            error("VBOOK_RESPONSE_ERROR:${obj.string("error").orEmpty()}")
        }
        return when {
            code == 0 -> obj.values["data"] ?: JsonValue.Null
            obj.bool("success") == true -> obj.values["data"] ?: JsonValue.Null
            else -> error("CHROMIUM_DISPATCH_ENVELOPE_REQUIRED")
        }
    }

    private const val RAW_RESULT_KEY = "__ngheVBookRawResult"
    private const val EVAL_ERROR_KEY = "__ngheChromiumEvalError"
    private const val MAX_LAYERS = 8
    private const val MAX_DEPTH = 96
    private const val MAX_NODES = 200_000
}
