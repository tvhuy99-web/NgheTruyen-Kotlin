package vn.nghetruyen.source.vbook

import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceStorageBroker
import vn.nghetruyen.source.api.SourceStorageRequest

 
class VBookStorageBoundaryBroker(
    private val delegate: SourceStorageBroker,
) : SourceStorageBroker {
    override fun get(manifest: SourceManifest, request: SourceStorageRequest): SourcePlatformResult<ByteArray?> {
        VBookSafeRhinoBoundary.installCurrentContext()
        return delegate.get(manifest, request)
    }

    override fun put(manifest: SourceManifest, request: SourceStorageRequest): SourcePlatformResult<Unit> {
        VBookSafeRhinoBoundary.installCurrentContext()
        return delegate.put(manifest, request)
    }

    override fun delete(manifest: SourceManifest, request: SourceStorageRequest): SourcePlatformResult<Unit> {
        VBookSafeRhinoBoundary.installCurrentContext()
        return delegate.delete(manifest, request)
    }

    override fun keys(
        manifest: SourceManifest,
        sourceId: String,
        prefix: String,
        traceId: String,
    ): SourcePlatformResult<List<String>> {
        VBookSafeRhinoBoundary.installCurrentContext()
        return delegate.keys(manifest, sourceId, prefix, traceId)
    }

    override fun clearPrefix(
        manifest: SourceManifest,
        sourceId: String,
        prefix: String,
        traceId: String,
    ): SourcePlatformResult<Unit> {
        VBookSafeRhinoBoundary.installCurrentContext()
        return delegate.clearPrefix(manifest, sourceId, prefix, traceId)
    }

    override fun clear(sourceId: String): SourcePlatformResult<Unit> = delegate.clear(sourceId)
}
