package vn.nghetruyen.source.vbook

import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue

enum class VBookFeature {
    CONTRACT_LEGACY,
    CONTRACT_CURRENT,
    CONTENT_NOVEL,
    CONTENT_CHINESE_NOVEL,
    CONTENT_COMIC,
    CONTENT_VIDEO,
    CONTENT_AUDIO,
    CONTENT_TTS,
    CONTENT_TRANSLATE,
    CONTENT_UNKNOWN,
    METADATA_ENCRYPT,
    LEGACY_HTTP_SOURCE,
    CONFIG_LEGACY_PRIMITIVE,
    CONFIG_DESCRIPTOR,
    CONFIG_CONNECTION_SETTINGS,
    CONFIG_UNSUPPORTED_DESCRIPTOR,
    COMMENTS,
    SUGGESTIONS,
    DYNAMIC_SCRIPT_REFERENCE,
    DYNAMIC_DATA_ARGUMENT,
    DYNAMIC_LOAD,
    LOAD_CRYPTO_BUILTIN,
    RESPONSE_HELPER,
    RESPONSE_LEGACY_CODE,
    FETCH,
    FETCH_QUERIES,
    FETCH_TIMEOUT,
    FETCH_HEADER,
    FETCH_STATUS_TEXT,
    FETCH_CHARSET,
    FETCH_BASE64,
    FETCH_BLOB,
    FETCH_REQUEST_INFO,
    HTML_DOM,
    HTML_CLEAN,
    HTML_COLLECTION_CALLBACKS,
    HTML_MUTATION,
    HTML_ATTRIBUTES,
    LOCAL_CONFIG,
    LOCAL_STORAGE,
    CACHE_STORAGE,
    LOCAL_COOKIE,
    LOCAL_COOKIE_CLEARTEXT,
    BROWSER,
    BROWSER_LOAD_HTML,
    BROWSER_WAIT_URL,
    BROWSER_REQUEST_METADATA,
    GRAPHICS,
    WEBSOCKET,
    WEBSOCKET_HEADERS,
    WEBSOCKET_FRAMES,
    QUICK_TRANSLATOR,
    QUICK_TRANSLATOR_OPTIONS,
    QUICK_TRANSLATOR_SEGMENTS,
    CRYPTO,
    SCRIPT_EXECUTE,
    USER_AGENT,
    SLEEP,
    LOGGING,
    JS_FORBIDDEN_ASYNC_AWAIT,
    JS_FORBIDDEN_OPTIONAL_CHAINING,
    JS_FORBIDDEN_NULLISH,
    JS_FORBIDDEN_SPREAD,
    JS_FORBIDDEN_NAMED_CAPTURE,
    JS_FORBIDDEN_LOOKBEHIND,
}

data class VBookFeatureEvidence(
    val feature: VBookFeature,
    val script: String? = null,
    val evidence: String,
)

data class VBookExtensionAudit(
    val id: String,
    val manifest: VBookExtensionManifest,
    val detection: VBookContractDetection,
    val features: Set<VBookFeature>,
    val evidence: List<VBookFeatureEvidence>,
    val missingRequiredScripts: Set<String>,
    val referencedDynamicScripts: Set<String>,
    val missingReferencedScripts: Set<String>,
    val unknownScriptRoles: Set<String>,
)

data class VBookCorpusFeatureRow(
    val feature: VBookFeature,
    val extensionCount: Int,
    val extensionIds: List<String>,
)

data class VBookCorpusReport(
    val extensionCount: Int,
    val profiles: Map<VBookContractProfile, Int>,
    val contentTypes: Map<VBookContentType, Int>,
    val features: List<VBookCorpusFeatureRow>,
    val extensionsWithMissingRequiredScripts: List<String>,
    val extensionsWithMissingDynamicScripts: List<String>,
) {
    fun count(feature: VBookFeature): Int = features.firstOrNull { it.feature == feature }?.extensionCount ?: 0
}

data class VBookRepositoryDescriptor(
    val link: String,
    val author: String,
    val description: String,
)

object VBookRepositoryIndexParser {
    fun parse(json: String): List<VBookRepositoryDescriptor> {
        val root = JsonCodec.parse(json, maxDepth = 32, maxNodes = 20_000) as? JsonValue.Arr
            ?: error("VBOOK_REPOSITORY_INDEX_ARRAY_REQUIRED")
        return root.values.mapNotNull { value ->
            val obj = value as? JsonValue.Obj ?: return@mapNotNull null
            val link = obj.string("link")?.trim().orEmpty()
            if (link.isBlank()) return@mapNotNull null
            VBookRepositoryDescriptor(
                link = link,
                author = obj.string("author").orEmpty(),
                description = obj.string("description").orEmpty(),
            )
        }.distinctBy { it.link }
    }
}

