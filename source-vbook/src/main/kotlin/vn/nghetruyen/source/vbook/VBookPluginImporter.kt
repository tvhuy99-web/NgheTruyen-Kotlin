package vn.nghetruyen.source.vbook

import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceActionName
import vn.nghetruyen.source.api.SourceActionSpec
import vn.nghetruyen.source.api.SourceBrowserCapability
import vn.nghetruyen.source.api.SourceCapabilities
import vn.nghetruyen.source.api.SourceContentType
import vn.nghetruyen.source.api.SourceCookieMode
import vn.nghetruyen.source.api.SourceCryptoCapability
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkCapability
import vn.nghetruyen.source.api.SourcePrivacyDisclosure
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceRuntimePolicy
import vn.nghetruyen.source.packagekit.SourceManifestWriter
import java.net.URI
import java.util.Locale


data class LegacyVBookMetadata(
    val name: String,
    val id: String,
    val author: String,
    val version: String,
    val source: String,
    val regexp: String?,
    val description: String,
    val locale: String,
    val type: String,
)

data class VBookPlugin(
    val metadata: LegacyVBookMetadata,
    val scripts: Map<String, String>,
    val files: Map<String, ByteArray>,
    val config: Map<String, String> = emptyMap(),
)

data class VBookImportResult(
    val manifest: SourceManifest,
    val entries: Map<String, ByteArray>,
    val warnings: List<String>,
)


object VBookPluginImporter {
    private val DIRECT_ACTIONS = linkedMapOf(
        "search" to SourceActionName.SEARCH,
        "detail" to SourceActionName.DETAIL,
        "latest" to SourceActionName.LATEST_CHAPTER,
        "toc" to SourceActionName.TOC,
        "page" to SourceActionName.TOC_PAGES,
        "chap" to SourceActionName.CHAPTER,
        "comments" to SourceActionName.COMMENTS,
        "suggest" to SourceActionName.SUGGESTIONS,
        "login" to SourceActionName.LOGIN,
    )

    fun parse(pluginJson: ByteArray, files: Map<String, ByteArray>): VBookPlugin {
        require(pluginJson.size in 1..1024 * 1024) { "VBOOK_PLUGIN_MANIFEST_TOO_LARGE" }
        val root = JsonCodec.parse(pluginJson.toString(Charsets.UTF_8)) as? JsonValue.Obj ?: error("VBOOK_PLUGIN_NOT_OBJECT")
        val metadata = root.obj("metadata") ?: error("VBOOK_METADATA_REQUIRED")
        val scripts = root.obj("script") ?: error("VBOOK_SCRIPT_MAP_REQUIRED")
        val parsed = LegacyVBookMetadata(
            name = metadata.string("name")?.takeIf(String::isNotBlank) ?: error("VBOOK_NAME_REQUIRED"),
            id = metadata.string("id")?.takeIf(String::isNotBlank) ?: error("VBOOK_ID_REQUIRED"),
            author = metadata.string("author").orEmpty(),
            version = when (val raw = metadata["version"]) {
                is JsonValue.Str -> raw.value
                is JsonValue.Num -> raw.raw
                else -> "1"
            },
            source = metadata.string("source")?.takeIf(String::isNotBlank) ?: error("VBOOK_SOURCE_REQUIRED"),
            regexp = metadata.string("regexp"),
            description = metadata.string("description").orEmpty(),
            locale = metadata.string("locale") ?: "vi-VN",
            type = metadata.string("type") ?: "novel",
        )
        require(parsed.name.length <= 120 && parsed.author.length <= 120 && parsed.description.length <= 1000) { "VBOOK_METADATA_TOO_LONG" }
        val scriptMap = scripts.values.mapValues { (_, value) ->
            (value as? JsonValue.Str)?.value ?: error("VBOOK_SCRIPT_PATH_INVALID")
        }
        scriptMap.values.forEach(SourceManifest::requireSafeRelativePath)
        val normalizedFiles = files.mapKeys { (path, _) -> normalizePath(path) }
        scriptMap.values.forEach { script ->
            require(normalizedFiles.containsKey(normalizeScriptPath(script))) { "VBOOK_SCRIPT_MISSING:$script" }
        }
        val config = root.obj("config")?.values.orEmpty().entries.take(256).associate { (key, value) ->
            require(key.length in 1..128) { "VBOOK_CONFIG_KEY_INVALID" }
            key to when (value) {
                is JsonValue.Str -> value.value
                JsonValue.Null -> ""
                else -> JsonCodec.stringify(value)
            }
        }
        return VBookPlugin(parsed, scriptMap, normalizedFiles, config)
    }

