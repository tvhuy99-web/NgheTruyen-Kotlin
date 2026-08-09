package vn.nghetruyen.source.api

/**
 * Host-only callback invoked immediately before implementation-owned collections are exposed to a
 * script runtime. The API module intentionally knows nothing about Rhino or any other engine.
 */
object SourceHostValueBoundary {
    @Volatile
    private var beforeExposure: (() -> Unit)? = null

    fun installBeforeExposureHook(hook: (() -> Unit)?) {
        beforeExposure = hook
    }

    fun beforeCollectionExposure() {
        beforeExposure?.invoke()
    }
}