object VBookCorpusAnalyzer {
    private val dynamicObject = Regex(
        "\\{[^{}]{0,1000}?\\bscript\\s*:\\s*['\"]([^'\"]+\\.js)['\"][^{}]{0,1000}?\\}",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val loadLiteral = Regex("\\bload\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)")
    private val quickTranslatorWithExtras = Regex("\\bQt\\.translate\\s*\\([^)]*,[^)]*,", RegexOption.DOT_MATCHES_ALL)
    private val websocketWithHeaders = Regex("\\b(?:new\\s+)?WebSocket\\s*\\([^,\\n]+,\\s*\\{", RegexOption.IGNORE_CASE)

    fun audit(
        id: String,
        pluginJson: String,
        scripts: Map<String, String>,
    ): VBookExtensionAudit {
        val manifest = VBookManifestParser.parse(pluginJson)
        val detection = VBookContractDetector.detect(manifest, scripts)
        val evidence = mutableListOf<VBookFeatureEvidence>()
        fun add(feature: VBookFeature, script: String? = null, value: String) {
            evidence += VBookFeatureEvidence(feature, script, value.take(240))
        }

        when (detection.profile) {
            VBookContractProfile.LEGACY_JS -> add(VBookFeature.CONTRACT_LEGACY, value = detection.reasons.joinToString("; "))
            VBookContractProfile.CURRENT_JS -> add(VBookFeature.CONTRACT_CURRENT, value = detection.reasons.joinToString("; "))
            VBookContractProfile.UNKNOWN -> Unit
        }
        val contentFeature = when (manifest.metadata.type) {
            VBookContentType.NOVEL -> VBookFeature.CONTENT_NOVEL
            VBookContentType.CHINESE_NOVEL -> VBookFeature.CONTENT_CHINESE_NOVEL
            VBookContentType.COMIC -> VBookFeature.CONTENT_COMIC
            VBookContentType.VIDEO -> VBookFeature.CONTENT_VIDEO
            VBookContentType.AUDIO -> VBookFeature.CONTENT_AUDIO
            VBookContentType.TTS -> VBookFeature.CONTENT_TTS
            VBookContentType.TRANSLATE -> VBookFeature.CONTENT_TRANSLATE
            VBookContentType.UNKNOWN -> VBookFeature.CONTENT_UNKNOWN
        }
        add(contentFeature, value = manifest.metadata.rawType.orEmpty())
        if (manifest.metadata.encrypt) add(VBookFeature.METADATA_ENCRYPT, value = "metadata.encrypt=true")
        if (manifest.metadata.source.startsWith("http://", ignoreCase = true)) {
            add(VBookFeature.LEGACY_HTTP_SOURCE, value = manifest.metadata.source)
        }
        manifest.config.values.firstOrNull { it.defaultValue.startsWith("http://", ignoreCase = true) }?.let {
            add(VBookFeature.LEGACY_HTTP_SOURCE, value = "config:${it.key}=${it.defaultValue}")
        }
        if (manifest.config.values.any(VBookConfigField::legacyPrimitive)) add(VBookFeature.CONFIG_LEGACY_PRIMITIVE, value = "primitive config")
        if (manifest.config.values.any { !it.legacyPrimitive }) add(VBookFeature.CONFIG_DESCRIPTOR, value = "descriptor config")
        manifest.config.values.filter {
            !it.legacyPrimitive && (it.mode == VBookConfigMode.UNKNOWN || it.format == VBookConfigFormat.UNKNOWN)
        }.takeIf { it.isNotEmpty() }?.let { fields ->
            add(VBookFeature.CONFIG_UNSUPPORTED_DESCRIPTOR, value = fields.joinToString { it.key })
        }
        if (manifest.config.keys.any { it in VBookConfigValues.BUILT_IN_KEYS }) {
            add(VBookFeature.CONFIG_CONNECTION_SETTINGS, value = manifest.config.keys.filter { it in VBookConfigValues.BUILT_IN_KEYS }.sorted().joinToString())
        }
        if (manifest.script(VBookScriptRole.COMMENT) != null) add(VBookFeature.COMMENTS, value = "script.comment")
        if (manifest.script(VBookScriptRole.SUGGEST) != null) add(VBookFeature.SUGGESTIONS, value = "script.suggest")

        val dynamicScripts = linkedSetOf<String>()
        scripts.forEach { (path, code) ->
            val executableCode = VBookJavaScriptLexicalMask.executable(code)
            val structuralCode = VBookJavaScriptLexicalMask.withoutComments(code)
            fun hit(feature: VBookFeature, regex: Regex, label: String = regex.pattern) {
                regex.find(executableCode)?.let { match -> add(feature, path, match.value.take(160).ifBlank { label }) }
            }
            fun hitLiteralArgument(feature: VBookFeature, regex: Regex) {
                regex.findAll(code).firstOrNull { match ->
                    // The match starts in executable code only when the method dot survived masking.
                    // This preserves literal arguments for evidence without treating generated JS/HTML
                    // strings or comments as host-API requirements.
                    executableCode.getOrNull(match.range.first) == '.'
                }?.let { match -> add(feature, path, match.value.take(160)) }
            }
            if (structuralCode.contains("http://", ignoreCase = true)) add(VBookFeature.LEGACY_HTTP_SOURCE, path, "http:// literal")
            hit(VBookFeature.RESPONSE_HELPER, Regex("\\bResponse\\.(?:success|error)\\s*\\("))
            hit(VBookFeature.RESPONSE_LEGACY_CODE, Regex("\\bcode\\s*[:=]\\s*(?:200|403)\\b"))
            hit(VBookFeature.FETCH, Regex("\\bfetch\\s*\\("))
            hit(VBookFeature.FETCH_QUERIES, Regex("\\bqueries\\s*:"))
            hit(VBookFeature.FETCH_TIMEOUT, Regex("\\btimeout\\s*:"))
            hit(VBookFeature.FETCH_HEADER, Regex("\\.header\\s*\\("))
            hit(VBookFeature.FETCH_STATUS_TEXT, Regex("\\.statusText\\b"))
            hitLiteralArgument(VBookFeature.FETCH_CHARSET, Regex("\\.(?:text|html)\\s*\\(\\s*['\"][^'\"]+['\"]\\s*\\)"))
            hit(VBookFeature.FETCH_BASE64, Regex("\\.base64\\s*\\("))
            hit(VBookFeature.FETCH_BLOB, Regex("\\.blob\\s*\\("))
            hit(VBookFeature.FETCH_REQUEST_INFO, Regex("\\.request\\.(?:url|headers)\\b"))
            hit(VBookFeature.HTML_DOM, Regex("\\b(?:Html|HTML)\\.parse\\s*\\(|\\.select\\s*\\("))
            hit(VBookFeature.HTML_CLEAN, Regex("\\b(?:Html|HTML)\\.clean\\s*\\("))
            hit(VBookFeature.HTML_COLLECTION_CALLBACKS, Regex("\\.(?:forEach|map)\\s*\\("))
            hit(VBookFeature.HTML_MUTATION, Regex("\\.remove\\s*\\("))
            hit(VBookFeature.HTML_ATTRIBUTES, Regex("\\.attributes\\s*\\("))
            hit(VBookFeature.LOCAL_CONFIG, Regex("\\blocalConfig\\b"))
            hit(VBookFeature.LOCAL_STORAGE, Regex("\\blocalStorage\\b"))
            hit(VBookFeature.CACHE_STORAGE, Regex("\\bcacheStorage\\b"))
            hit(VBookFeature.LOCAL_COOKIE, Regex("\\blocalCookie\\b"))
            hit(VBookFeature.BROWSER, Regex("\\b(?:Engine\\.(?:newBrowser|browser)|Browser\\.)"))
            hit(VBookFeature.BROWSER_LOAD_HTML, Regex("\\.loadHtml\\s*\\("))
            hit(VBookFeature.BROWSER_WAIT_URL, Regex("\\.waitUrl\\s*\\("))
            hit(VBookFeature.BROWSER_REQUEST_METADATA, Regex("\\.(?:waitRequest|requests|urls)\\s*\\("))
            hit(VBookFeature.GRAPHICS, Regex("\\bGraphics\\."))
            hit(VBookFeature.WEBSOCKET, Regex("\\bWebSocket\\s*\\("))
            hit(VBookFeature.WEBSOCKET_HEADERS, websocketWithHeaders)
            hit(VBookFeature.WEBSOCKET_FRAMES, Regex("\\.message\\s*\\("))
            hit(VBookFeature.QUICK_TRANSLATOR, Regex("\\bQt\\.translate\\s*\\("))
            hit(VBookFeature.QUICK_TRANSLATOR_OPTIONS, quickTranslatorWithExtras)
            hit(VBookFeature.QUICK_TRANSLATOR_SEGMENTS, Regex("\\.segments\\b"))
            hit(VBookFeature.CRYPTO, Regex("\\b(?:CryptoJS|Crypto)\\b"))
            hit(VBookFeature.SCRIPT_EXECUTE, Regex("\\bScript\\.execute\\s*\\("))
            hit(VBookFeature.USER_AGENT, Regex("\\bUserAgent\\.(?:system|chrome|android|ios)\\s*\\("))
            hit(VBookFeature.SLEEP, Regex("\\bsleep\\s*\\("))
            hit(VBookFeature.LOGGING, Regex("\\b(?:Log|Console|console)\\.(?:log|info|warn|error|debug)\\s*\\("))
            hit(VBookFeature.JS_FORBIDDEN_ASYNC_AWAIT, Regex("\\b(?:async\\s+function|await\\s+)"))
            hit(VBookFeature.JS_FORBIDDEN_OPTIONAL_CHAINING, Regex("\\?\\.[A-Za-z_$\\[]"))
            hit(VBookFeature.JS_FORBIDDEN_NULLISH, Regex("\\?\\?"))
            hit(VBookFeature.JS_FORBIDDEN_SPREAD, Regex("\\.\\.\\.[A-Za-z_$\\[{]"))
            hit(VBookFeature.JS_FORBIDDEN_NAMED_CAPTURE, Regex("\\(\\?<([A-Za-z][A-Za-z0-9_]*)>"))
            hit(VBookFeature.JS_FORBIDDEN_LOOKBEHIND, Regex("\\(\\?<([=!])"))

            loadLiteral.findAll(structuralCode).forEach { match ->
                val target = match.groupValues[1]
                if (target.equals("crypto.js", ignoreCase = true)) {
                    add(VBookFeature.LOAD_CRYPTO_BUILTIN, path, target)
                } else {
                    add(VBookFeature.DYNAMIC_LOAD, path, target)
                }
            }
            dynamicObject.findAll(structuralCode).forEach { match ->
                val raw = match.groupValues[1]
                runCatching { VBookPaths.normalizeScriptPath(raw) }.getOrNull()?.let { target ->
                    dynamicScripts += target
                    add(VBookFeature.DYNAMIC_SCRIPT_REFERENCE, path, target)
                    if (Regex("\\bdata\\s*:").containsMatchIn(match.value)) {
                        add(VBookFeature.DYNAMIC_DATA_ARGUMENT, path, match.value)
                    }
                }
            }
        }

        val featuresBeforeComposite = evidence.mapTo(linkedSetOf(), VBookFeatureEvidence::feature)
        if (VBookFeature.LEGACY_HTTP_SOURCE in featuresBeforeComposite && VBookFeature.LOCAL_COOKIE in featuresBeforeComposite) {
            add(VBookFeature.LOCAL_COOKIE_CLEARTEXT, value = "legacy cleartext source uses localCookie")
        }

        val packagePaths = scripts.keys.mapTo(linkedSetOf()) { VBookPaths.normalizeScriptPath(it) }
        val missingDynamic = dynamicScripts - packagePaths
        val unknownRoles = manifest.scripts.keys.filterTo(linkedSetOf()) { VBookScriptRole.from(it) == null }
        return VBookExtensionAudit(
            id = id,
            manifest = manifest,
            detection = detection,
            features = evidence.mapTo(linkedSetOf(), VBookFeatureEvidence::feature),
            evidence = evidence.distinct(),
            missingRequiredScripts = VBookRequiredScripts.missing(manifest, detection.profile),
            referencedDynamicScripts = dynamicScripts,
            missingReferencedScripts = missingDynamic,
            unknownScriptRoles = unknownRoles,
        )
    }

    fun aggregate(audits: List<VBookExtensionAudit>): VBookCorpusReport {
        val rows = VBookFeature.entries.map { feature ->
            val ids = audits.filter { feature in it.features }.map(VBookExtensionAudit::id).sorted()
            VBookCorpusFeatureRow(feature, ids.size, ids)
        }.filter { it.extensionCount > 0 }
        return VBookCorpusReport(
            extensionCount = audits.size,
            profiles = audits.groupingBy { it.detection.profile }.eachCount(),
            contentTypes = audits.groupingBy { it.manifest.metadata.type }.eachCount(),
            features = rows,
            extensionsWithMissingRequiredScripts = audits.filter { it.missingRequiredScripts.isNotEmpty() }.map(VBookExtensionAudit::id).sorted(),
            extensionsWithMissingDynamicScripts = audits.filter { it.missingReferencedScripts.isNotEmpty() }.map(VBookExtensionAudit::id).sorted(),
        )
    }
}
