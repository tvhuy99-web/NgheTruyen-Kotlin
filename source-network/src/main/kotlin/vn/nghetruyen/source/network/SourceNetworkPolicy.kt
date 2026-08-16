package vn.nghetruyen.source.network

import vn.nghetruyen.source.api.SourceManifest
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale

object SourceOriginPolicy {
    fun requireInitialUrl(manifest: SourceManifest, rawUrl: String): URI = requireAllowed(
        manifest = manifest,
        rawUrl = rawUrl,
        allowedOrigins = manifest.origins,
        error = "SOURCE_NETWORK_ORIGIN_DENIED",
    )

    fun requireRedirectUrl(manifest: SourceManifest, rawUrl: String): URI = requireAllowed(
        manifest = manifest,
        rawUrl = rawUrl,
        allowedOrigins = manifest.origins + manifest.redirectOrigins,
        error = "SOURCE_NETWORK_REDIRECT_ORIGIN_DENIED",
    )

    fun originOf(uri: URI): String = buildString {
        val scheme = uri.scheme.lowercase(Locale.ROOT)
        append(scheme).append("://").append(uri.host.lowercase(Locale.ROOT))
        val defaultPort = defaultPort(scheme)
        if (uri.port != -1 && uri.port != defaultPort) append(':').append(uri.port)
    }

    fun matchesOrigin(uri: URI, declared: String): Boolean {
        val declaredUri = parseDeclaredOrigin(declared) ?: return false
        val requestScheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (requestScheme != declaredUri.uri.scheme.lowercase(Locale.ROOT)) return false
        val requestPort = if (uri.port == -1) defaultPort(requestScheme) else uri.port
        val allowedPort = if (declaredUri.uri.port == -1) defaultPort(requestScheme) else declaredUri.uri.port
        if (requestPort != allowedPort) return false
        val requestHost = uri.host?.lowercase(Locale.ROOT) ?: return false
        val allowedHost = declaredUri.uri.host.lowercase(Locale.ROOT)
        return if (declaredUri.wildcard) requestHost.endsWith(".$allowedHost") && requestHost != allowedHost
        else requestHost == allowedHost
    }

    private fun requireAllowed(
        manifest: SourceManifest,
        rawUrl: String,
        allowedOrigins: Set<String>,
        error: String,
    ): URI {
        require(rawUrl.length in 1..4096) { "SOURCE_NETWORK_URL_INVALID" }
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: error("SOURCE_NETWORK_URL_INVALID")
        val network = manifest.capabilities.network
        val publicInternet = network?.publicInternet == true
        val cleartext = publicInternet && network.allowCleartext
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "https" || (cleartext && scheme == "http")) {
            if (scheme == "http") "SOURCE_NETWORK_CLEARTEXT_DENIED" else "SOURCE_NETWORK_HTTPS_REQUIRED"
        }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "SOURCE_NETWORK_URL_INVALID" }
        require(uri.port == -1 || uri.port in 1..65535) { "SOURCE_NETWORK_URL_INVALID" }
        if (!publicInternet) {
            require(allowedOrigins.any { matchesOrigin(uri, it) }) { error }
        }
        return uri
    }

    private data class DeclaredOrigin(val uri: URI, val wildcard: Boolean)

    private fun parseDeclaredOrigin(raw: String): DeclaredOrigin? {
        val wildcard = raw.startsWith("https://*.", true) || raw.startsWith("http://*.", true)
        val normalized = if (wildcard) raw.replaceFirst("://*.", "://", ignoreCase = true) else raw
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        return DeclaredOrigin(uri, wildcard)
    }

    private fun defaultPort(scheme: String): Int = when (scheme.lowercase(Locale.ROOT)) {
        "http" -> 80
        "https" -> 443
        else -> -1
    }
}

object PublicAddressPolicy {
    fun requirePublic(addresses: List<InetAddress>): List<InetAddress> {
        require(addresses.isNotEmpty()) { "SOURCE_NETWORK_DNS_EMPTY" }
        addresses.forEach { address -> require(isPublic(address)) { "SOURCE_NETWORK_PRIVATE_ADDRESS:${address.hostAddress}" } }
        return addresses
    }

    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address
        return when (address) {
            is Inet4Address -> isPublicV4(bytes)
            is Inet6Address -> isPublicV6(bytes)
            else -> false
        }
    }

    private fun isPublicV4(raw: ByteArray): Boolean {
        val a = raw[0].toInt() and 0xff
        val b = raw[1].toInt() and 0xff
        val c = raw[2].toInt() and 0xff
        return when {
            a == 0 || a == 10 || a == 127 -> false
            a == 100 && b in 64..127 -> false
            a == 169 && b == 254 -> false
            a == 172 && b in 16..31 -> false
            a == 192 && b == 0 && c == 0 -> false
            a == 192 && b == 0 && c == 2 -> false
            a == 192 && b == 88 && c == 99 -> false
            a == 192 && b == 168 -> false
            a == 198 && b in 18..19 -> false
            a == 198 && b == 51 && c == 100 -> false
            a == 203 && b == 0 && c == 113 -> false
            a >= 224 -> false
            else -> true
        }
    }

    private fun isPublicV6(raw: ByteArray): Boolean {
        if (raw.size != 16) return false
        val first = raw[0].toInt() and 0xff
        val second = raw[1].toInt() and 0xff
        if (raw.all { it == 0.toByte() }) return false
        if (raw.dropLast(1).all { it == 0.toByte() } && raw.last() == 1.toByte()) return false
        if (first and 0xfe == 0xfc) return false // fc00::/7 unique-local
        if (first == 0xfe && second and 0xc0 == 0x80) return false // fe80::/10 link-local
        if (first == 0xff) return false // multicast
        if (raw.take(10).all { it == 0.toByte() } && raw[10] == 0xff.toByte() && raw[11] == 0xff.toByte()) {
            return isPublicV4(raw.copyOfRange(12, 16))
        }
        // Documentation prefix 2001:db8::/32.
        if (first == 0x20 && second == 0x01 && (raw[2].toInt() and 0xff) == 0x0d && (raw[3].toInt() and 0xff) == 0xb8) return false
        return true
    }
}

internal object SourceHeaderPolicy {
    private val headerName = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,100}$")
    private val forbidden = setOf("host", "content-length", "connection", "transfer-encoding", "cookie", "set-cookie")

    fun validate(headers: Map<String, String>) {
        require(headers.size <= 64) { "SOURCE_NETWORK_TOO_MANY_HEADERS" }
        headers.forEach { (name, value) ->
            require(headerName.matches(name) && name.lowercase(Locale.ROOT) !in forbidden) { "SOURCE_NETWORK_HEADER_DENIED:$name" }
            require(value.length <= 8192 && value.none { it == '\r' || it == '\n' || it == '\u0000' }) {
                "SOURCE_NETWORK_HEADER_INVALID:$name"
            }
        }
    }
}
