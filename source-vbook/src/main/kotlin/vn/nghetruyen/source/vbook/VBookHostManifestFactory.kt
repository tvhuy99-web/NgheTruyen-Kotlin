package vn.nghetruyen.source.vbook

import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceActionName
import vn.nghetruyen.source.api.SourceActionSpec
import vn.nghetruyen.source.api.SourceBrowserCapability
import vn.nghetruyen.source.api.SourceCapabilities
import vn.nghetruyen.source.api.SourceContentType
import vn.nghetruyen.source.api.SourceCookieMode
import vn.nghetruyen.source.api.SourceCryptoCapability
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkCapability
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceRuntimePolicy
import vn.nghetruyen.source.api.SourceWebSocketCapability
import java.net.URI
import java.security.MessageDigest










object VBookHostManifestFactory {
    private const val DISPATCH_PATH = "src/__nghe_vbook_dispatch.js"
    private val allMethods = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")

    fun create(
        artifactIdentity: String,
        plugin: VBookExtensionManifest,
        resources: vn.nghetruyen.source.runtime.SourceResourceProvider,
    ): SourceManifest {
        val sourceId = stableSourceId(artifactIdentity)


        val allowCleartext = true
        val origin = sourceOrigin(plugin.metadata.source, allowCleartext)
        val connection = VBookConfigValues.resolve(plugin).connectionSettings()
        val manifest = SourceManifest(
            schemaVersion = 2,
            id = sourceId,
            name = plugin.metadata.name.ifBlank { artifactIdentity },
            description = plugin.metadata.description,
            author = plugin.metadata.author,
            version = SemanticVersion(0, 0, plugin.metadata.version.coerceAtLeast(0)),
            apiVersion = 2,
            locale = normalizeLocale(plugin.metadata.locale),
            contentType = when (plugin.metadata.type) {
                VBookContentType.COMIC -> SourceContentType.COMIC
                VBookContentType.AUDIO -> SourceContentType.AUDIO
                VBookContentType.VIDEO, VBookContentType.TTS, VBookContentType.TRANSLATE -> SourceContentType.MIXED
                else -> SourceContentType.NOVEL
            },
            adult = plugin.metadata.nsfw,
            runtime = SourceRuntimePolicy(
                mode = SourceRuntimeMode.VBOOK_JS_COMPAT,


                instructionBudget = 1_000_000,
                memoryBudgetBytes = 64 * 1024 * 1024,
                actionTimeoutMs = 120_000,
            ),
            origins = setOf(origin),
            capabilities = SourceCapabilities(
                network = SourceNetworkCapability(
                    methods = allMethods,
                    maxResponseBytes = 16 * 1024 * 1024,
                    maxRequestBytes = 4 * 1024 * 1024,
                    requestsPerMinute = 600,
                    maxConcurrent = connection.threadNum,
                    publicInternet = true,
                    allowCleartext = true,
                ),
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
                storageBytes = 16 * 1024 * 1024,
                crypto = SourceCryptoCapability.entries.toSet(),
                websocket = SourceWebSocketCapability(
                    enabled = true,
                    maxMessageBytes = 64 * 1024,
                    maxLifetimeMs = 60_000,
                ),
            ),
            actions = mapOf(
                SourceActionName.DETAIL to SourceActionSpec(DISPATCH_PATH, maxOutputBytes = 4 * 1024 * 1024),
                SourceActionName.TOC to SourceActionSpec(DISPATCH_PATH, maxOutputBytes = 4 * 1024 * 1024),
                SourceActionName.CHAPTER to SourceActionSpec(DISPATCH_PATH, maxOutputBytes = 4 * 1024 * 1024),
                SourceActionName.UI_ACTION to SourceActionSpec(DISPATCH_PATH, maxOutputBytes = 4 * 1024 * 1024),
            ),
        )
        manifest.validate()
        return manifest
    }

    fun stableSourceId(artifactIdentity: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(artifactIdentity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "vbook.extension.${digest.take(24)}"
    }

    private fun normalizeLocale(raw: String): String {
        val value = raw.trim().replace('_', '-')
        val parts = value.split('-').filter(String::isNotBlank)
        if (parts.isEmpty()) return "vi-VN"
        val language = parts.first().lowercase().takeIf { it.matches(Regex("[a-z]{2,3}")) } ?: return "vi-VN"
        val region = parts.getOrNull(1)?.uppercase()?.takeIf { it.matches(Regex("[A-Z]{2}")) }
        return if (region == null) language else "$language-$region"
    }

    private fun sourceOrigin(raw: String, allowCleartext: Boolean): String {
        val parsed = runCatching { URI(raw.trim()) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase()
        val host = parsed?.host?.lowercase()
        if (!host.isNullOrBlank() && (scheme == "https" || (allowCleartext && scheme == "http"))) {
            val port = parsed.port
            return buildString {
                append(scheme).append("://").append(host)
                val default = if (scheme == "https") 443 else 80
                if (port != -1 && port != default) append(':').append(port)
            }
        }

        return "https://vbook.invalid"
    }
}
