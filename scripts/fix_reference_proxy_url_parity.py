from pathlib import Path
import re

root = Path('.')
policy = root / 'app/src/main/java/vn/nghetruyen/app/ai/AiEndpointPolicy.kt'
online = root / 'app/src/main/java/vn/nghetruyen/app/ai/OnlineAiServices.kt'
gradle = root / 'app/build.gradle.kts'

policy.write_text('''package vn.nghetruyen.app.ai\n\nobject AiEndpointPolicy {\n    fun validate(raw: String): Result<String> = runCatching {\n        val value = raw.trim()\n        require(value.isNotBlank()) { "Chưa nhập URL API." }\n        require(value.startsWith("https://", ignoreCase = true)) { "URL API phải dùng HTTPS." }\n        value\n    }\n}\n''', encoding='utf-8')

text = online.read_text(encoding='utf-8')

chat_start = text.index('    private suspend fun chat(\n')
build_start = text.index('    private fun buildRequest(\n', chat_start)
extract_start = text.index('    private fun extractContent(provider: AiProvider, raw: String): String = when (provider) {\n', build_start)
extract_error_start = text.index('    private fun extractError(provider: AiProvider, raw: String): String? = runCatching {\n', extract_start)

new_chat_and_build = r'''    private suspend fun chat(
        prompt: String,
        maxOutputTokens: Int,
        config: EffectiveAiConfiguration,
        jsonMode: Boolean,
    ): AppResult<String> = withContext(Dispatchers.IO) {
        val validation = validateConfiguration(config)
        if (validation != null) return@withContext validation
        val apiKey = when (config.provider) {
            AiProvider.GEMINI -> credentialStore.apiKey(config.provider)?.trim()?.takeIf(String::isNotBlank)
                ?: return@withContext failure("AI_KEY_MISSING", "Chưa lưu API key cho ${providerLabel(config.provider)}.")
            AiProvider.OPENAI_COMPATIBLE -> credentialStore.apiKey(config.provider)?.trim().orEmpty()
        }
        val permit = when (val reserved = requestGovernor.reserve(prompt.length)) {
            is AppResult.Failure -> return@withContext reserved
            is AppResult.Success -> reserved.value
        }
        val requestData = runCatching { buildRequest(config, apiKey, prompt, maxOutputTokens, jsonMode) }
            .getOrElse {
                requestGovernor.finish(permit, 0, 0, "AI_CONFIGURATION_INVALID")
                return@withContext failure("AI_CONFIGURATION_INVALID", it.message ?: "Cấu hình AI không hợp lệ.")
            }
        val candidates = if (config.provider == AiProvider.OPENAI_COMPATIBLE) {
            listOf(requestData) + requestData.alternatives
        } else {
            listOf(requestData)
        }
        var retries = 0
        var lastFailure: AppResult.Failure? = null
        for (attempt in 0..permit.maxRetries) {
            var shouldRetry = false
            var retryAfterMillis: Long? = null
            candidateLoop@ for ((candidateIndex, candidateData) in candidates.withIndex()) {
                val builder = Request.Builder()
                    .url(candidateData.url)
                    .header("Accept", "application/json")
                candidateData.headers.forEach { (name, value) -> builder.header(name, value) }
                val request = builder.post(candidateData.body.toRequestBody(JSON_MEDIA_TYPE)).build()
                var fallbackToNext = false
                try {
                    val call = client.newCall(request)
                    call.timeout().timeout(config.global.timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
                    val response = call.execute()
                    response.use {
                        if (response.isRedirect) {
                            lastFailure = failure("AI_REDIRECT_BLOCKED", "Endpoint AI trả redirect; yêu cầu URL API trực tiếp.")
                        } else {
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
                            } else if (response.isSuccessful) {
                                val content = runCatching { extractContent(config.provider, raw) }
                                    .getOrElse {
                                        lastFailure = failure("AI_BAD_RESPONSE", it.message ?: "Không đọc được nội dung phản hồi AI.")
                                        ""
                                    }
                                if (content.isNotBlank()) {
                                    requestGovernor.finish(permit, content.length, retries, null)
                                    return@withContext AppResult.Success(content.trim())
                                }
                                if (lastFailure == null) lastFailure = failure("AI_EMPTY_RESPONSE", "AI trả về nội dung trống.")
                            } else {
                                val detail = extractError(config.provider, raw)
                                lastFailure = failure("AI_HTTP_${response.code}", detail?.take(400) ?: "Nhà cung cấp AI trả lỗi HTTP ${response.code}.")
                                fallbackToNext = config.provider == AiProvider.OPENAI_COMPATIBLE &&
                                    response.code in OPENAI_ENDPOINT_FALLBACK_HTTP_CODES &&
                                    candidateIndex < candidates.lastIndex
                                if (!fallbackToNext) {
                                    shouldRetry = attempt < permit.maxRetries && response.code in RETRYABLE_HTTP_CODES
                                    retryAfterMillis = response.header("Retry-After")?.toLongOrNull()?.times(1_000L)
                                }
                            }
                        }
                    }
                    if (fallbackToNext) continue@candidateLoop
                } catch (error: IOException) {
                    lastFailure = failure("AI_NETWORK_ERROR", error.message ?: "Không kết nối được nhà cung cấp AI.", error)
                    if (attempt < permit.maxRetries) {
                        shouldRetry = true
                    }
                } catch (error: Exception) {
                    lastFailure = failure("AI_NETWORK_ERROR", error.message ?: "Không kết nối được nhà cung cấp AI.", error)
                }
                break@candidateLoop
            }
            if (shouldRetry) {
                retries += 1
                delay(retryDelayMillis(permit.retryBaseDelayMillis, attempt, retryAfterMillis))
                continue
            }
            break
        }
        val result = lastFailure ?: failure("AI_UNKNOWN_ERROR", "Yêu cầu AI thất bại.")
        requestGovernor.finish(permit, 0, retries, result.code)
        result
    }

    private fun openAiCandidateUrls(value: String): List<String> {
        val original = value.trim()
        if (original.isBlank()) return emptyList()
        val tailIndex = original.indexOfFirst { it == '?' || it == '#' }
        val path = if (tailIndex >= 0) original.substring(0, tailIndex) else original
        val tail = if (tailIndex >= 0) original.substring(tailIndex) else ""
        val base = path.trimEnd('/')
        if (base.isBlank()) return emptyList()
        val lower = base.lowercase()
        val out = mutableListOf<String>()
        val seen = linkedSetOf<String>()
        fun append(candidate: String) {
            val clean = candidate.trimEnd('/')
            if (clean.isBlank()) return
            val url = clean + tail
            if (seen.add(url)) out += url
        }
        append(base)
        when {
            lower.endsWith("/responses") -> {
                append(base.removeSuffix("/responses") + "/chat/completions")
            }
            lower.endsWith("/chat/completions") -> {
                append(base.removeSuffix("/chat/completions") + "/responses")
            }
            else -> {
                append("$base/chat/completions")
                append("$base/responses")
                if (!lower.endsWith("/v1")) {
                    append("$base/v1/chat/completions")
                    append("$base/v1/responses")
                }
            }
        }
        return out
    }

    private fun buildRequest(
        config: EffectiveAiConfiguration,
        apiKey: String,
        prompt: String,
        maxOutputTokens: Int,
        jsonMode: Boolean,
    ): AiHttpRequest = when (config.provider) {
        AiProvider.OPENAI_COMPATIBLE -> {
            val urls = openAiCandidateUrls(config.endpoint)
            require(urls.isNotEmpty()) { "Chưa nhập URL API" }
            val headers = buildMap {
                if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey")
            }
            val requests = urls.map { url ->
                val path = url.substringBefore('?').substringBefore('#').trimEnd('/').lowercase()
                val body = if (path.endsWith("/responses")) {
                    JSONObject()
                        .put("model", config.model)
                        .put("input", prompt)
                        .toString()
                } else {
                    JSONObject()
                        .put("model", config.model)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                        .put("temperature", config.temperature.toDouble())
                        .toString()
                }
                AiHttpRequest(
                    url = url,
                    headers = headers,
                    body = body,
                )
            }
            requests.first().copy(alternatives = requests.drop(1))
        }
        AiProvider.GEMINI -> {
            val geminiModel = config.model.removePrefix("models/").trim()
            require(GEMINI_MODEL_PATTERN.matches(geminiModel)) { "Tên model Gemini chứa ký tự không hợp lệ." }
            val generation = JSONObject()
                .put("temperature", config.temperature.toDouble())
                .put("maxOutputTokens", maxOutputTokens)
            if (jsonMode) generation.put("responseMimeType", "application/json")
            val body = JSONObject()
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", prompt))),
                    ),
                )
                .put("generationConfig", generation)
                .toString()
            AiHttpRequest(
                url = "$GEMINI_API_BASE/models/$geminiModel:generateContent",
                headers = mapOf("x-goog-api-key" to apiKey),
                body = body,
            )
        }
    }

'''

