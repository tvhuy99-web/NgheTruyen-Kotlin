package vn.nghetruyen.app.sourceplatform

import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.network.PublicAddressPolicy
import vn.nghetruyen.source.network.SourceOriginPolicy
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale










internal class BrowserNavigationPolicy(
    private val resolver: (String) -> List<InetAddress>,
) {
    sealed interface Decision {
        val shape: UrlShape?

        data class Allowed(
            val transportUrl: String,
            val transportIdentity: String,
            val host: String,
            val resolvedAddressKinds: Set<String>,
            val resolutionSource: String,
            val decisionThread: String,
            override val shape: UrlShape,
        ) : Decision

        data class NeedsDns(
            val transportUrl: String,
            val transportIdentity: String,
            val host: String,
            override val shape: UrlShape,
        ) : Decision

        data class Denied(
            val code: String,
            val causeType: String?,
            val decisionThread: String,
            override val shape: UrlShape?,
        ) : Decision
    }

    data class UrlShape(
        val scheme: String,
        val host: String,
        val port: Int,
        val hasQuery: Boolean,
        val hasFragment: Boolean,
    )

    fun preflightInitial(manifest: SourceManifest, rawUrl: String): Decision =
        preflight(manifest, rawUrl, initial = true)

    fun preflightRedirect(manifest: SourceManifest, rawUrl: String): Decision =
        preflight(manifest, rawUrl, initial = false)

     
    fun evaluateRedirect(
        manifest: SourceManifest,
        rawUrl: String,
        approvedHosts: Set<String>,
    ): Decision {
        val parsed = parseAllowed(manifest, rawUrl, initial = false)
        if (parsed is ParsedResult.Failure) return parsed.denied
        parsed as ParsedResult.Success
        return if (parsed.host in approvedHosts) {
            Decision.Allowed(
                transportUrl = parsed.transportUrl,
                transportIdentity = parsed.transportIdentity,
                host = parsed.host,
                resolvedAddressKinds = emptySet(),
                resolutionSource = "session-cache",
                decisionThread = Thread.currentThread().name,
                shape = parsed.shape,
            )
        } else {
            Decision.NeedsDns(
                transportUrl = parsed.transportUrl,
                transportIdentity = parsed.transportIdentity,
                host = parsed.host,
                shape = parsed.shape,
            )
        }
    }

     
    fun transportIdentity(rawUrl: String): String? = runCatching {
        val transportUrl = stripFragment(rawUrl)
        val uri = URI(transportUrl)
        val scheme = uri.scheme.lowercase(Locale.ROOT)
        val host = uri.host.lowercase(Locale.ROOT)
        val defaultPort = when (scheme) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }
        val port = if (uri.port == -1) defaultPort else uri.port
        buildString {
            append(scheme).append("://").append(host).append(':').append(port)
            append(uri.rawPath.orEmpty().ifBlank { "/" })
            uri.rawQuery?.let { append('?').append(it) }
        }
    }.getOrNull()

    private fun preflight(manifest: SourceManifest, rawUrl: String, initial: Boolean): Decision {
        val parsed = parseAllowed(manifest, rawUrl, initial)
        if (parsed is ParsedResult.Failure) return parsed.denied
        parsed as ParsedResult.Success
        return try {
            val addresses = PublicAddressPolicy.requirePublic(resolver(parsed.host))
            Decision.Allowed(
                transportUrl = parsed.transportUrl,
                transportIdentity = parsed.transportIdentity,
                host = parsed.host,
                resolvedAddressKinds = addresses.mapTo(linkedSetOf(), ::addressKind),
                resolutionSource = "dns-preflight",
                decisionThread = Thread.currentThread().name,
                shape = parsed.shape,
            )
        } catch (error: Exception) {
            Decision.Denied(
                code = policyCode(error),
                causeType = error.javaClass.name,
                decisionThread = Thread.currentThread().name,
                shape = parsed.shape,
            )
        }
    }

    private fun parseAllowed(manifest: SourceManifest, rawUrl: String, initial: Boolean): ParsedResult {
        val shape = urlShape(rawUrl)
        return try {
            val transportUrl = stripFragment(rawUrl)
            val uri = if (initial) {
                SourceOriginPolicy.requireInitialUrl(manifest, transportUrl)
            } else {
                SourceOriginPolicy.requireRedirectUrl(manifest, transportUrl)
            }
            val host = uri.host.lowercase(Locale.ROOT)
            val identity = transportIdentity(transportUrl) ?: error("SOURCE_NETWORK_URL_INVALID")
            ParsedResult.Success(transportUrl, identity, host, shape ?: shapeOf(uri, rawUrl))
        } catch (error: Exception) {
            ParsedResult.Failure(Decision.Denied(
                code = policyCode(error),
                causeType = error.javaClass.name,
                decisionThread = Thread.currentThread().name,
                shape = shape,
            ))
        }
    }

    private fun stripFragment(rawUrl: String): String {
        val uri = URI(rawUrl)
        return if (uri.rawFragment == null) rawUrl else rawUrl.substringBefore('#')
    }

    private fun urlShape(rawUrl: String): UrlShape? = runCatching {
        shapeOf(URI(rawUrl), rawUrl)
    }.getOrNull()

    private fun shapeOf(uri: URI, rawUrl: String): UrlShape = UrlShape(
        scheme = uri.scheme.orEmpty().lowercase(Locale.ROOT),
        host = uri.host.orEmpty().lowercase(Locale.ROOT),
        port = uri.port,
        hasQuery = uri.rawQuery != null,
        hasFragment = uri.rawFragment != null || '#' in rawUrl,
    )

    private fun addressKind(address: InetAddress): String = when (address) {
        is Inet4Address -> "ipv4-public"
        is Inet6Address -> "ipv6-public"
        else -> "public-other"
    }

    private fun policyCode(error: Exception): String {
        val message = error.message.orEmpty()
        return ERROR_CODE.find(message)?.value ?: when (error) {
            is java.net.UnknownHostException -> "SOURCE_NETWORK_DNS_FAILED"
            else -> "SOURCE_BROWSER_URL_POLICY_FAILED"
        }
    }

    private sealed interface ParsedResult {
        data class Success(
            val transportUrl: String,
            val transportIdentity: String,
            val host: String,
            val shape: UrlShape,
        ) : ParsedResult

        data class Failure(val denied: Decision.Denied) : ParsedResult
    }

    private companion object {
        val ERROR_CODE = Regex("[A-Z][A-Z0-9_]{2,}")
    }
}
