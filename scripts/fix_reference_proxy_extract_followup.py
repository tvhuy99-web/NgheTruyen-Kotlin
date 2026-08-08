from pathlib import Path

path = Path('app/src/main/java/vn/nghetruyen/app/ai/OnlineAiServices.kt')
text = path.read_text(encoding='utf-8')
start = text.index('    private fun extractContent(provider: AiProvider, raw: String): String = when (provider) {\n')
end = text.index('    private fun extractError(provider: AiProvider, raw: String): String? = runCatching {\n', start)
replacement = r'''    private fun extractContent(provider: AiProvider, raw: String): String = when (provider) {
        AiProvider.OPENAI_COMPATIBLE -> extractOpenAiContent(raw)
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

    private fun extractOpenAiContent(raw: String): String {
        val root = JSONObject(raw)
        root.optJSONObject("error")?.let { errorObject ->
            errorObject.optString("message").takeIf(String::isNotBlank)
                ?: errorObject.optString("code").takeIf(String::isNotBlank)
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
            if (text.isNotBlank()) return text
        }

        when (val status = root.optString("status")) {
            "incomplete" -> {
                val reason = root.optJSONObject("incomplete_details")?.optString("reason").orEmpty().ifBlank { "incomplete" }
                error(if (reason == "max_output_tokens") "Phản hồi bị cắt vì đạt giới hạn đầu ra của model" else "Responses API chưa hoàn tất: $reason")
            }
            "failed" -> error("Responses API báo thất bại")
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

'''
text = text[:start] + replacement + text[end:]
text = text.replace('append(base.removeSuffix("/responses") + "/chat/completions")', 'append(base.dropLast("/responses".length) + "/chat/completions")')
text = text.replace('append(base.removeSuffix("/chat/completions") + "/responses")', 'append(base.dropLast("/chat/completions".length) + "/responses")')
path.write_text(text, encoding='utf-8')
print('OpenAI-compatible response parser and case-insensitive URL parity fixed')
