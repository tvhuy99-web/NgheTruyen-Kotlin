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
        val request = when (config.provider) {
            AiProvider.GEMINI -> geminiRequest(config, apiKey, prompt)
            AiProvider.OPENAI_COMPATIBLE -> openAiRequest(config, apiKey, prompt)
        }
        val timeout = config.timeoutMillis.coerceAtLeast(10_000)
        val callClient = client.newBuilder()
            .connectTimeout(minOf(timeout, 30_000).toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .build()
        callClient.newCall(request).execute().use { response ->
            if (response.isRedirect) {
                return AppResult.Failure("AI_REDIRECT_BLOCKED", "Endpoint AI trả redirect; hãy dùng URL API trực tiếp.")
            }
            val raw = response.body?.string().orEmpty()
            if (raw.length > MAX_RESPONSE_CHARS) {
                return AppResult.Failure("AI_RESPONSE_TOO_LARGE", "Phản hồi AI vượt giới hạn an toàn.")
            }
            if (!response.isSuccessful) {
                return AppResult.Failure(
                    "AI_HTTP_${response.code}",
                    extractError(raw).ifBlank { "Nhà cung cấp AI trả lỗi HTTP ${response.code}." }.take(500),
                )
            }
            val content = extractContent(config.provider, raw).trim()
            if (content.isBlank()) return AppResult.Failure("AI_BAD_RESPONSE", "AI không trả nội dung đạo diễn âm thanh.")
            return AppResult.Success(Response(content, config.provider.name, config.model))
        }
    }

    private fun geminiRequest(config: Config, apiKey: String, prompt: String): Request {
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", prompt))),
                ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", config.temperature.toDouble())
                    .put("responseMimeType", "application/json"),
            )
            .toString()
        val model = config.model.removePrefix("models/")
        return Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("Accept", "application/json")
            .header("x-goog-api-key", apiKey)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun openAiRequest(config: Config, apiKey: String, prompt: String): Request {
        val endpoint = config.endpoint.trim()
        val isResponses = endpoint.trimEnd('/').endsWith("/responses", ignoreCase = true)
        val body = if (isResponses) {
            JSONObject()
                .put("model", config.model)
                .put("temperature", config.temperature.toDouble())
                .put(
                    "input",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "content",
                                JSONArray().put(JSONObject().put("type", "input_text").put("text", prompt)),
                            ),
                    ),
                )
        } else {
            JSONObject()
                .put("model", config.model)
                .put("temperature", config.temperature.toDouble())
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                .put("response_format", JSONObject().put("type", "json_object"))
        }
        return Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
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

    private fun extractContent(provider: AiProvider, raw: String): String {
        val root = JSONObject(raw)
        return when (provider) {
            AiProvider.GEMINI -> {
                val candidates = root.optJSONArray("candidates") ?: return ""
                val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
                buildString {
                    for (index in 0 until parts.length()) {
                        parts.optJSONObject(index)?.optString("text")?.takeIf(String::isNotBlank)?.let(::append)
                    }
                }
            }
            AiProvider.OPENAI_COMPATIBLE -> {
                root.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.takeIf(String::isNotBlank)
                    ?: extractResponsesText(root)
            }
        }
    }

    private fun extractResponsesText(root: JSONObject): String {
        root.optString("output_text").takeIf(String::isNotBlank)?.let { return it }
        val output = root.optJSONArray("output") ?: return ""
        val out = StringBuilder()
        for (i in 0 until output.length()) {
            val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val item = content.optJSONObject(j) ?: continue
                item.optString("text").takeIf(String::isNotBlank)?.let(out::append)
            }
        }
        return out.toString()
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
        private const val MAX_PROMPT_CHARS = 180_000
        private const val MAX_RESPONSE_CHARS = 1_000_000
    }
}