    fun import(plugin: VBookPlugin): VBookImportResult {
        val sourceUri = runCatching { URI(plugin.metadata.source) }.getOrNull() ?: error("VBOOK_SOURCE_URL_INVALID")
        require(sourceUri.scheme == "https" && !sourceUri.host.isNullOrBlank()) { "VBOOK_SOURCE_URL_INVALID" }
        val sourceId = normalizeId(plugin.metadata.id)
        val actions = linkedMapOf<SourceActionName, SourceActionSpec>()
        fun install(action: SourceActionName, scriptName: String?) {
            val script = scriptName?.let(plugin.scripts::get) ?: return
            actions[action] = SourceActionSpec(normalizeScriptPath(script), maxOutputBytes = 2 * 1024 * 1024)
        }
        install(SourceActionName.HOME, if (plugin.scripts.containsKey("homecontent")) "homecontent" else "home")
        install(SourceActionName.GENRE, if (plugin.scripts.containsKey("genrecontent")) "genrecontent" else "genre")
        DIRECT_ACTIONS.forEach { (name, action) -> install(action, name) }
        require(actions.keys.containsAll(setOf(SourceActionName.DETAIL, SourceActionName.TOC, SourceActionName.CHAPTER))) {
            "VBOOK_REQUIRED_ACTION_MISSING"
        }
        val warnings = buildList {
            if (plugin.scripts.containsKey("homecontent")) add("homecontent được định tuyến qua HOME bằng input script.")
            if (plugin.scripts.containsKey("genrecontent")) add("genrecontent được định tuyến qua GENRE bằng input script.")
            add("Đây là đường migration SourcePack cũ; cài vBook mới dùng raw artifact subsystem.")
        }
        val version = normalizeVersion(plugin.metadata.version)
        val manifest = SourceManifest(
            schemaVersion = 2,
            id = sourceId,
            name = plugin.metadata.name,
            description = plugin.metadata.description,
            author = plugin.metadata.author,
            version = version,
            apiVersion = 2,
            locale = plugin.metadata.locale,
            contentType = when (plugin.metadata.type.lowercase(Locale.ROOT)) {
                "comic" -> SourceContentType.COMIC
                "audio" -> SourceContentType.AUDIO
                else -> SourceContentType.NOVEL
            },
            runtime = SourceRuntimePolicy(
                mode = SourceRuntimeMode.VBOOK_JS_COMPAT,
                entry = "plugin.json",
                instructionBudget = 500_000,
                memoryBudgetBytes = 32 * 1024 * 1024,
                actionTimeoutMs = 45_000,
            ),
            urlPatterns = plugin.metadata.regexp?.let(::listOf).orEmpty(),
            origins = setOf(origin(sourceUri)),
            redirectOrigins = setOf(origin(sourceUri)),
            capabilities = SourceCapabilities(
                network = SourceNetworkCapability(setOf("GET", "HEAD", "POST"), maxResponseBytes = 8 * 1024 * 1024, maxRequestBytes = 512 * 1024, requestsPerMinute = 90, maxConcurrent = 2),
                cookies = SourceCookieMode.BROWSER_SHARED,
                browser = SourceBrowserCapability(navigate = true, domSnapshot = true, click = true, input = true, requestMetadata = true, serviceWorkerCapture = true, pageJavaScript = true),
                storageBytes = 2 * 1024 * 1024,
                crypto = setOf(
                    SourceCryptoCapability.MD5,
                    SourceCryptoCapability.SHA1,
                    SourceCryptoCapability.SHA256,
                    SourceCryptoCapability.SHA512,
                    SourceCryptoCapability.HMAC_MD5,
                    SourceCryptoCapability.HMAC_SHA1,
                    SourceCryptoCapability.HMAC_SHA256,
                    SourceCryptoCapability.HMAC_SHA512,
                    SourceCryptoCapability.AES_COMPAT,
                    SourceCryptoCapability.AES_GCM_SECRET,
                ),
                websocket = vn.nghetruyen.source.api.SourceWebSocketCapability(enabled = true),
            ),
            actions = actions,
            privacy = SourcePrivacyDisclosure(
                sendsContentToThirdParty = true,
                thirdParties = listOf("Nhà cung cấp AI do người dùng cấu hình"),
                note = "Extension migration cũ có thể gọi Qt.translate; raw vBook runtime áp dụng broker riêng.",
            ),
            fixtures = emptyList(),
        ).also(SourceManifest::validate)
        val entries = linkedMapOf<String, ByteArray>()
        entries["source.json"] = SourceManifestWriter.write(manifest)
        entries["plugin.json"] = pluginManifestJson(plugin).toByteArray(Charsets.UTF_8)
        plugin.files.forEach { (path, bytes) -> entries[normalizePath(path)] = bytes }
        return VBookImportResult(manifest, entries, warnings)
    }

