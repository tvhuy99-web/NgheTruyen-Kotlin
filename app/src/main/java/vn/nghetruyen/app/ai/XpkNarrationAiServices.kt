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
import vn.nghetruyen.app.data.local.VoiceRoleEntity
import vn.nghetruyen.app.data.repository.LibraryRepository
import vn.nghetruyen.app.data.settings.AiOnlineSettings
import vn.nghetruyen.app.data.settings.AiProvider
import vn.nghetruyen.app.data.settings.SettingsRepository
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Narration-only AI path that mirrors the XPK voice-cast contract without disturbing translation or
 * VietPhrase traffic handled by [OnlineAiServices].
 */
class XpkNarrationAiServices(
    private val settingsRepository: SettingsRepository,
    private val credentialStore: AiCredentialStore,
    private val requestGovernor: AiRequestGovernor,
    private val libraryRepository: LibraryRepository,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .dns(AiPublicDns)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    suspend fun planVoiceCast(
        storyId: String,
        chapterId: String,
        chapterTitle: String,
        rawText: String,
    ): AppResult<VoiceCastPlan> {
        val request = NarrationPlanRequest(
            storyId = storyId,
            chapterId = chapterId,
            chapterTitle = chapterTitle,
            rawText = rawText,
            includeVoiceCast = true,
            includeSceneMusic = false,
        )
        return when (val result = planNarration(request)) {
            is AppResult.Failure -> result
            is AppResult.Success -> AppResult.Success(result.value.voiceCast)
        }
    }

    suspend fun planNarration(request: NarrationPlanRequest): AppResult<NarrationPlan> {
        val rawText = request.rawText.trim()
        if (rawText.isBlank()) return failure("AI_EMPTY_INPUT", "Chương không có nội dung để lập kế hoạch kể chuyện.")
        if (rawText.length > MAX_PLAN_CHARS) return failure("AI_INPUT_TOO_LARGE", "Chương quá dài để lập kế hoạch kể chuyện trong một lượt.")
        if (!request.includeVoiceCast && !request.includeSceneMusic) return failure("AI_PLAN_EMPTY", "Không có hạng mục kể chuyện nào được yêu cầu.")
        if (request.includeSceneMusic && request.tracks.isEmpty()) return failure("AI_TRACKS_EMPTY", "Chưa có tệp nhạc cảnh đang bật.")

        val config = resolveConfiguration(request.storyId)
        validateConfiguration(config)?.let { return it }
        val profiles = if (request.includeVoiceCast) {
            libraryRepository.listEffectiveVoiceRoles(
                request.storyId,
                settingsRepository.snapshot().autoVoiceCastEnabled,
            ).filter(VoiceRoleEntity::enabled).take(MAX_VOICE_PROFILES)
        } else emptyList()

        if (request.includeVoiceCast) {
            if (profiles.none(VoiceRoleEntity::isNarrator)) {
                return failure("VOICE_NARRATOR_MISSING", "Chưa có hồ sơ Người kể chuyện hợp lệ.")
            }
            if (profiles.none { !it.isNarrator }) {
                return failure("VOICE_PROFILES_INSUFFICIENT", "Cần ít nhất một hồ sơ giọng nhân vật ngoài Người kể chuyện để phân vai.")
            }
        }

        val storyNote = StoryVoiceCastReferenceCodec.userNote(config.voiceCastNote)
        val customGuidance = if (config.useCustomVoiceCastPrompt) {
            renderCustomGuidance(config.voiceCastPrompt, request, storyNote)
        } else ""
        val bundle = XpkVoiceCastPrompt.build(
            title = request.chapterTitle,
            body = rawText,
            profiles = profiles,
            storyNote = storyNote,
            expressiveAdjustment = config.expressiveAdjustment,
            speedLimitPct = config.expressionSpeedLimitPct,
            pitchLimitPct = config.expressionPitchLimitPct,
            volumeLimitPct = config.expressionVolumeLimitPct,
            expressionPrompt = config.expressionPrompt,
            customGuidance = customGuidance,
            includeVoiceCast = request.includeVoiceCast,
            includeSceneMusic = request.includeSceneMusic,
            tracks = request.tracks,
        )
        if (request.includeVoiceCast && bundle.dialogueIds.isEmpty() && !request.includeSceneMusic) {
            return AppResult.Success(
                NarrationPlan(
                    voiceCast = VoiceCastPlan(configuredRoles(profiles), emptyList()),
                    musicCues = emptyList(),
                ),
            )
        }
        if (bundle.prompt.length > MAX_PROMPT_CHARS) return failure("AI_INPUT_TOO_LARGE", "Bản chép phân vai vượt giới hạn gửi AI.")

        return when (val response = chat(bundle.prompt, config)) {
            is AppResult.Failure -> response
            is AppResult.Success -> runCatching {
                val parsed = AiLineProtocol.parseXpkNarration(
                    response.value,
                    AiLineProtocol.XpkParseOptions(
                        validDialogueIds = bundle.dialogueIds,
                        validUnitIds = bundle.unitIds,
                        validVoiceIds = bundle.voiceIds,
                        validTrackIds = request.tracks.map(SceneMusicTrackOption::id),
                        includeVoiceCast = request.includeVoiceCast,
                        includeSceneMusic = request.includeSceneMusic,
                        speedLimitPct = config.expressionSpeedLimitPct.toFloat(),
                        pitchLimitPct = config.expressionPitchLimitPct.toFloat(),
                        volumeLimitPct = config.expressionVolumeLimitPct.toFloat(),
                        expressiveAdjustment = config.expressiveAdjustment,
                    ),
                )
                val roleByPromptId = profiles.associateBy(XpkVoiceCastPrompt::promptVoiceId)
                val normalizedVoice = if (request.includeVoiceCast) {
                    parsed.voiceCast.copy(
                        roles = configuredRoles(profiles),
                        assignments = parsed.voiceCast.assignments.map { assignment ->
                            assignment.copy(character = roleByPromptId[assignment.voiceId]?.roleName.orEmpty())
                        },
                    )
                } else parsed.voiceCast
                parsed.copy(voiceCast = normalizedVoice)
            }.fold(
                { AppResult.Success(it) },
                { failure("AI_BAD_RESPONSE", it.message ?: "Kết quả kế hoạch kể chuyện không hợp lệ.", it) },
            )
        }
    }

    private fun configuredRoles(profiles: List<VoiceRoleEntity>): List<VoiceRole> = profiles.map { role ->
        VoiceRole(
            character = role.roleName,
            aliases = role.aliasesCsv.split(',').map(String::trim).filter(String::isNotBlank).take(20),
            expression = role.expression,
        )
    }

    private fun renderCustomGuidance(
        template: String,
        request: NarrationPlanRequest,
        storyNote: String,
    ): String {
        if (template.isBlank()) return ""
        return template
            .replace("{{CHAPTER_TEXT}}", "Xem BẢN CHÉP UNIT/DIALOGUE do ứng dụng cung cấp; không dùng văn bản tự chia lại.")
            .replace("{{CHAPTER_TITLE}}", request.chapterTitle)
            .replace("{{STORY_NOTE}}", storyNote)
            .replace("{{EXISTING_ROLES}}", "Chỉ dùng DANH SÁCH GIỌNG ĐƯỢC PHÉP SỬ DỤNG trong prompt chính.")
            .replace("{{EXPRESSION_RULES}}", "Tuân theo giới hạn và quy tắc ba phần trăm trong prompt chính.")
            .take(MAX_CUSTOM_GUIDANCE_CHARS)
    }

    private suspend fun resolveConfiguration(storyId: String): EffectiveConfig {
        val global = settingsRepository.snapshot().aiOnline
        val profile = storyId.takeIf(String::isNotBlank)?.let { libraryRepository.getStoryAiProfile(it) }
        val provider = if (profile?.overrideProvider == true) {
            runCatching { AiProvider.valueOf(profile.provider) }.getOrDefault(global.provider)
        } else global.provider
        return EffectiveConfig(
            global = global,
            provider = provider,
            endpoint = profile?.endpoint?.takeIf { profile.overrideProvider && it.isNotBlank() } ?: global.endpoint,
            model = profile?.model?.takeIf { profile.overrideProvider && it.isNotBlank() } ?: global.model,
            temperature = profile?.temperature?.takeIf { it in 0f..2f } ?: global.temperature,
            useCustomVoiceCastPrompt = profile?.useCustomVoiceCastPrompt == true,
            voiceCastPrompt = profile?.voiceCastPrompt.orEmpty(),
            voiceCastNote = profile?.voiceCastNote.orEmpty(),
            expressiveAdjustment = profile?.expressiveAdjustment ?: true,
            expressionPrompt = profile?.expressionPrompt.orEmpty(),
            expressionSpeedLimitPct = profile?.expressionSpeedLimitPct?.coerceIn(0, 100) ?: 10,
            expressionPitchLimitPct = profile?.expressionPitchLimitPct?.coerceIn(0, 100) ?: 10,
            expressionVolumeLimitPct = profile?.expressionVolumeLimitPct?.coerceIn(0, 100) ?: 10,
        )
    }

    private fun validateConfiguration(config: EffectiveConfig): AppResult.Failure? {
        if (!config.global.enabled) return failure("AI_DISABLED", "AI online đang tắt.")
        if (config.provider == AiProvider.OPENAI_COMPATIBLE) {
            AiEndpointPolicy.validate(config.endpoint).exceptionOrNull()?.let {
                return failure("AI_ENDPOINT_INVALID", it.message ?: "Endpoint AI không hợp lệ.")
            }
        }
        if (config.model.isBlank()) return failure("AI_MODEL_MISSING", "Chưa cấu hình model AI.")
        return null
    }

    private suspend fun chat(prompt: String, config: EffectiveConfig): AppResult<String> = withContext(Dispatchers.IO) {
        val apiKey = when (config.provider) {
            AiProvider.GEMINI -> credentialStore.apiKey(config.provider)?.trim()?.takeIf(String::isNotBlank)
                ?: return@withContext failure("AI_KEY_MISSING", "Chưa lưu API key cho Gemini.")
            AiProvider.OPENAI_COMPATIBLE -> credentialStore.apiKey(config.provider)?.trim().orEmpty()
        }
        val permit = when (val reserved = requestGovernor.reserve(prompt.length)) {
            is AppResult.Failure -> return@withContext reserved
            is AppResult.Success -> reserved.value
        }
        val requests = runCatching { buildRequests(config, apiKey, prompt) }.getOrElse {
            requestGovernor.finish(permit, 0, 0, "AI_CONFIGURATION_INVALID")
            return@withContext failure("AI_CONFIGURATION_INVALID", it.message ?: "Cấu hình AI không hợp lệ.", it)
        }
        var lastFailure: AppResult.Failure? = null
        requests.forEachIndexed { index, requestData ->
            try {
                val request = Request.Builder()
                    .url(requestData.url)
                    .header("Accept", "application/json")
                    .apply { requestData.headers.forEach { (name, value) -> header(name, value) } }
                    .post(requestData.body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                val response = client.newCall(request).execute()
                response.use {
                    if (response.isRedirect) {
                        lastFailure = failure("AI_REDIRECT_BLOCKED", "Endpoint AI trả redirect; yêu cầu URL API trực tiếp.")
                        return@forEachIndexed
                    }
                    val raw = response.body?.charStream()?.use { reader ->
                        buildString {
                            val buffer = CharArray(8_192)
                            while (length <= MAX_RESPONSE_CHARS) {
                                val count = reader.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_CHARS + 1 - length))
                                if (count < 0) break
                                append(buffer, 0, count)
                            }
                        }
                    }.orEmpty()
                    if (raw.length > MAX_RESPONSE_CHARS) {
                        lastFailure = failure("AI_RESPONSE_TOO_LARGE", "Phản hồi AI vượt giới hạn an toàn.")
                        return@forEachIndexed
                    }
                    if (!response.isSuccessful) {
                        lastFailure = failure("AI_HTTP_${response.code}", extractError(raw)?.take(400) ?: "Nhà cung cấp AI trả lỗi HTTP ${response.code}.")
                        val canFallback = config.provider == AiProvider.OPENAI_COMPATIBLE &&
                            response.code in OPENAI_ENDPOINT_FALLBACK_HTTP_CODES && index < requests.lastIndex
                        if (canFallback) return@forEachIndexed
                        requestGovernor.finish(permit, 0, 0, lastFailure?.code)
                        return@withContext lastFailure!!
                    }
                    val content = runCatching { extractContent(config.provider, raw) }.getOrElse {
                        lastFailure = failure("AI_BAD_RESPONSE", it.message ?: "Không đọc được phản hồi AI.", it)
                        ""
                    }
                    if (content.isNotBlank()) {
                        requestGovernor.finish(permit, content.length, 0, null)
                        return@withContext AppResult.Success(content.trim())
                    }
                }
            } catch (error: IOException) {
                lastFailure = failure("AI_NETWORK_ERROR", error.message ?: "Không kết nối được nhà cung cấp AI.", error)
            } catch (error: Exception) {
                lastFailure = failure("AI_NETWORK_ERROR", error.message ?: "Không kết nối được nhà cung cấp AI.", error)
            }
        }
        val result = lastFailure ?: failure("AI_EMPTY_RESPONSE", "AI trả về nội dung trống.")
        requestGovernor.finish(permit, 0, 0, result.code)
        result
    }

    private fun buildRequests(config: EffectiveConfig, apiKey: String, prompt: String): List<AiRequest> = when (config.provider) {
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
                        .put("maxOutputTokens", MAX_OUTPUT_TOKENS)
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
                        .put("response_format", JSONObject().put("type", "json_object"))
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
        AiProvider.OPENAI_COMPATIBLE -> {
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
            text.takeIf(String::isNotBlank) ?: error("OpenAI-compatible API không trả nội dung")
        }
    }

    private fun extractError(raw: String): String? = runCatching {
        JSONObject(raw).optJSONObject("error")?.optString("message")?.takeIf(String::isNotBlank)
    }.getOrNull()

    private data class EffectiveConfig(
        val global: AiOnlineSettings,
        val provider: AiProvider,
        val endpoint: String,
        val model: String,
        val temperature: Float,
        val useCustomVoiceCastPrompt: Boolean,
        val voiceCastPrompt: String,
        val voiceCastNote: String,
        val expressiveAdjustment: Boolean,
        val expressionPrompt: String,
        val expressionSpeedLimitPct: Int,
        val expressionPitchLimitPct: Int,
        val expressionVolumeLimitPct: Int,
    )

    private data class AiRequest(
        val url: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private fun failure(code: String, message: String, cause: Throwable? = null) = AppResult.Failure(code, message, cause)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta"
        private val GEMINI_MODEL_PATTERN = Regex("^[A-Za-z0-9._-]+$")
        private val OPENAI_ENDPOINT_FALLBACK_HTTP_CODES = setOf(404, 405)
        private const val MAX_PLAN_CHARS = 60_000
        private const val MAX_PROMPT_CHARS = 160_000
        private const val MAX_RESPONSE_CHARS = 2_000_000
        private const val MAX_CUSTOM_GUIDANCE_CHARS = 20_000
        private const val MAX_OUTPUT_TOKENS = 8_000
        private const val MAX_VOICE_PROFILES = 40
    }
}
