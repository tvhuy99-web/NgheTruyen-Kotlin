package vn.nghetruyen.source.vbook

import vn.nghetruyen.source.api.SourceActionRequest
import vn.nghetruyen.source.api.SourceActionResponse
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.runtime.SourceResourceProvider

/**
 * Runtime-neutral execution seam for one vBook/Native-Lua-compatible source action.
 *
 * The default implementation remains [VBookJsRuntime]. Android may inject a Chromium-backed
 * implementation without making the pure JVM vBook module depend on android.webkit.
 */
fun interface VBookActionRuntime {
    fun execute(
        manifest: SourceManifest,
        resources: SourceResourceProvider,
        request: SourceActionRequest,
    ): SourcePlatformResult<SourceActionResponse>
}
