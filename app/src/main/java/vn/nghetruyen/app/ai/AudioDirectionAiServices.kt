package vn.nghetruyen.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.data.repository.LibraryRepository
import vn.nghetruyen.app.data.settings.AiProvider
import vn.nghetruyen.app.data.settings.SettingsRepository
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** Lightweight AI transport dedicated to the ambience/SFX direction pass. */
class AudioDirectionAiServices(
    private val settingsRepository: SettingsRepository,
    private val credentialStore: AiCredentialStore,
    private val requestGovernor: AiRequestGovernor,
    private val libraryRepository: LibraryRepository,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .dns(AiPublicDns)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    data class Response(
        val content: String,
        val provider: String,
        val model: String,
    )

    private data class Config(
        val provider: AiProvider,
        val endpoint: String,
        val model: String,
        val temperature: Float,
        val enabled: Boolean,
        val timeoutMillis: Int,
    )

    private data class AiRequest(
        val url: String,
        val headers: Map<String, String>,
        val body: String,
    )

    suspend fun direct(storyId: String, prompt: String): AppResult<Response> {
        if (prompt.isBlank()) return AppResult.Failure("AI_EMPTY_INPUT", "Prompt đạo diễn âm thanh đang trống.")
        if (prompt.length > MAX_PROMPT_CHARS) {
            return AppResult.Failure("AI_INPUT_TOO_LARGE", "Prompt đạo diễn âm thanh vượt giới hạn an toàn.")
        }
        val config = resolveConfig(storyId)
        if (!config.enabled) return AppResult.Failure("AI_DISABLED", "AI online đang tắt.")
        if (config.model.isBlank()) return AppResult.Failure("AI_MODEL_MISSING", "Chưa cấu hình model AI.")
        if (config.provider == AiProvider.OPENAI_COMPATIBLE) {
            AiEndpointPolicy.validate(config.endpoint).exceptionOrNull()?.let {
                return AppResult.Failure("AI_ENDPOINT_INVALID", it.message ?: "Endpoint AI không hợp lệ.", it)
            }
        }
        val permit = when (val reserved = requestGovernor.reserve(prompt.length)) {
            is AppResult.Failure -> return reserved
            is AppResult.Success -> reserved.value
        }
        return withContext(Dispatchers.IO) {
            val result = runCatching { execute(config, prompt) }.fold(
                onSuccess = { it },
                onFailure = { error ->
                    when (error) {
                        is IOException -> AppResult.Failure("AI_NETWORK_ERROR", error.message ?: "Không kết nối được nhà cung cấp AI.", error)
                        else -> AppResult.Failure("AI_BAD_RESPONSE", error.message ?: "Không đọc được phản hồi AI.", error)
                    }
                },
            )
            when (result) {
                is AppResult.Success -> requestGovernor.finish(permit, result.value.content.length, 0, null)
                is AppResult.Failure -> requestGovernor.finish(permit, 0, 0, result.code)
            }
            result
        }
    }

    private fun execute(config: Config, prompt: String): AppResult<Response> {
        val apiKey = credentialStore.apiKey(config.provider)?.trim().orEmpty()
        if (config.provider == AiProvider.GEMINI && apiKey.isBlank()) {
            return AppResult.Failure("AI_KEY_MISSING", "Chưa lưu API key cho Gemini.")
        }
        val requests = runCatching { buildRequests(config, apiKey, prompt) }.getOrElse {
            return AppResult.Failure("AI_CONFIGURATION_INVALID", it.message ?: "Cấu hình AI không hợp lệ.", it)
        }
        if (requests.isEmpty()) return AppResult.Failure("AI_ENDPOINT_INVALID", "Không tạo được endpoint AI hợp lệ.")

        val timeout = config.timeoutMillis.coerceAtLeast(10_000)
        val callClient = client.newBuilder()
            .connectTimeout(minOf(timeout, 30_000).toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .build()
        var lastFailure: AppResult.Failure? = null

        requests.forEachIndexed { index, requestData ->
            try {
                val request = Request.Builder()
                    .url(requestData.url)
                    .header("Accept", "application/json")
                    .apply { requestData.headers.forEach { (name, value) -> header(name, value) } }
                    .post(requestData.body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                callClient.newCall(request).execute().use { response ->
                    if (response.isRedirect) {
                        lastFailure = AppResult.Failure("AI_REDIRECT_BLOCKED", "Endpoint AI trả redirect; hãy dùng URL API trực tiếp.")
                        return@forEachIndexed
                    }
                    val raw = response.body?.charStream()?.use { reader ->
                        buildString {
                            val buffer = CharArray(8_192)
                            while (length <= MAX_RESPONSE_CHARS) {
                                val count = reader.read(buffer, 0, min(buffer.size, MAX_RESPONSE_CHARS + 1 - length))
                                if (count < 0) break
                                append(buffer, 0, count)
                            }
                        }
                    }.orEmpty()
                    if (raw.length > MAX_RESPONSE_CHARS) {
                        return AppResult.Failure("AI_RESPONSE_TOO_LARGE", "Phản hồi AI vượt giới hạn an toàn.")
                    }
                    if (!response.isSuccessful) {
                        lastFailure = AppResult.Failure(
                            "AI_HTTP_${response.code}",
                            extractError(raw).ifBlank { "Nhà cung cấp AI trả lỗi HTTP ${response.code}." }.take(500),
                        )
                        val canFallback = config.provider == AiProvider.OPENAI_COMPATIBLE &&
                            response.code in OPENAI_ENDPOINT_FALLBACK_HTTP_CODES && index < requests.lastIndex
                        if (canFallback) return@forEachIndexed
                        return lastFailure!!
                    }
                    val content = runCatching { extractContent(config.provider, raw) }.getOrElse { error ->
                        lastFailure = AppResult.Failure("AI_BAD_RESPONSE", error.message ?: "Không đọc được phản hồi AI.", error)
                        ""
                    }
                    if (content.isNotBlank()) {
                        return AppResult.Success(Response(content.trim(), config.provider.name, config.model))
                    }
                    lastFailure = AppResult.Failure("AI_EMPTY_RESPONSE", "AI trả về nội dung trống.")
                }
            } catch (error: IOException) {
                lastFailure = AppResult.Failure("AI_NETWORK_ERROR", error.message ?: "Không kết nối được nhà cung cấp AI.", error)
            } catch (error: Exception) {
                lastFailure = AppResult.Failure("AI_NETWORK_ERROR", error.message ?: "Không kết nối được nhà cung cấp AI.", error)
            }
        }
        return lastFailure ?: AppResult.Failure("AI_EMPTY_RESPONSE", "AI trả về nội dung trống.")
    }

    private fun buildRequests(config: Config, apiKey: String, prompt: String): List<AiRequest> = when (config.provider) {
        AiProvider.GEMINI -> {
            val model = config.model.removePrefix("models/").trim()
            require(GEMINI_MODEL_PATTERN.matches(model)) { "Tên model Gemini chứa ký tự không hợp lệ." }
            val body = JSONObject()
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt))),
                    ),
                )
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("temperature", config.temperature.toDouble())
                        .put("responseMimeType", "application/json"),
                )
                .toString()
            listOf(
                AiRequest(
                    url = "$GEMINI_API_BASE/models/$model:generateContent",
                    headers = mapOf("x-goog-api-key" to apiKey),
                    body = body,
                ),
            )
        }
        AiProvider.OPENAI_COMPATIBLE -> {
            val headers = buildMap { if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey") }
            openAiCandidateUrls(config.endpoint).map { url ->
                val path = url.substringBefore('?').substringBefore('#').trimEnd('/').lowercase()
                val body = if (path.endsWith("/responses")) {
                    JSONObject().put("model", config.model).put("input", prompt).toString()
                } else {
                    JSONObject()
                        .put("model", config.model)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                        .put("temperature", config.temperature.toDouble())
                        .toString()
                }
                AiRequest(url, headers, body)
            }
        }
    }

    private fun openAiCandidateUrls(value: String): List<String> {
        val original = value.trim()
        if (original.isBlank()) return emptyList()
        val tailIndex = original.indexOfFirst { it == '?' || it == '#' }
        val path = if (tailIndex >= 0) original.substring(0, tailIndex) else original
        val tail = if (tailIndex >= 0) original.substring(tailIndex) else ""
        val base = path.trimEnd('/')
        val out = mutableListOf<String>()
        val seen = linkedSetOf<String>()
        fun append(candidate: String) {
            val clean = candidate.trimEnd('/')
            if (clean.isNotBlank() && seen.add(clean + tail)) out += clean + tail
        }
        append(base)
        when {
            base.endsWith("/responses", ignoreCase = true) -> append(base.dropLast("/responses".length) + "/chat/completions")
            base.endsWith("/chat/completions", ignoreCase = true) -> append(base.dropLast("/chat/completions".length) + "/responses")
            else -> {
                append("$base/chat/completions")
                append("$base/responses")
                if (!base.endsWith("/v1", ignoreCase = true)) {
                    append("$base/v1/chat/completions")
                    append("$base/v1/responses")
                }
            }
        }
        return out
    }

    private suspend fun resolveConfig(storyId: String): Config {
        val global = settingsRepository.snapshot().aiOnline
        val profile = storyId.takeIf(String::isNotBlank)?.let { libraryRepository.getStoryAiProfile(it) }
        val provider = if (profile?.overrideProvider == true) {
            runCatching { AiProvider.valueOf(profile.provider) }.getOrDefault(global.provider)
        } else global.provider
        return Config(
            provider = provider,
            endpoint = profile?.endpoint?.takeIf { profile.overrideProvider && it.isNotBlank() } ?: global.endpoint,
            model = profile?.model?.takeIf { profile.overrideProvider && it.isNotBlank() } ?: global.model,
            temperature = profile?.temperature?.takeIf { it in 0f..2f } ?: global.temperature,
            enabled = global.enabled,
            timeoutMillis = global.timeoutMillis,
        )
    }

    private fun extractContent(provider: AiProvider, raw: String): String = when (provider) {
        AiProvider.GEMINI -> {
            val root = JSONObject(raw)
            root.optJSONObject("error")?.optString("message")?.takeIf(String::isNotBlank)?.let { error(it) }
            val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
                ?: error(root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty().ifBlank { "Gemini không trả candidate." })
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: error("Gemini không trả nội dung.")
            buildString {
                for (index in 0 until parts.length()) {
                    parts.optJSONObject(index)?.optString("text")?.takeIf(String::isNotBlank)?.let {
                        if (isNotEmpty()) append('\n')
                        append(it)
                    }
                }
            }
        }
        AiProvider.OPENAI_COMPATIBLE -> extractOpenAiContent(raw)
    }

    private fun extractOpenAiContent(raw: String): String {
        val root = JSONObject(raw)
        root.optJSONObject("error")?.optString("message")?.takeIf(String::isNotBlank)?.let { error(it) }
        root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.opt("content")?.let { content ->
            when (content) {
                is String -> if (content.isNotBlank()) return content
                is JSONArray -> {
                    val text = buildString {
                        for (index in 0 until content.length()) {
                            content.optJSONObject(index)?.optString("text")?.takeIf(String::isNotBlank)?.let {
                                if (isNotEmpty()) append('\n')
                                append(it)
                            }
                        }
                    }
                    if (text.isNotBlank()) return text
                }
            }
        }
        root.optString("output_text").takeIf(String::isNotBlank)?.let { return it }
        val output = root.optJSONArray("output") ?: JSONArray()
        val text = buildString {
            for (outputIndex in 0 until output.length()) {
                val parts = output.optJSONObject(outputIndex)?.optJSONArray("content") ?: continue
                for (partIndex in 0 until parts.length()) {
                    parts.optJSONObject(partIndex)?.optString("text")?.takeIf(String::isNotBlank)?.let {
                        if (isNotEmpty()) append('\n')
                        append(it)
                    }
                }
            }
        }
        return text.takeIf(String::isNotBlank) ?: error("OpenAI-compatible API không trả nội dung")
    }

    private fun extractError(raw: String): String = runCatching {
        val root = JSONObject(raw)
        val error = root.opt("error")
        when (error) {
            is JSONObject -> error.optString("message")
            is String -> error
            else -> root.optString("message")
        }
    }.getOrDefault("")

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta"
        private val GEMINI_MODEL_PATTERN = Regex("^[A-Za-z0-9._-]+$")
        private val OPENAI_ENDPOINT_FALLBACK_HTTP_CODES = setOf(404, 405)
        private const val MAX_PROMPT_CHARS = 160_000
        private const val MAX_RESPONSE_CHARS = 2_000_000
    }
}
