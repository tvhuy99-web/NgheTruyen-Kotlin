package vn.nghetruyen.source.lua

import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourceBrowserCapability
import vn.nghetruyen.source.api.SourceCapabilities
import vn.nghetruyen.source.api.SourceCookieMode
import vn.nghetruyen.source.api.SourceCryptoCapability
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourceNetworkCapability
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceRuntimePolicy
import vn.nghetruyen.source.packagekit.SourceManifestWriter
import vn.nghetruyen.source.vbook.VBookPluginImporter
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

object NativeLuaSourceImporter {
    private const val NATIVE_API_MODULE = "app.sources.native_api"
    private const val NATIVE_API_RESOURCE = "/vn/nghetruyen/source/lua/native_api.lua"
    private const val ADAPTER_RESOURCE = "/vn/nghetruyen/source/lua/native_v2_adapter.lua"

    fun import(
        sourceBytes: ByteArray,
        archiveFiles: Map<String, ByteArray> = mapOf("source.lua" to sourceBytes),
        entryPath: String = "source.lua",
    ): NativeLuaImportResult {
        require(sourceBytes.size in 1..LuaSandbox.MAX_SOURCE_BYTES) { "NATIVE_LUA_SOURCE_TOO_LARGE" }
        require(archiveFiles.isNotEmpty() && archiveFiles.size <= 256) { "NATIVE_LUA_ARCHIVE_ENTRY_LIMIT" }
        require(archiveFiles.values.sumOf { it.size.toLong() } <= 48L * 1024L * 1024L) { "NATIVE_LUA_ARCHIVE_INFLATED_LIMIT" }
        require(!sourceBytes.copyOfRange(0, minOf(4, sourceBytes.size)).contentEquals(byteArrayOf(0x1b, 0x4c, 0x75, 0x61))) {
            "NATIVE_LUA_BYTECODE_DENIED"
        }
        val sourceText = decodeUtf8(sourceBytes)
        require('\u0000' !in sourceText) { "NATIVE_LUA_SOURCE_INVALID" }
        val nativeApi = resourceText(NATIVE_API_RESOURCE)
        val adapterSource = resourceText(ADAPTER_RESOURCE)
        val moduleBundle = buildModuleBundle(archiveFiles, entryPath)
        val sourceSandbox = LuaSandbox(
            modules = mapOf(NATIVE_API_MODULE to nativeApi) + moduleBundle.sources,
            instructionBudget = 500_000,
            timeoutMs = 20_000,
            memoryBudgetBytes = 48 * 1024 * 1024,
        )
        val packageValue = sourceSandbox.evaluate(sourceText, "@native/$entryPath")
        require(packageValue.istable()) { "NATIVE_LUA_PACKAGE_TABLE_REQUIRED" }
        // Strip metatables and reject cyclic/shared object graphs before trusted validation.
        val packageTable = sanitizePackage(packageValue).checktable()
        require(packageTable.get("api_version").optint(0) == 2 || packageTable.get("native_source_api").optint(0) == 2) {
            "NATIVE_LUA_API_VERSION_UNSUPPORTED"
        }

        // Validation and adapter generation use fresh globals. An extension cannot replace
        // require/type/pairs in its own environment and influence these trusted phases.
        val validationSandbox = LuaSandbox(
            modules = mapOf(NATIVE_API_MODULE to nativeApi),
            instructionBudget = 500_000,
            timeoutMs = 20_000,
        )
        val nativeApiTable = validationSandbox.requireModule(NATIVE_API_MODULE)
        val validation = nativeApiTable.get("validatePackage").invoke(packageTable)
        require(validation.arg1().toboolean()) {
            validation.arg(2).optjstring("NATIVE_LUA_PACKAGE_INVALID")
        }
        val metadata = packageTable.get("metadata").checktable()
        val source = packageTable.get("source").checktable()
        val adapterSandbox = LuaSandbox(
            modules = mapOf(NATIVE_API_MODULE to nativeApi),
            instructionBudget = 500_000,
            timeoutMs = 20_000,
        )
        val adapter = adapterSandbox.evaluate(adapterSource, "@native/native_v2_adapter.lua")
        val built = adapter.get("build").call(source, metadata, LuaTable()).checktable()
        val manifestJson = built.get("manifest_json").checkjstring()
        val filesJson = built.get("files_json").checkjstring()
        require(manifestJson.toByteArray().size <= 1024 * 1024) { "NATIVE_LUA_MANIFEST_TOO_LARGE" }
        require(filesJson.toByteArray().size <= 12 * 1024 * 1024) { "NATIVE_LUA_GENERATED_FILES_TOO_LARGE" }

        val filesRoot = JsonCodec.parse(filesJson, maxDepth = 96, maxNodes = 200_000) as? JsonValue.Obj
            ?: error("NATIVE_LUA_GENERATED_FILES_INVALID")
        val files = filesRoot.values.mapValues { (_, value) ->
            (value as? JsonValue.Str)?.value?.toByteArray(Charsets.UTF_8)
                ?: error("NATIVE_LUA_GENERATED_FILE_INVALID")
        }
        val plugin = VBookPluginImporter.parse(manifestJson.toByteArray(Charsets.UTF_8), files)
        val vBook = VBookPluginImporter.import(plugin)
        val baseUrl = firstNonBlank(
            source.get("base_url").optjstring(""),
            source.get("home_url").optjstring(""),
            metadata.get("website").optjstring(""),
            plugin.metadata.source,
        )
        val baseUri = URI(baseUrl)
        require(baseUri.scheme == "https" && !baseUri.host.isNullOrBlank()) { "NATIVE_LUA_BASE_URL_INVALID" }
        val permissions = source.get("permissions").let { if (it.istable()) it.checktable() else LuaTable() }
        val hosts = linkedSetOf<String>().apply {
            add(baseUri.host.lowercase(Locale.ROOT))
            addAll(stringList(source.get("allowed_hosts")))
            addAll(stringList(permissions.get("hosts")))
        }.map(::normalizeHost).filter(String::isNotBlank).toSet()
        require(hosts.isNotEmpty() && hosts.size <= 64) { "NATIVE_LUA_ALLOWED_HOSTS_INVALID" }
        val browserEnabled = permissions.get("browser").optboolean(false)
        val captureEnabled = permissions.get("network_capture").optboolean(false)
        val storageEnabled = permissions.get("storage").optboolean(false) || source.get("config").istable()
        val origins = hosts.mapTo(linkedSetOf()) { "https://$it" }
        val nativeId = normalizeNativeId(metadata.get("id").optjstring(plugin.metadata.id))
        val runtime = SourceRuntimePolicy(
            mode = SourceRuntimeMode.NATIVE_LUA_COMPAT,
            entry = "native/source.lua",
            instructionBudget = 500_000,
            memoryBudgetBytes = 32 * 1024 * 1024,
            actionTimeoutMs = 50_000,
        )
        val manifest = vBook.manifest.copy(
            id = nativeId,
            runtime = runtime,
            origins = origins,
            redirectOrigins = origins,
            capabilities = SourceCapabilities(
                network = SourceNetworkCapability(
                    methods = setOf("GET", "HEAD", "POST"),
                    maxResponseBytes = 8 * 1024 * 1024,
                    maxRequestBytes = 512 * 1024,
                    requestsPerMinute = 90,
                    maxConcurrent = 2,
                ),
                cookies = SourceCookieMode.BROWSER_SHARED,
                browser = SourceBrowserCapability(
                    navigate = browserEnabled,
                    domSnapshot = browserEnabled,
                    click = browserEnabled,
                    input = browserEnabled,
                    requestMetadata = captureEnabled,
                    serviceWorkerCapture = captureEnabled,
                    pageJavaScript = browserEnabled,
                ),
                storageBytes = if (storageEnabled) 2 * 1024 * 1024 else 256 * 1024,
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
        ).also(SourceManifest::validate)
        val entries = LinkedHashMap(vBook.entries)
        entries["native/source.lua"] = sourceBytes
        archiveFiles.forEach { (path, bytes) ->
            if (path != entryPath) entries["native/archive/$path"] = bytes
        }
        entries["data/native-module-index.json"] = JsonCodec.stringify(JsonValue.Obj(linkedMapOf(
            "entryPath" to JsonValue.Str(entryPath),
            "modules" to JsonValue.Obj(LinkedHashMap(moduleBundle.entryPaths.mapValues { JsonValue.Str(it.value) })),
            "resources" to JsonValue.Obj(LinkedHashMap(
                archiveFiles.keys.filterNot { it.endsWith(".lua", true) }.sorted().associateWith { JsonValue.Str("native/archive/$it") },
            )),
        ))).toByteArray(Charsets.UTF_8)
        entries["data/native-source-info.json"] = JsonCodec.stringify(JsonValue.Obj(linkedMapOf(
            "nativeApiVersion" to JsonValue.Num(2.0, "2"),
            "engine" to JsonValue.Str(built.get("runtime").optjstring("native-api-2")),
            "allowedHosts" to JsonValue.Arr(hosts.sorted().map(JsonValue::Str)),
            "browser" to JsonValue.Bool(browserEnabled),
            "networkCapture" to JsonValue.Bool(captureEnabled),
            "hasComments" to JsonValue.Bool(manifest.actions.keys.any { it.name == "COMMENTS" }),
            "archiveEntryCount" to JsonValue.Num(archiveFiles.size.toDouble(), archiveFiles.size.toString()),
            "moduleCount" to JsonValue.Num(moduleBundle.entryPaths.size.toDouble(), moduleBundle.entryPaths.size.toString()),
        ))).toByteArray(Charsets.UTF_8)
        entries["source.json"] = SourceManifestWriter.write(manifest)
        val warnings = buildList {
            addAll(vBook.warnings)
            add("Native Source API 2 chạy trong LuaJ sandbox; luajava, io, os, debug, package và bytecode bị vô hiệu hóa.")
            if (archiveFiles.size > 1) add("Đã giữ ${archiveFiles.size} tệp trong package và ánh xạ ${moduleBundle.entryPaths.size} alias require() an toàn.")
            if (source.get("hooks").istable()) add("Pure Lua hooks được chạy theo từng lần gọi với ngân sách lệnh, bộ nhớ và giới hạn đầu ra.")
            if (browserEnabled) add("Dynamic Web dùng WebView tuần tự với origin allowlist và không cho truy cập file/content URI.")
        }
        return NativeLuaImportResult(manifest, entries, warnings)
    }


    private data class ModuleBundle(
        val sources: Map<String, String>,
        val entryPaths: Map<String, String>,
    )

    private fun buildModuleBundle(files: Map<String, ByteArray>, entryPath: String): ModuleBundle {
        val rootDir = entryPath.substringBeforeLast('/', "")
        val sources = linkedMapOf<String, String>()
        val entryPaths = linkedMapOf<String, String>()
        files.entries.filter { it.key.endsWith(".lua", true) && it.key != entryPath }.forEach { (path, bytes) ->
            val text = decodeUtf8(bytes)
            require('\u0000' !in text && bytes.size <= LuaSandbox.MAX_SOURCE_BYTES) { "NATIVE_LUA_MODULE_INVALID:$path" }
            val storedPath = "native/archive/$path"
            moduleAliases(path, rootDir).forEach { alias ->
                if (alias.isNotBlank() && alias != NATIVE_API_MODULE && alias !in sources) {
                    sources[alias] = text
                    entryPaths[alias] = storedPath
                }
            }
        }
        return ModuleBundle(sources, entryPaths)
    }

    private fun moduleAliases(path: String, rootDir: String): List<String> {
        val normalized = path.removeSuffix(".lua").replace('/', '.')
        val relative = if (rootDir.isNotBlank() && path.startsWith("$rootDir/")) {
            path.removePrefix("$rootDir/").removeSuffix(".lua").replace('/', '.')
        } else normalized
        val basename = path.substringAfterLast('/').removeSuffix(".lua")
        val parentAlias = if (path.endsWith("/init.lua", true)) path.substringBeforeLast('/').replace('/', '.') else ""
        return linkedSetOf(normalized, relative, basename, parentAlias).filter(String::isNotBlank)
    }


    private fun decodeUtf8(bytes: ByteArray): String = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrElse { error("NATIVE_LUA_SOURCE_UTF8_INVALID") }

    private fun sanitizePackage(value: LuaValue): LuaValue = sanitizeValue(
        value = value,
        depth = 0,
        nodes = AtomicInteger(),
        seen = IdentityHashMap(),
    )

    private fun sanitizeValue(
        value: LuaValue,
        depth: Int,
        nodes: AtomicInteger,
        seen: IdentityHashMap<LuaValue, Boolean>,
    ): LuaValue {
        require(depth <= 96) { "NATIVE_LUA_PACKAGE_TOO_DEEP" }
        require(nodes.incrementAndGet() <= 100_000) { "NATIVE_LUA_PACKAGE_TOO_COMPLEX" }
        return when {
            value.isnil() -> LuaValue.NIL
            value.isboolean() -> LuaValue.valueOf(value.toboolean())
            value.isnumber() -> LuaValue.valueOf(value.todouble())
            value.isstring() -> LuaValue.valueOf(value.tojstring())
            value.isfunction() -> value // Validator checks hooks by type; adapter never executes them.
            value.istable() -> {
                require(seen.put(value, true) == null) { "NATIVE_LUA_PACKAGE_CYCLIC" }
                try {
                    LuaTable().also { copy ->
                        value.checktable().keys().forEach { key ->
                            val cleanKey = when {
                                key.isint() -> LuaValue.valueOf(key.toint())
                                key.isstring() -> LuaValue.valueOf(key.tojstring())
                                else -> error("NATIVE_LUA_PACKAGE_KEY_INVALID")
                            }
                            copy.set(cleanKey, sanitizeValue(value.get(key), depth + 1, nodes, seen))
                        }
                    }
                } finally {
                    seen.remove(value)
                }
            }
            else -> error("NATIVE_LUA_PACKAGE_VALUE_UNSUPPORTED")
        }
    }

    private fun resourceText(path: String): String = NativeLuaSourceImporter::class.java.getResourceAsStream(path)
        ?.use { it.readBytes().toString(Charsets.UTF_8) }
        ?: error("NATIVE_LUA_RUNTIME_RESOURCE_MISSING:$path")

    private fun stringList(value: LuaValue): List<String> = when {
        value.isnil() -> emptyList()
        value.isstring() -> listOf(value.tojstring())
        value.istable() -> (1..value.length()).mapNotNull { index -> value.get(index).takeIf(LuaValue::isstring)?.tojstring() }
        else -> emptyList()
    }

    private fun normalizeHost(raw: String): String = raw.trim().lowercase(Locale.ROOT)
        .removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':')
        .removePrefix("www.").trimEnd('.')
        .also { host -> require(HOST.matches(host)) { "NATIVE_LUA_HOST_INVALID:$raw" } }

    private fun normalizeNativeId(raw: String): String {
        val slug = raw.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9.-]+"), "-").trim('-', '.')
            .split('.').filter(String::isNotBlank).joinToString(".") { it.trim('-').ifBlank { "source" } }
        return "vn.nghetruyen.native.${slug.ifBlank { "source" }}"
    }

    private fun firstNonBlank(vararg values: String): String = values.firstOrNull(String::isNotBlank).orEmpty()

    private val HOST = Regex("^[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?$")
}

data class NativeLuaImportResult(
    val manifest: SourceManifest,
    val entries: Map<String, ByteArray>,
    val warnings: List<String>,
)
