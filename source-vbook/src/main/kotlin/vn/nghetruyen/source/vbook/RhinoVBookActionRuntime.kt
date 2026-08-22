package vn.nghetruyen.source.vbook

import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourceActionName
import vn.nghetruyen.source.api.SourceActionRequest
import vn.nghetruyen.source.api.SourceActionResponse
import vn.nghetruyen.source.api.SourceCapabilityBrokers
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.diagnostics.DiagnosticEvidenceSink
import vn.nghetruyen.source.diagnostics.DiagnosticSink
import vn.nghetruyen.source.runtime.SourceResourceProvider

/**
 * Portable Rhino runtime and Android primary-engine selection point.
 *
 * When Android has installed a platform runtime factory, Chromium is tried first and Rhino is used
 * only for explicit VBOOK_RUNTIME_UNAVAILABLE. Pure JVM consumers never install that factory and
 * therefore keep the exact Rhino path used before M3.
 *
 * Legacy declared GENRE menus are selected onto Rhino before any extension code executes. Legacy
 * vBook genre scripts commonly return large static dynamic-action arrays through the 200-response
 * ABI; keeping that menu-discovery step on the mature portable engine avoids Android WebView ABI
 * differences without replaying extension code or changing current-JS Chromium routing.
 */
class RhinoVBookActionRuntime(
    private val brokers: SourceCapabilityBrokers = SourceCapabilityBrokers(),
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    evidence: DiagnosticEvidenceSink = DiagnosticEvidenceSink.NONE,
) : VBookActionRuntime {
    private val delegate = VBookJsRuntime(brokers, diagnostics, evidence = evidence)
    private val fallback = VBookActionRuntime { manifest, resources, request ->
        delegate.execute(manifest, resources, request)
    }
    private val selected: VBookActionRuntime by lazy {
        VBookActionRuntimeRegistry
            .platformRuntime(brokers, diagnostics)
            ?.let { primary -> PrimaryFallbackVBookActionRuntime(primary, fallback) }
            ?: fallback
    }

    override fun execute(
        manifest: SourceManifest,
        resources: SourceResourceProvider,
        request: SourceActionRequest,
    ): SourcePlatformResult<SourceActionResponse> {
        if (preferPortableLegacyGenre(resources, request)) {
            return fallback.execute(manifest, resources, request)
        }
        return selected.execute(manifest, resources, request)
    }

    private fun preferPortableLegacyGenre(
        resources: SourceResourceProvider,
        request: SourceActionRequest,
    ): Boolean {
        if (request.action != SourceActionName.UI_ACTION) return false
        val target = (request.input["script"] as? JsonValue.Str)?.value
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return false
        val pluginBytes = resources.read("plugin.json", 1024 * 1024) ?: return false
        val plugin = runCatching { VBookManifestParser.parse(pluginBytes.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return false
        val genrePath = plugin.script(VBookScriptRole.GENRE) ?: return false
        val normalizedTarget = runCatching { VBookPaths.normalizeScriptPath(target) }.getOrNull() ?: return false
        if (normalizedTarget != genrePath) return false

        val declaredSources = plugin.allDeclaredScriptPaths().associateWith { path ->
            resources.read(path, 2 * 1024 * 1024)?.toString(Charsets.UTF_8).orEmpty()
        }
        return VBookContractDetector.detect(plugin, declaredSources).profile == VBookContractProfile.LEGACY_JS
    }

    fun validateScripts(manifest: SourceManifest, resources: SourceResourceProvider): VBookCompatibilityReport =
        delegate.validateScripts(manifest, resources)
}
