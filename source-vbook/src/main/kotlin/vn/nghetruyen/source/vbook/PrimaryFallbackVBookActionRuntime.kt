package vn.nghetruyen.source.vbook

import vn.nghetruyen.source.api.SourceActionRequest
import vn.nghetruyen.source.api.SourceActionResponse
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.runtime.SourceResourceProvider

/**
 * Selects a primary engine without replaying extension side effects after ordinary script failures.
 *
 * Fallback is allowed only when the primary explicitly reports runtime unavailability. A network,
 * host-command, script, browser or data error is returned as-is so an action is never executed twice.
 */
class PrimaryFallbackVBookActionRuntime(
    private val primary: VBookActionRuntime,
    private val fallback: VBookActionRuntime,
) : VBookActionRuntime {
    override fun execute(
        manifest: SourceManifest,
        resources: SourceResourceProvider,
        request: SourceActionRequest,
    ): SourcePlatformResult<SourceActionResponse> = when (val result = primary.execute(manifest, resources, request)) {
        is SourcePlatformResult.Success -> result
        is SourcePlatformResult.Failure -> if (result.error.code == SourceErrorCode.VBOOK_RUNTIME_UNAVAILABLE) {
            fallback.execute(manifest, resources, request)
        } else result
    }
}