text = text[:chat_start] + new_chat_and_build + text[extract_start:]

extract_start = text.index('    private fun extractContent(provider: AiProvider, raw: String): String = when (provider) {\n')
extract_error_start = text.index('    private fun extractError(provider: AiProvider, raw: String): String? = runCatching {\n', extract_start)
new_extract = r'''    private fun extractContent(provider: AiProvider, raw: String): String = when (provider) {
        AiProvider.OPENAI_COMPATIBLE -> {
            val root = JSONObject(raw)
            root.optJSONObject("error")?.let { error ->
                error.optString("message").takeIf(String::isNotBlank)
                    ?: error.optString("code").takeIf(String::isNotBlank)
            }?.let { error(it) }

            root.optJSONArray("choices")?.optJSONObject(0)?.let { choice ->
                val finishReason = choice.optString("finish_reason")
                require(finishReason.isBlank() || finishReason == "stop") {
                    if (finishReason == "length") "Phản hồi bị cắt vì đạt giới hạn đầu ra của model"
                    else "Model kết thúc phản hồi với lý do: $finishReason"
                }
                val content = choice.optJSONObject("message")?.opt("content")
                val text = when (content) {
                    is String -> content
                    is JSONArray -> buildString {
                        for (index in 0 until content.length()) {
                            content.optJSONObject(index)?.optString("text")?.takeIf(String::isNotBlank)?.let {
                                if (isNotEmpty()) append('\n')
                                append(it)
                            }
                        }
                    }
                    else -> ""
                }
                if (text.isNotBlank()) return@when text
            }

            when (val status = root.optString("status")) {
                "incomplete" -> {
                    val reason = root.optJSONObject("incomplete_details")?.optString("reason").orEmpty().ifBlank { "incomplete" }
                    error(if (reason == "max_output_tokens") "Phản hồi bị cắt vì đạt giới hạn đầu ra của model" else "Responses API chưa hoàn tất: $reason")
                }
                "failed" -> error("Responses API báo thất bại")
            }
            root.optString("output_text").takeIf(String::isNotBlank)?.let { return@when it }
            val output = root.optJSONArray("output") ?: JSONArray()
            buildString {
                for (outputIndex in 0 until output.length()) {
                    val parts = output.optJSONObject(outputIndex)?.optJSONArray("content") ?: continue
                    for (partIndex in 0 until parts.length()) {
                        parts.optJSONObject(partIndex)?.optString("text")?.takeIf(String::isNotBlank)?.let {
                            if (isNotEmpty()) append('\n')
                            append(it)
                        }
                    }
                }
            }.takeIf(String::isNotBlank) ?: error("OpenAI-compatible API không trả nội dung")
        }
        AiProvider.GEMINI -> {
            val root = JSONObject(raw)
            root.optJSONObject("error")?.optString("message")?.takeIf(String::isNotBlank)?.let { error(it) }
            val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
                ?: error(root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty().ifBlank { "Gemini không trả candidate." })
            val finishReason = candidate.optString("finishReason")
            require(finishReason.isBlank() || finishReason == "STOP") {
                if (finishReason == "MAX_TOKENS") "Phản hồi Gemini bị cắt vì đạt giới hạn đầu ra." else "Gemini kết thúc với lý do $finishReason."
            }
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
    }

'''
text = text[:extract_start] + new_extract + text[extract_error_start:]

old_request = '''    private data class AiHttpRequest(\n        val url: String,\n        val headers: Map<String, String>,\n        val body: String,\n    )\n'''
new_request = '''    private data class AiHttpRequest(\n        val url: String,\n        val headers: Map<String, String>,\n        val body: String,\n        val alternatives: List<AiHttpRequest> = emptyList(),\n    )\n'''
if old_request not in text:
    raise SystemExit('AiHttpRequest block not found')
text = text.replace(old_request, new_request, 1)

needle = '        private val RETRYABLE_HTTP_CODES = setOf(408, 425, 429, 500, 502, 503, 504)\n'
replacement = '        private val OPENAI_ENDPOINT_FALLBACK_HTTP_CODES = setOf(404, 405)\n' + needle
if needle not in text:
    raise SystemExit('retry codes block not found')
text = text.replace(needle, replacement, 1)

online.write_text(text, encoding='utf-8')

gradle_text = gradle.read_text(encoding='utf-8')
if 'versionCode = 31' not in gradle_text:
    raise SystemExit('expected versionCode 31 not found')
gradle.write_text(gradle_text.replace('versionCode = 31', 'versionCode = 32', 1), encoding='utf-8')

print('reference proxy URL parity fix applied')
