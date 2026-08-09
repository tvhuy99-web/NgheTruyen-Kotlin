package vn.nghetruyen.source.vbook

enum class VBookFeatureSupportLevel {
    SUPPORTED,
    PARTIAL,
    PACKAGE_LAYER_PENDING,
    REFERENCE_REJECTS,
}

data class VBookFeatureSupport(
    val feature: VBookFeature,
    val level: VBookFeatureSupportLevel,
    val note: String,
)

data class VBookCorpusCompatibilityMatrix(
    val rows: List<VBookFeatureSupport>,
    val requiredByCorpus: Map<VBookFeature, Int>,
) {
    val blockingFeatures: List<VBookFeatureSupport>
        get() = rows.filter { row ->
            (requiredByCorpus[row.feature] ?: 0) > 0 &&
                row.level in setOf(VBookFeatureSupportLevel.PARTIAL, VBookFeatureSupportLevel.PACKAGE_LAYER_PENDING)
        }
}

/**
 * Declares implementation truth, not product marketing. A feature becomes SUPPORTED only after
 * its semantic differential fixture passes. PARTIAL rows remain visible in corpus reports.
 */
object VBookEngineFeatureMatrix {
    private val support = VBookFeature.entries.associateWith { feature ->
        when (feature) {
            VBookFeature.CONTRACT_LEGACY,
            VBookFeature.CONTRACT_CURRENT,
            VBookFeature.CONTENT_NOVEL,
            VBookFeature.CONTENT_CHINESE_NOVEL,
            VBookFeature.CONTENT_COMIC,
            VBookFeature.CONTENT_VIDEO,
            VBookFeature.CONTENT_AUDIO,
            VBookFeature.CONTENT_TTS,
            VBookFeature.CONTENT_TRANSLATE,
            VBookFeature.CONTENT_UNKNOWN,
            VBookFeature.CONFIG_LEGACY_PRIMITIVE,
            VBookFeature.CONFIG_DESCRIPTOR,
            VBookFeature.DYNAMIC_SCRIPT_REFERENCE,
            VBookFeature.DYNAMIC_LOAD,
            VBookFeature.LOAD_CRYPTO_BUILTIN,
            VBookFeature.RESPONSE_HELPER,
            VBookFeature.RESPONSE_LEGACY_CODE,
            VBookFeature.FETCH,
            VBookFeature.FETCH_QUERIES,
            VBookFeature.FETCH_TIMEOUT,
            VBookFeature.FETCH_HEADER,
            VBookFeature.FETCH_REQUEST_INFO,
            VBookFeature.HTML_DOM,
            VBookFeature.LOCAL_CONFIG,
            VBookFeature.LOCAL_STORAGE,
            VBookFeature.CACHE_STORAGE,
            VBookFeature.LOCAL_COOKIE,
            VBookFeature.BROWSER,
            VBookFeature.BROWSER_REQUEST_METADATA,
            VBookFeature.GRAPHICS,
            VBookFeature.WEBSOCKET,
            VBookFeature.QUICK_TRANSLATOR,
            VBookFeature.CRYPTO,
            VBookFeature.SCRIPT_EXECUTE -> VBookFeatureSupport(
                feature,
                VBookFeatureSupportLevel.SUPPORTED,
                "Implemented by the contract dispatcher and existing capability brokers; keep guarded by differential fixtures.",
            )

            VBookFeature.FETCH_CHARSET,
            VBookFeature.FETCH_BASE64,
            VBookFeature.FETCH_BLOB -> VBookFeatureSupport(
                feature,
                VBookFeatureSupportLevel.PARTIAL,
                "API shape exists, but byte-exact parity requires the raw network body bridge rather than UTF-8 text reconstruction.",
            )

            VBookFeature.METADATA_ENCRYPT -> VBookFeatureSupport(
                feature,
                VBookFeatureSupportLevel.PACKAGE_LAYER_PENDING,
                "Source-tree execution is supported; encrypted distribution ZIP decoding still requires a proven package-format implementation.",
            )

            VBookFeature.LEGACY_HTTP_SOURCE -> VBookFeatureSupport(
                feature,
                VBookFeatureSupportLevel.PARTIAL,
                "Artifact parses correctly; cleartext network access must be granted per extension instead of enabled globally.",
            )

            VBookFeature.JS_FORBIDDEN_ASYNC_AWAIT,
            VBookFeature.JS_FORBIDDEN_OPTIONAL_CHAINING,
            VBookFeature.JS_FORBIDDEN_NULLISH,
            VBookFeature.JS_FORBIDDEN_SPREAD,
            VBookFeature.JS_FORBIDDEN_NAMED_CAPTURE,
            VBookFeature.JS_FORBIDDEN_LOOKBEHIND -> VBookFeatureSupport(
                feature,
                VBookFeatureSupportLevel.REFERENCE_REJECTS,
                "The reference Rhino contract rejects this syntax; encountering it is an extension authoring error, not an NgheTruyen compatibility gap.",
            )
        }
    }

    fun support(feature: VBookFeature): VBookFeatureSupport = support.getValue(feature)

    fun matrix(report: VBookCorpusReport): VBookCorpusCompatibilityMatrix {
        val counts = report.features.associate { it.feature to it.extensionCount }
        return VBookCorpusCompatibilityMatrix(
            rows = VBookFeature.entries.map(::support),
            requiredByCorpus = counts,
        )
    }
}
