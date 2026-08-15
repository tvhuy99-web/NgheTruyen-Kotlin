package vn.nghetruyen.app.sourceplatform

import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue

/**
 * Decodes the string/envelope layers introduced by WebView evaluateJavascript plus the vBook
 * compatibility dispatcher. The decoder is intentionally target-aware: Chromium is only selected
 * for the generated compatibility UI_ACTION, so success means reaching the exact dispatcher data
 * object consumed by VBookCompatibilityRuntime.
 *
 * [checkpoint] exposes Lua-style micro stages without coupling this pure decoder to Android or the
 * diagnostics module. The callback must be side-effect safe; decoder correctness never depends on it.
 */
internal object ChromiumVBookDispatchDecoder {
    fun decode(
        raw: String,
        checkpoint: (name: String, attributes: Map<String, String>) -> Unit = { _, _ -> },
    ): JsonValue.Obj {
        checkpoint("CHROMIUM_DECODE_JSON_START", mapOf("layer" to "0", "chars" to raw.length.toString()))
        var current = JsonCodec.parse(raw, maxDepth = MAX_DEPTH, maxNodes = MAX_NODES)
        checkpoint("CHROMIUM_DECODE_JSON_OK", mapOf("layer" to "0", "valueType" to valueType(current)))

        repeat(MAX_LAYERS) { layer ->
            val snapshot = current
            val obj = snapshot as? JsonValue.Obj
            checkpoint("CHROMIUM_DECODE_LAYER_CHECK", mapOf(
                "layer" to layer.toString(),
                "valueType" to valueType(snapshot),
                "objectKeys" to (obj?.values?.size ?: 0).toString(),
            ))

            val evaluationError = obj?.string(EVAL_ERROR_KEY)
            checkpoint("CHROMIUM_DECODE_EVAL_ERROR_CHECK", mapOf(
                "layer" to layer.toString(),
                "present" to (!evaluationError.isNullOrBlank()).toString(),
            ))
            if (!evaluationError.isNullOrBlank()) error("CHROMIUM_EVAL_ERROR:$evaluationError")

            if (obj != null && obj.values.containsKey(RAW_RESULT_KEY)) {
                checkpoint("CHROMIUM_DECODE_RAW_RESULT_FOUND", mapOf(
                    "layer" to layer.toString(),
                    "objectKeys" to obj.values.size.toString(),
                ))
                return obj
            }

            current = when (snapshot) {
                is JsonValue.Str -> {
                    checkpoint("CHROMIUM_DECODE_STRING_JSON_START", mapOf(
                        "layer" to layer.toString(),
                        "chars" to snapshot.value.length.toString(),
                    ))
                    val parsed = runCatching {
                        JsonCodec.parse(snapshot.value, maxDepth = MAX_DEPTH, maxNodes = MAX_NODES)
                    }.getOrElse {
                        error("CHROMIUM_DISPATCH_STRING_JSON_REQUIRED")
                    }
                    checkpoint("CHROMIUM_DECODE_STRING_JSON_OK", mapOf(
                        "layer" to layer.toString(),
                        "valueType" to valueType(parsed),
                    ))
                    parsed
                }

                is JsonValue.Obj -> unwrapEnvelope(snapshot, layer, checkpoint)
                else -> error("CHROMIUM_DISPATCH_RESULT_OBJECT_REQUIRED:${snapshot.javaClass.simpleName}")
            }
        }
        error("CHROMIUM_DISPATCH_RESULT_DEPTH_EXCEEDED")
    }

    private fun unwrapEnvelope(
        obj: JsonValue.Obj,
        layer: Int,
        checkpoint: (String, Map<String, String>) -> Unit,
    ): JsonValue {
        val code = obj.int("code")
        val success = obj.bool("success")
        checkpoint("CHROMIUM_DECODE_ENVELOPE_CHECK", mapOf(
            "layer" to layer.toString(),
            "code" to (code?.toString() ?: "missing"),
            "success" to (success?.toString() ?: "missing"),
            "keys" to obj.values.size.toString(),
        ))
        if (code != null && code != 0) {
            error("VBOOK_RESPONSE_ERROR:${obj.string("data") ?: obj.string("error").orEmpty()}")
        }
        if (success == false) {
            error("VBOOK_RESPONSE_ERROR:${obj.string("error").orEmpty()}")
        }
        val value = when {
            code == 0 -> obj.values["data"] ?: JsonValue.Null
            success == true -> obj.values["data"] ?: JsonValue.Null
            else -> error("CHROMIUM_DISPATCH_ENVELOPE_REQUIRED")
        }
        checkpoint("CHROMIUM_DECODE_ENVELOPE_OK", mapOf(
            "layer" to layer.toString(),
            "valueType" to valueType(value),
        ))
        return value
    }

    private fun valueType(value: JsonValue): String = when (value) {
        is JsonValue.Obj -> "object"
        is JsonValue.Arr -> "array"
        is JsonValue.Str -> "string"
        is JsonValue.Num -> "number"
        is JsonValue.Bool -> "boolean"
        JsonValue.Null -> "null"
    }

    private const val RAW_RESULT_KEY = "__ngheVBookRawResult"
    private const val EVAL_ERROR_KEY = "__ngheChromiumEvalError"
    private const val MAX_LAYERS = 8
    private const val MAX_DEPTH = 96
    private const val MAX_NODES = 200_000
}
