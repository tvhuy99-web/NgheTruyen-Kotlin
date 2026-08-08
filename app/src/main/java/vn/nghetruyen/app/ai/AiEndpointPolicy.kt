package vn.nghetruyen.app.ai

object AiEndpointPolicy {
    fun validate(raw: String): Result<String> = runCatching {
        val value = raw.trim()
        require(value.isNotBlank()) { "Chưa nhập URL API." }
        require(value.startsWith("https://", ignoreCase = true)) { "URL API phải dùng HTTPS." }
        value
    }
}
