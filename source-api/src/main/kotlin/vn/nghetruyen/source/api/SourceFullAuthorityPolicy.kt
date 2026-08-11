package vn.nghetruyen.source.api

/**
 * The single authority model for installed NgheTruyen extensions.
 *
 * This is intentionally not a trust tier. Once an extension is installed it receives the complete
 * host capability surface that can be expressed by SourceManifest. Runtime budgets still exist to
 * contain hangs/crashes, while the hard boundary stays below the source API: no raw Android/Java
 * object bridge, host-secret extraction, arbitrary process execution, file:// or content:// escape.
 */
object SourceFullAuthorityPolicy {
    const val AUTHORITY_ID = "FULL_IN_APP"

    private val allNetworkMethods = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")

    fun apply(
        manifest: SourceManifest,
        allowCleartext: Boolean = true,
        maxConcurrent: Int = 8,
    ): SourceManifest {
        val existingNetwork = manifest.capabilities.network
        val network = (existingNetwork ?: SourceNetworkCapability(
            methods = allNetworkMethods,
            maxResponseBytes = 16 * 1024 * 1024,
            maxRequestBytes = 4 * 1024 * 1024,
            requestsPerMinute = 600,
            maxConcurrent = maxConcurrent,
        )).copy(
            methods = allNetworkMethods,
            maxResponseBytes = maxOf(existingNetwork?.maxResponseBytes ?: 0, 16 * 1024 * 1024),
            maxRequestBytes = maxOf(existingNetwork?.maxRequestBytes ?: 0, 4 * 1024 * 1024),
            requestsPerMinute = maxOf(existingNetwork?.requestsPerMinute ?: 0, 600),
            maxConcurrent = maxOf(existingNetwork?.maxConcurrent ?: 0, maxConcurrent),
            publicInternet = true,
            allowCleartext = allowCleartext,
        )
        val websocket = manifest.capabilities.websocket.copy(
            enabled = true,
            maxMessageBytes = maxOf(manifest.capabilities.websocket.maxMessageBytes, 256 * 1024),
            maxLifetimeMs = maxOf(manifest.capabilities.websocket.maxLifetimeMs, 10 * 60_000L),
        )
        return manifest.copy(
            capabilities = manifest.capabilities.copy(
                network = network,
                cookies = SourceCookieMode.BROWSER_SHARED,
                browser = SourceBrowserCapability(
                    navigate = true,
                    domSnapshot = true,
                    click = true,
                    input = true,
                    requestMetadata = true,
                    serviceWorkerCapture = true,
                    pageJavaScript = true,
                ),
                storageBytes = maxOf(manifest.capabilities.storageBytes, 32 * 1024 * 1024),
                crypto = SourceCryptoCapability.entries.toSet(),
                websocket = websocket,
            ),
        ).also(SourceManifest::validate)
    }
}
