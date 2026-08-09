package vn.nghetruyen.source.vbook

import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Base64

data class VBookContinuation(val token: String = "") {
    /** Tokens are opaque. Never parse, increment, multiply or otherwise reinterpret them. */
    fun hasNext(): Boolean = token.isNotEmpty()
}

data class VBookScriptInvocation(
    val scriptPath: String,
    val args: List<String>,
) {
    init {
        VBookPaths.normalizeScriptPath(scriptPath)
        require(args.size <= 16) { "VBOOK_TOO_MANY_SCRIPT_ARGUMENTS" }
        require(args.all { it.length <= 2 * 1024 * 1024 }) { "VBOOK_SCRIPT_ARGUMENT_TOO_LARGE" }
    }
}

data class VBookDynamicAction(
    val title: String,
    val input: String,
    val scriptPath: String,
    val data: String = "",
    /** Explore actions may explicitly declare `data`, including an empty string, as args[1]. */
    val hasDataArgument: Boolean = false,
    val type: String? = null,
) {
    fun invocation(continuation: VBookContinuation = VBookContinuation()): VBookScriptInvocation {
        val args = when {
            continuation.hasNext() -> listOf(input, continuation.token)
            hasDataArgument -> listOf(input, data)
            else -> listOf(input)
        }
        return VBookScriptInvocation(scriptPath, args)
    }
}

object VBookDynamicActionParser {
    fun parse(value: JsonValue): VBookDynamicAction? {
        val obj = value as? JsonValue.Obj ?: return null
        val rawScript = obj.string("script")?.trim()?.takeIf(String::isNotBlank) ?: return null
        return VBookDynamicAction(
            title = obj.string("title").orEmpty(),
            input = obj.string("input").orEmpty(),
            scriptPath = VBookPaths.normalizeScriptPath(rawScript),
            data = obj.string("data").orEmpty(),
            hasDataArgument = "data" in obj.values,
            type = obj.string("type"),
        )
    }

    fun collect(value: JsonValue): List<VBookDynamicAction> = when (value) {
        is JsonValue.Arr -> value.values.mapNotNull(::parse)
        is JsonValue.Obj -> buildList {
            parse(value)?.let(::add)
            listOf("tags", "genres", "suggests", "reviews", "comments").forEach { key ->
                value.array(key)?.values.orEmpty().mapNotNullTo(this, ::parse)
            }
            value["comment"]?.let(::parse)?.let(::add)
            value.array("items")?.values.orEmpty().forEach { item ->
                val itemObj = item as? JsonValue.Obj
                itemObj?.get("action")?.let(::parse)?.let(::add)
            }
        }
        else -> emptyList()
    }
}

object VBookInvocationPlanner {
    fun current(
        role: VBookScriptRole,
        scriptPath: String,
        input: String = "",
        continuation: VBookContinuation = VBookContinuation(),
        text: String = "",
        voiceId: String = "",
        from: String = "",
        to: String = "",
        source: String = "",
    ): VBookScriptInvocation {
        val args = when (role) {
            VBookScriptRole.HOME, VBookScriptRole.EXPLORE, VBookScriptRole.GENRE,
            VBookScriptRole.VOICE, VBookScriptRole.LANGUAGE -> emptyList()
            VBookScriptRole.SEARCH -> listOf(input, continuation.token)
            VBookScriptRole.DETAIL, VBookScriptRole.TOC, VBookScriptRole.CHAP,
            VBookScriptRole.PAGE, VBookScriptRole.TRACK -> listOf(input)
            VBookScriptRole.TTS -> listOf(text, voiceId)
            VBookScriptRole.TRANSLATE -> listOf(text, from, to, source)
        }
        return VBookScriptInvocation(scriptPath, args)
    }

    /** Legacy callers may provide exact positional arguments. We do not invent page arithmetic. */
    fun legacy(scriptPath: String, args: List<String>): VBookScriptInvocation = VBookScriptInvocation(scriptPath, args)
}

data class VBookConnectionSettings(
    val threadNum: Int = DEFAULT_THREAD_NUM,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val delayMs: Long = DEFAULT_DELAY_MS,
) {
    companion object {
        const val DEFAULT_THREAD_NUM = 3
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val DEFAULT_DELAY_MS = 0L
    }
}

