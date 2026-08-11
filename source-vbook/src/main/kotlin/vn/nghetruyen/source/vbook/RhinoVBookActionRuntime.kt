package vn.nghetruyen.source.vbook

import vn.nghetruyen.source.api.SourceActionRequest
import vn.nghetruyen.source.api.SourceActionResponse
import vn.nghetruyen.source.api.SourceCapabilityBrokers
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.diagnostics.DiagnosticEvidenceSink
import vn.nghetruyen.source.diagnostics.DiagnosticSink
import vn.nghetruyen.source.runtime.SourceResourceProvider

/** Keeps Rhino as the portable JVM fallback while Android may choose a Chromium primary runtime. */
class RhinoVBookActionRuntime(
    brokers: SourceCapabilityBrokers = SourceCapabilityBrokers(),
    diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    evidence: DiagnosticEvidenceSink = DiagnosticEvidenceSink.NONE,
) : VBookActionRuntime {
    private val delegate = VBookJsRuntime(brokers, diagnostics, evidence = evidence)

    override fun execute(
        manifest: SourceManifest,
        resources: SourceResourceProvider,
        request: SourceActionRequest,
    ): SourcePlatformResult<SourceActionResponse> = delegate.execute(manifest, resources, request)

    fun validateScripts(manifest: SourceManifest, resources: SourceResourceProvider): VBookCompatibilityReport =
        delegate.validateScripts(manifest, resources)
}
