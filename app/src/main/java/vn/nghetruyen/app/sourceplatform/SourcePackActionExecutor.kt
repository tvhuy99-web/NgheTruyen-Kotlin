package vn.nghetruyen.app.sourceplatform

import vn.nghetruyen.source.api.SourceActionRequest
import vn.nghetruyen.source.api.SourceActionResponse
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.packagekit.VerifiedSourcePack
import vn.nghetruyen.source.runtime.SourceResourceProvider

fun interface SourcePackActionExecutor {
    fun execute(
        pack: VerifiedSourcePack,
        resources: SourceResourceProvider,
        request: SourceActionRequest,
    ): SourcePlatformResult<SourceActionResponse>
}
