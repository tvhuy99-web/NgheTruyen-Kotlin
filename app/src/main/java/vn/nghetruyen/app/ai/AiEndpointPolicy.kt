package vn.nghetruyen.app.ai

import java.net.URI
import java.net.InetAddress

object AiEndpointPolicy {
    fun validate(raw: String): Result<String> = runCatching {
        val uri = URI(raw.trim())
        require(uri.scheme.equals("https", ignoreCase = true)) { "Endpoint AI phải dùng HTTPS." }
        require(uri.userInfo == null && uri.fragment == null) { "Endpoint AI không được chứa thông tin đăng nhập hoặc fragment." }
        val host = uri.host?.lowercase()?.trimEnd('.') ?: error("Endpoint AI thiếu hostname.")
        require(host != "localhost" && !host.endsWith(".local")) { "Không cho phép endpoint cục bộ." }
        val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
        require(addresses.none { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress }) {
            "Không cho phép endpoint thuộc mạng nội bộ."
        }
        require(uri.path.isNotBlank() && uri.path != "/") { "Endpoint AI phải trỏ tới API chat completions." }
        uri.toString()
    }
}