data class VBookConfigValues(
    val values: Map<String, String>,
) {
    operator fun get(key: String): String? = values[key]

    fun connectionSettings(): VBookConnectionSettings = VBookConnectionSettings(
        threadNum = values[THREAD_NUM]?.toIntOrNull()?.coerceIn(1, 8)
            ?: VBookConnectionSettings.DEFAULT_THREAD_NUM,
        timeoutMs = values[TIMEOUT]?.toLongOrNull()?.coerceIn(100L, 120_000L)
            ?: VBookConnectionSettings.DEFAULT_TIMEOUT_MS,
        delayMs = values[DELAY]?.toLongOrNull()?.coerceIn(0L, 120_000L)
            ?: VBookConnectionSettings.DEFAULT_DELAY_MS,
    )

    companion object {
        const val THREAD_NUM = "thread_num"
        const val TIMEOUT = "timeout"
        const val DELAY = "delay"
        const val IGNORE = "ignore"
        val BUILT_IN_KEYS = setOf(THREAD_NUM, TIMEOUT, DELAY, IGNORE)

        fun resolve(
            manifest: VBookExtensionManifest,
            persisted: Map<String, String> = emptyMap(),
            runtimeOverrides: Map<String, String> = emptyMap(),
        ): VBookConfigValues {
            val output = linkedMapOf(
                THREAD_NUM to VBookConnectionSettings.DEFAULT_THREAD_NUM.toString(),
                TIMEOUT to VBookConnectionSettings.DEFAULT_TIMEOUT_MS.toString(),
                DELAY to VBookConnectionSettings.DEFAULT_DELAY_MS.toString(),
                IGNORE to "false",
            )
            manifest.config.forEach { (key, spec) -> output[key] = spec.defaultValue }
            val allowedKeys = manifest.config.keys + BUILT_IN_KEYS
            persisted.forEach { (key, value) -> if (key in allowedKeys) output[key] = value }
            runtimeOverrides.forEach { (key, value) -> if (key in allowedKeys) output[key] = value }
            return VBookConfigValues(output)
        }
    }
}

object VBookConfigPrelude {
    private val identifier = Regex("^[A-Za-z_$][A-Za-z0-9_$]{0,127}$")
    private val reserved = VBookConfigValues.BUILT_IN_KEYS

    fun build(profile: VBookContractProfile, config: VBookConfigValues): String {
        if (profile != VBookContractProfile.CURRENT_JS) return ""
        return buildString {
            config.values.toSortedMap().forEach { (key, value) ->
                require(identifier.matches(key)) { "VBOOK_CONFIG_IDENTIFIER_INVALID:$key" }
                if (key in reserved) return@forEach
                append("const ").append(key).append(" = ")
                    .append(JsonCodec.stringify(JsonValue.Str(value))).append(";\n")
            }
        }
    }
}

enum class VBookLoadKind { PACKAGE_SCRIPT, BUNDLED_CRYPTO }

data class VBookLoadTarget(val kind: VBookLoadKind, val path: String?)

object VBookLoadPolicy {
    fun resolve(raw: String): VBookLoadTarget {
        val value = raw.trim()
        if (value.equals("crypto.js", ignoreCase = true)) return VBookLoadTarget(VBookLoadKind.BUNDLED_CRYPTO, null)
        return VBookLoadTarget(VBookLoadKind.PACKAGE_SCRIPT, VBookPaths.normalizeScriptPath(value))
    }
}

data class VBookResponseEnvelope(
    val data: JsonValue,
    val continuation: VBookContinuation,
    val profile: VBookContractProfile,
    val raw: JsonValue,
)

class VBookResponseException(
    val profile: VBookContractProfile,
    val responseCode: Int?,
    message: String,
) : IllegalArgumentException(message)

object VBookResponseEnvelopeParser {
    fun parse(rawValue: JsonValue, profile: VBookContractProfile): VBookResponseEnvelope {
        val decoded = if (rawValue is JsonValue.Str) {
            runCatching { JsonCodec.parse(rawValue.value, maxDepth = 96, maxNodes = 300_000) }.getOrDefault(rawValue)
        } else rawValue
        val obj = decoded as? JsonValue.Obj
            ?: throw VBookResponseException(profile, null, "VBOOK_RESPONSE_OBJECT_REQUIRED")
        val code = obj.int("code")
        return when (profile) {
            VBookContractProfile.CURRENT_JS -> parseCurrent(obj, decoded, code)
            VBookContractProfile.LEGACY_JS -> parseLegacy(obj, decoded, code)
            VBookContractProfile.UNKNOWN -> throw VBookResponseException(profile, code, "VBOOK_CONTRACT_PROFILE_REQUIRED")
        }
    }

