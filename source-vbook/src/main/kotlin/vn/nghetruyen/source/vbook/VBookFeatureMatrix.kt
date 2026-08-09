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

/** Code coverage only. IMPLEMENTED is deliberately distinct from reference-certified parity. */
object VBookEngineFeatureMatrix {
    private val implemented = setOf(
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
        VBookFeature.CONFIG_CONNECTION_SETTINGS,
        VBookFeature.DYNAMIC_SCRIPT_REFERENCE,
        VBookFeature.DYNAMIC_DATA_ARGUMENT,
        VBookFeature.DYNAMIC_LOAD,
        VBookFeature.LOAD_CRYPTO_BUILTIN,
        VBookFeature.RESPONSE_HELPER,
        VBookFeature.RESPONSE_LEGACY_CODE,
        VBookFeature.FETCH,
        VBookFeature.FETCH_QUERIES,
        VBookFeature.FETCH_TIMEOUT,
        VBookFeature.FETCH_HEADER,
        VBookFeature.FETCH_STATUS_TEXT,
        VBookFeature.FETCH_CHARSET,
        VBookFeature.FETCH_BASE64,
        VBookFeature.FETCH_BLOB,
        VBookFeature.FETCH_REQUEST_INFO,
        VBookFeature.LEGACY_HTTP_SOURCE,
        VBookFeature.HTML_DOM,
        VBookFeature.HTML_COLLECTION_CALLBACKS,
        VBookFeature.HTML_MUTATION,
        VBookFeature.HTML_ATTRIBUTES,
        VBookFeature.LOCAL_CONFIG,
        VBookFeature.LOCAL_STORAGE,
        VBookFeature.CACHE_STORAGE,
        VBookFeature.LOCAL_COOKIE,
        VBookFeature.BROWSER,
        VBookFeature.BROWSER_LOAD_HTML,
        VBookFeature.BROWSER_WAIT_URL,
        VBookFeature.BROWSER_REQUEST_METADATA,
        VBookFeature.GRAPHICS,
        VBookFeature.CRYPTO,
        VBookFeature.SCRIPT_EXECUTE,
    )

    private val partial = setOf(
        VBookFeature.WEBSOCKET,
        VBookFeature.WEBSOCKET_HEADERS,
        VBookFeature.WEBSOCKET_FRAMES,
        VBookFeature.QUICK_TRANSLATOR,
        VBookFeature.QUICK_TRANSLATOR_OPTIONS,
        VBookFeature.QUICK_TRANSLATOR_SEGMENTS,
    )

    private val referenceRejects = setOf(
        VBookFeature.JS_FORBIDDEN_ASYNC_AWAIT,
        VBookFeature.JS_FORBIDDEN_OPTIONAL_CHAINING,
        VBookFeature.JS_FORBIDDEN_NULLISH,
        VBookFeature.JS_FORBIDDEN_SPREAD,
        VBookFeature.JS_FORBIDDEN_NAMED_CAPTURE,
        VBookFeature.JS_FORBIDDEN_LOOKBEHIND,
    )

    private val support = VBookFeature.entries.associateWith { feature ->
        when {
            feature in implemented -> VBookFeatureSupport(
                feature,
                VBookFeatureImplementationLevel.IMPLEMENTED,
                implementationNote(feature),
            )
            feature in partial -> VBookFeatureSupport(
                feature,
                VBookFeatureImplementationLevel.PARTIAL,
                partialNote(feature),
            )
            feature == VBookFeature.METADATA_ENCRYPT -> VBookFeatureSupport(
                feature,
                VBookFeatureImplementationLevel.PACKAGE_LAYER_PENDING,
                "Plain source-tree/package execution is supported; encrypted distribution decoding is intentionally not guessed without a proven reference format.",
            )
            feature in referenceRejects -> VBookFeatureSupport(
                feature,
                VBookFeatureImplementationLevel.REFERENCE_REJECTS,
                "The reference current Rhino contract rejects this syntax; encountering it is an extension-authoring error, not a host parity gap.",
            )
            else -> error("VBOOK_FEATURE_MATRIX_ROW_MISSING:$feature")
        }
    }

    private fun implementationNote(feature: VBookFeature): String = when (feature) {
        VBookFeature.FETCH_CHARSET, VBookFeature.FETCH_BASE64, VBookFeature.FETCH_BLOB ->
            "VBookRawNetworkBroker keeps exact response bytes; representation APIs reuse the captured response and never replay the upstream request."
        VBookFeature.FETCH_STATUS_TEXT ->
            "Transport reason metadata is preserved. Empty statusText remains valid for protocols/transports without a reason phrase."
        VBookFeature.FETCH_REQUEST_INFO ->
            "Final request URL and headers are captured after defaults, cookies and redirects; internal compatibility headers are removed."
        VBookFeature.CONFIG_CONNECTION_SETTINGS ->
            "thread_num controls host concurrency; timeout provides the default request timeout; delay spaces real upstream request starts without delaying cached response conversions."
        VBookFeature.DYNAMIC_DATA_ARGUMENT ->
            "Initial dynamic invocation preserves explicit data as args[1], including an explicit empty string; subsequent pages replace args[1] with opaque data2."
        VBookFeature.LEGACY_HTTP_SOURCE ->
            "Cleartext is derived per extension and allowed only in VBOOK_JS_COMPAT while public-address DNS restrictions continue blocking private/LAN destinations."
        VBookFeature.HTML_COLLECTION_CALLBACKS ->
            "Compatibility DOM exposes array-compatible forEach/map plus vBook collection helpers; reference certification still decides exact behavioral parity."
        VBookFeature.HTML_MUTATION ->
            "Compatibility DOM tracks removed nodes so subsequent select/text/html serialization observes removals; exact mutation parity remains differential-certified rather than assumed."
        VBookFeature.HTML_ATTRIBUTES ->
            "Element.attributes() exposes an object keyed by parsed attribute names and values from the underlying parsed element."
        VBookFeature.BROWSER_LOAD_HTML ->
            "Compatibility wrapper translates vBook loadHtml(html, baseUrl) into the legacy internal host argument order."
        VBookFeature.BROWSER_WAIT_URL ->
            "Compatibility wrapper waits against captured network-request URLs rather than the current page URL."
        else -> "Implementation exists; certification remains a separate differential-test state."
    }

    private fun partialNote(feature: VBookFeature): String = when (feature) {
        VBookFeature.WEBSOCKET ->
            "Network broker now preserves text/binary frames, but the JavaScript host object still needs exact constructor-header and frame-object wiring."
        VBookFeature.WEBSOCKET_HEADERS ->
            "SourceWebSocketBroker accepts headers, but the current JavaScript WebSocket constructor does not yet forward its second argument."
        VBookFeature.WEBSOCKET_FRAMES ->
            "Transport frames are preserved, but JS message() still exposes the older string-only shape instead of {type,data}."
        VBookFeature.QUICK_TRANSLATOR ->
            "Qt.translate currently routes through the generic translation broker; a dedicated vp/hv compatibility adapter is still required for reference semantics."
        VBookFeature.QUICK_TRANSLATOR_OPTIONS ->
            "Quick Translator extras are not yet carried end-to-end with their vBook meanings."
        VBookFeature.QUICK_TRANSLATOR_SEGMENTS ->
            "The source API can represent offset segments, but the JS Qt bridge does not yet expose the reference object-array shape."
        else -> "Compatibility behavior is incomplete."
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
