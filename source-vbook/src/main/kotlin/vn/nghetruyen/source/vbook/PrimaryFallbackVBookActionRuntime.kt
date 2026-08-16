package vn.nghetruyen.source.vbook

import vn.nghetruyen.source.api.SourceActionRequest
import vn.nghetruyen.source.api.SourceActionResponse
import vn.nghetruyen.source.api.SourceErrorCode
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.runtime.SourceResourceProvider









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
        is SourcePlatformResult.Failure -> if (canFallback(result)) {
            fallback.execute(manifest, resources, request)
        } else result
    }

    private fun canFallback(result: SourcePlatformResult.Failure): Boolean {
        if (result.error.code != SourceErrorCode.VBOOK_RUNTIME_UNAVAILABLE) return false
        return PRE_EXECUTION_UNAVAILABLE_PREFIXES.any(result.error.message::startsWith)
    }

    companion object {
        private val PRE_EXECUTION_UNAVAILABLE_PREFIXES = setOf(
            "CHROMIUM_RUNTIME_CLOSED",
            "CHROMIUM_VBOOK_MODE_REQUIRED",
            "CHROMIUM_COMPAT_DISPATCH_ACTION_REQUIRED:",
            "CHROMIUM_WEBVIEW_UNAVAILABLE:",
            "CHROMIUM_MAIN_THREAD_CALLER_UNSUPPORTED",
        )
    }
}
