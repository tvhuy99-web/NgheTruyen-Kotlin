package vn.nghetruyen.source.vbook

enum class VBookFeatureImplementationLevel {
    IMPLEMENTED,
    PARTIAL,
    PACKAGE_LAYER_PENDING,
    REFERENCE_REJECTS,
}

enum class VBookFeatureCertificationState {
    UNTESTED,
    CERTIFIED,
    DIVERGED,
    NOT_APPLICABLE,
}

data class VBookFeatureSupport(
    val feature: VBookFeature,
    val implementation: VBookFeatureImplementationLevel,
    val note: String,
)

data class VBookFeatureCertification(
    val feature: VBookFeature,
    val state: VBookFeatureCertificationState,
    val caseIds: Set<String> = emptySet(),
    val detail: String = "",
)

data class VBookCorpusCompatibilityMatrix(
    val rows: List<VBookFeatureSupport>,
    val requiredByCorpus: Map<VBookFeature, Int>,
    val certifications: Map<VBookFeature, VBookFeatureCertification> = emptyMap(),
) {
    val blockingFeatures: List<VBookFeatureSupport>
        get() = rows.filter { row ->
            (requiredByCorpus[row.feature] ?: 0) > 0 &&
                row.implementation in setOf(
                    VBookFeatureImplementationLevel.PARTIAL,
                    VBookFeatureImplementationLevel.PACKAGE_LAYER_PENDING,
                )
        }

    val uncertifiedRequiredFeatures: List<VBookFeatureSupport>
        get() = rows.filter { row ->
            (requiredByCorpus[row.feature] ?: 0) > 0 &&
                row.implementation == VBookFeatureImplementationLevel.IMPLEMENTED &&
                certifications[row.feature]?.state != VBookFeatureCertificationState.CERTIFIED
        }

    val canClaimFullCorpusParity: Boolean
        get() = blockingFeatures.isEmpty() && uncertifiedRequiredFeatures.isEmpty() &&
            certifications.values.none { it.state == VBookFeatureCertificationState.DIVERGED }
}

/**
 * Declares code coverage only. IMPLEMENTED is deliberately not the same as CERTIFIED.
 * Certification is produced by differential fixtures against the reference runtime.
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
                VBookFeatureImplementationLevel.IMPLEMENTED,
                "Implementation exists; certification remains a separate differential-test state.",
            )

            VBookFeature.FETCH_CHARSET,
            VBookFeature.FETCH_BASE64,
            VBookFeature.FETCH_BLOB -> VBookFeatureSupport(
                feature,
                VBookFeatureImplementationLevel.PARTIAL,
                "The network broker preserves raw bytes, but VBookJsRuntime still constructs fetch responses from bodyText(); the raw-byte host bridge must be wired before certification.",
            )

            VBookFeature.METADATA_ENCRYPT -> VBookFeatureSupport(
                feature,
                VBookFeatureImplementationLevel.PACKAGE_LAYER_PENDING,
                "Plain source-tree/package execution is supported; encrypted distribution payload decoding is intentionally not guessed without a proven reference format.",
            )

            VBookFeature.LEGACY_HTTP_SOURCE -> VBookFeatureSupport(
                feature,
                VBookFeatureImplementationLevel.PARTIAL,
                "Artifact parsing is supported; cleartext egress requires a vBook-specific, per-extension policy instead of globally weakening native-source HTTPS rules.",
            )

            VBookFeature.JS_FORBIDDEN_ASYNC_AWAIT,
            VBookFeature.JS_FORBIDDEN_OPTIONAL_CHAINING,
            VBookFeature.JS_FORBIDDEN_NULLISH,
            VBookFeature.JS_FORBIDDEN_SPREAD,
            VBookFeature.JS_FORBIDDEN_NAMED_CAPTURE,
            VBookFeature.JS_FORBIDDEN_LOOKBEHIND -> VBookFeatureSupport(
                feature,
                VBookFeatureImplementationLevel.REFERENCE_REJECTS,
                "The reference current Rhino contract rejects this syntax; encountering it is an extension-authoring error, not a host parity gap.",
            )
        }
    }

    fun support(feature: VBookFeature): VBookFeatureSupport = support.getValue(feature)

    fun matrix(
        report: VBookCorpusReport,
        certifications: Collection<VBookFeatureCertification> = emptyList(),
    ): VBookCorpusCompatibilityMatrix {
        val counts = report.features.associate { it.feature to it.extensionCount }
        return VBookCorpusCompatibilityMatrix(
            rows = VBookFeature.entries.map(::support),
            requiredByCorpus = counts,
            certifications = certifications.associateBy(VBookFeatureCertification::feature),
        )
    }
}