    private fun pluginManifestJson(plugin: VBookPlugin): String = JsonCodec.stringify(JsonValue.Obj(linkedMapOf(
        "metadata" to JsonValue.Obj(linkedMapOf(
            "name" to JsonValue.Str(plugin.metadata.name),
            "id" to JsonValue.Str(plugin.metadata.id),
            "author" to JsonValue.Str(plugin.metadata.author),
            "version" to JsonValue.Str(plugin.metadata.version),
            "source" to JsonValue.Str(plugin.metadata.source),
            "regexp" to (plugin.metadata.regexp?.let(JsonValue::Str) ?: JsonValue.Null),
            "description" to JsonValue.Str(plugin.metadata.description),
            "locale" to JsonValue.Str(plugin.metadata.locale),
            "type" to JsonValue.Str(plugin.metadata.type),
        )),
        "script" to JsonValue.Obj(LinkedHashMap(plugin.scripts.mapValues { JsonValue.Str(it.value) })),
        "config" to JsonValue.Obj(LinkedHashMap(plugin.config.mapValues { JsonValue.Str(it.value) })),
    )))

    private fun normalizePath(raw: String): String {
        val clean = raw.replace('\\', '/').removePrefix("/")
        SourceManifest.requireSafeRelativePath(clean)
        return clean
    }

    private fun normalizeScriptPath(raw: String): String = normalizePath(if (raw.startsWith("src/")) raw else "src/$raw")

    private fun normalizeId(raw: String): String {
        val parts = raw.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9.-]+"), "-").trim('-', '.')
        val candidate = if (parts.count { it == '.' } >= 2) parts else "vn.nghetruyen.vbook.${parts.ifBlank { "source" }}"
        return candidate.split('.').joinToString(".") { segment -> segment.trim('-').ifBlank { "source" } }
    }

    private fun normalizeVersion(raw: String): SemanticVersion = runCatching { SemanticVersion.parse(raw) }
        .getOrElse {
            val major = raw.trim().toIntOrNull() ?: 1
            SemanticVersion(major.coerceAtLeast(0), 0, 0)
        }

    private fun origin(uri: URI): String = "https://${uri.host}${if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"}"
}