    private fun parseCurrent(obj: JsonValue.Obj, raw: JsonValue, code: Int?): VBookResponseEnvelope {
        when (code) {
            0 -> Unit
            1 -> throw VBookResponseException(VBookContractProfile.CURRENT_JS, code, obj.string("data") ?: "VBook error")
            else -> throw VBookResponseException(VBookContractProfile.CURRENT_JS, code, "VBOOK_CURRENT_RESPONSE_CODE_INVALID:$code")
        }
        val data2 = obj["data2"]
        val token = when (data2) {
            null, JsonValue.Null -> ""
            is JsonValue.Str -> data2.value
            else -> throw VBookResponseException(VBookContractProfile.CURRENT_JS, code, "VBOOK_CURRENT_DATA2_STRING_REQUIRED")
        }
        return VBookResponseEnvelope(obj["data"] ?: JsonValue.Null, VBookContinuation(token), VBookContractProfile.CURRENT_JS, raw)
    }

    private fun parseLegacy(obj: JsonValue.Obj, raw: JsonValue, code: Int?): VBookResponseEnvelope {
        when (code) {
            200 -> Unit
            403 -> throw VBookResponseException(VBookContractProfile.LEGACY_JS, code, obj.string("data") ?: obj.string("error") ?: "VBook legacy error")
            else -> throw VBookResponseException(VBookContractProfile.LEGACY_JS, code, "VBOOK_LEGACY_RESPONSE_CODE_INVALID:$code")
        }
        val data2 = obj["data2"] ?: obj["next"] ?: obj["nextPageUrl"]
        val token = when (data2) {
            null, JsonValue.Null -> ""
            is JsonValue.Str -> data2.value
            is JsonValue.Num -> data2.raw
            else -> JsonCodec.stringify(data2)
        }
        return VBookResponseEnvelope(obj["data"] ?: JsonValue.Null, VBookContinuation(token), VBookContractProfile.LEGACY_JS, raw)
    }
}

data class VBookFetchPlan(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: ByteArray,
    val timeoutMs: Long?,
)

object VBookFetchPlanner {
    fun create(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0),
        queries: Map<String, String> = emptyMap(),
        timeoutMs: Long? = null,
    ): VBookFetchPlan {
        require(url.length <= 8_192) { "VBOOK_FETCH_URL_TOO_LONG" }
        val normalizedMethod = method.uppercase()
        require(normalizedMethod in setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")) { "VBOOK_FETCH_METHOD_INVALID" }
        require(headers.size <= 128) { "VBOOK_FETCH_HEADERS_TOO_MANY" }
        val resolvedUrl = appendQueries(url, queries)
        URI(resolvedUrl) // validate syntax without restricting the host here; the network broker owns egress policy.
        return VBookFetchPlan(resolvedUrl, normalizedMethod, headers, body, timeoutMs?.coerceIn(100L, 120_000L))
    }

    private fun appendQueries(url: String, queries: Map<String, String>): String {
        if (queries.isEmpty()) return url
        val encoded = queries.entries.joinToString("&") { (key, value) ->
            encode(key) + "=" + encode(value)
        }
        val fragmentIndex = url.indexOf('#')
        val base = if (fragmentIndex >= 0) url.substring(0, fragmentIndex) else url
        val fragment = if (fragmentIndex >= 0) url.substring(fragmentIndex) else ""
        val delimiter = if ('?' in base) {
            if (base.endsWith('?') || base.endsWith('&')) "" else "&"
        } else "?"
        return base + delimiter + encoded + fragment
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}

data class VBookRequestInfo(
    val url: String,
    val headers: Map<String, String>,
)

data class VBookBlob(
    val bytes: ByteArray,
    val type: String,
) {
    val size: Int get() = bytes.size
    fun base64(): String = Base64.getEncoder().encodeToString(bytes)
}

data class VBookRawHttpResponse(
    val status: Int,
    val statusText: String,
    val url: String,
    val headers: Map<String, List<String>>,
    val bytes: ByteArray,
    val request: VBookRequestInfo,
) {
    val ok: Boolean get() = status in 200..299

    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value?.joinToString(", ")

    fun text(charset: String? = null): String = bytes.toString(resolveCharset(charset))

    fun base64(): String = Base64.getEncoder().encodeToString(bytes)

    fun blob(): VBookBlob = VBookBlob(bytes.copyOf(), contentType())

    fun contentType(): String = header("Content-Type")?.substringBefore(';')?.trim().orEmpty()

    private fun resolveCharset(explicit: String?): Charset {
        val requested = explicit?.trim()?.takeIf(String::isNotBlank)
            ?: header("Content-Type")?.split(';')?.map(String::trim)
                ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
                ?.substringAfter('=')?.trim()?.trim('"', '\'')
        return requested?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: StandardCharsets.UTF_8
    }
}
