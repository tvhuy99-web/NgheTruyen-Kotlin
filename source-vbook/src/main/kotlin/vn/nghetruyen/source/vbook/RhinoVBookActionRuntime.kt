package vn.nghetruyen.source.vbook

import vn.nghetruyen.source.api.SourceActionRequest
import vn.nghetruyen.source.api.SourceActionResponse
import vn.nghetruyen.source.api.SourceCapabilityBrokers
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.diagnostics.DiagnosticEvidenceSink
import vn.nghetruyen.source.diagnostics.DiagnosticSink
import vn.nghetruyen.source.runtime.SourceResourceProvider








class RhinoVBookActionRuntime(
    private val brokers: SourceCapabilityBrokers = SourceCapabilityBrokers(),
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    evidence: DiagnosticEvidenceSink = DiagnosticEvidenceSink.NONE,
) : VBookActionRuntime {
    private val delegate = VBookJsRuntime(brokers, diagnostics, evidence = evidence)
    private val fallback = VBookActionRuntime { manifest, resources, request ->
        delegate.execute(manifest, resources, request)
    }
    private val selected: VBookActionRuntime = VBookActionRuntimeRegistry
        .platformRuntime(brokers, diagnostics)
        ?.let { primary -> PrimaryFallbackVBookActionRuntime(primary, fallback) }
        ?: fallback

    override fun execute(
        manifest: SourceManifest,
        resources: SourceResourceProvider,
        request: SourceActionRequest,
    ): SourcePlatformResult<SourceActionResponse> = selected.execute(manifest, resources, request)

    fun validateScripts(manifest: SourceManifest, resources: SourceResourceProvider): VBookCompatibilityReport =
        delegate.validateScripts(manifest, resources)
}
