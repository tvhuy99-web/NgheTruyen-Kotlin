package vn.nghetruyen.source.vbook

enum class VBookFeatureImplementationLevel {
    IMPLEMENTED,
    PARTIAL,
    EXPLICITLY_UNSUPPORTED,
    PACKAGE_LAYER_PENDING,
    METADATA_ONLY,
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
                    VBookFeatureImplementationLevel.EXPLICITLY_UNSUPPORTED,
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
        VBookFeature.CONFIG_LEGACY_PRIMITIVE,
        VBookFeature.CONFIG_DESCRIPTOR,
        VBookFeature.CONFIG_CONNECTION_SETTINGS,
        VBookFeature.COMMENTS,
        VBookFeature.SUGGESTIONS,
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
        VBookFeature.HTML_CLEAN,
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
        VBookFeature.WEBSOCKET,
        VBookFeature.WEBSOCKET_HEADERS,
        VBookFeature.WEBSOCKET_FRAMES,
        VBookFeature.QUICK_TRANSLATOR,
        VBookFeature.CRYPTO,
        VBookFeature.SCRIPT_EXECUTE,
        VBookFeature.USER_AGENT,
        VBookFeature.SLEEP,
        VBookFeature.LOGGING,
    )

    private val explicitlyUnsupported = setOf(
        VBookFeature.CONTENT_UNKNOWN,
        VBookFeature.CONFIG_UNSUPPORTED_DESCRIPTOR,
        VBookFeature.LOCAL_COOKIE_CLEARTEXT,
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
            feature in explicitlyUnsupported -> VBookFeatureSupport(
                feature,
                VBookFeatureImplementationLevel.EXPLICITLY_UNSUPPORTED,
                unsupportedNote(feature),
            )
            feature == VBookFeature.METADATA_ENCRYPT -> VBookFeatureSupport(
                feature,
                VBookFeatureImplementationLevel.METADATA_ONLY,
                "The official vBook VS Code tester strips metadata.encrypt during build and test packaging. The host therefore treats the flag as authoring/build metadata; actual script bytes are validated independently.",
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
        VBookFeature.FETCH_TIMEOUT ->
            "Requested timeout is capped by the remaining action budget, and configured inter-request delay cannot sleep past that budget."
        VBookFeature.FETCH_STATUS_TEXT ->
            "Transport reason metadata is preserved. Empty statusText remains valid for protocols/transports without a reason phrase."
        VBookFeature.FETCH_REQUEST_INFO ->
            "Final request URL and headers are captured after defaults, cookies and redirects; internal compatibility headers are removed."
        VBookFeature.CONFIG_CONNECTION_SETTINGS ->
            "thread_num controls host concurrency; timeout provides the default request timeout; delay spaces real upstream request starts without delaying cached response conversions."
        VBookFeature.DYNAMIC_DATA_ARGUMENT ->
            "Initial dynamic invocation preserves explicit data as args[1], including an explicit empty string; subsequent pages replace args[1] with opaque data2."
        VBookFeature.COMMENTS ->
            "Declared comment scripts preserve opaque data2 pagination and normalize vBook name/content/description rows into the application comment model."
        VBookFeature.SUGGESTIONS ->
            "Declared suggest/suggests scripts are executed through the vBook ABI; string or story rows are normalized to bounded search suggestions."
        VBookFeature.LEGACY_HTTP_SOURCE ->
            "Cleartext is derived per extension and allowed only in VBOOK_JS_COMPAT while public-address DNS restrictions continue blocking private/LAN destinations."
        VBookFeature.HTML_CLEAN ->
            "Legacy Html.clean keeps explicitly allowed tags, unwraps other markup while preserving text, removes script/style/template/noscript content, and delegates to a native cleaner when the engine already provides one."
        VBookFeature.HTML_COLLECTION_CALLBACKS ->
            "Compatibility DOM exposes array-compatible forEach/map plus vBook collection helpers; reference certification still decides exact behavioral parity."
        VBookFeature.HTML_MUTATION ->
            "Compatibility DOM tracks removed nodes so subsequent select/text/html serialization observes removals; exact mutation parity remains differential-certified rather than assumed."
        VBookFeature.HTML_ATTRIBUTES ->
            "Element.attributes() exposes an object keyed by parsed attribute names and values from the underlying parsed element."
        VBookFeature.BROWSER_LOAD_HTML ->
            "Compatibility wrapper translates vBook loadHtml(html, baseUrl) into the legacy internal host argument order."
        VBookFeature.BROWSER_WAIT_URL ->
            "Compatibility wrapper waits against captured network-request URLs and accepts literal or regex-style patterns used by current community extensions."
        VBookFeature.WEBSOCKET_HEADERS ->
            "The current compatibility prelude carries constructor headers through an internal vBook-only marker; the network broker validates them before opening the public WSS connection."
        VBookFeature.WEBSOCKET_FRAMES ->
            "Text and binary transport frames are converted to the current {type,data} JavaScript shape; binary data remains canonical base64."
        VBookFeature.WEBSOCKET ->
            "The synchronous vBook socket ABI is implemented as one bounded WSS exchange per receive call, with headers, text/binary frames, close metadata, lifetime and message-size limits covered by tests."
        VBookFeature.QUICK_TRANSLATOR ->
            "Base Qt.translate(text,'vp'|'hv') is routed to a dedicated offline Quick Translator broker rather than the generic AI/translate-extension path."
        VBookFeature.USER_AGENT ->
            "UserAgent.system/chrome/android/ios are exposed inside the safe Rhino host without Java interop."
        VBookFeature.SLEEP ->
            "sleep(ms) is sandbox-budgeted and cannot bypass the action deadline."
        VBookFeature.LOGGING ->
            "Log, Console and console diagnostic methods are available without exposing JVM logging classes."
        else -> "Implementation exists; certification remains a separate differential-test state."
    }

    private fun unsupportedNote(feature: VBookFeature): String = when (feature) {
        VBookFeature.CONTENT_UNKNOWN ->
            "Unknown metadata.type is archived and audited but not activated. A new type or legacy dialect must be proven before the host assigns provider semantics."
        VBookFeature.CONFIG_UNSUPPORTED_DESCRIPTOR ->
            "A structured config object uses a descriptor contract the native editor does not understand. The package is quarantined instead of flattening or silently corrupting that value."
        VBookFeature.LOCAL_COOKIE_CLEARTEXT ->
            "Cleartext network access is sandboxed per extension, but localCookie.setCookie still enforces HTTPS. The corpus scanner exposes this exact combination so it blocks parity only when an extension actually requires it."
        VBookFeature.QUICK_TRANSLATOR_OPTIONS ->
            "All extras reach the dedicated broker, but unsupported specialized semantics such as NER, person_name and traditional-to-simplified are rejected at compatibility validation rather than silently approximated."
        VBookFeature.QUICK_TRANSLATOR_SEGMENTS ->
            "The generic/provider source API can represent offset segments, but Quick Translator does not claim reference-compatible offsets/types and packages that require them are quarantined."
        else -> "This feature is intentionally unsupported and blocks activation; it is never silently approximated."
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
