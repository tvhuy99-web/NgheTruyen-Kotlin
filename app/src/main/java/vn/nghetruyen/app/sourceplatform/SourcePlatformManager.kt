package vn.nghetruyen.app.sourceplatform

import android.content.Context
import vn.nghetruyen.app.sources.SourceSessionStore
import vn.nghetruyen.app.ai.TranslationEngine
import vn.nghetruyen.app.sources.StorySource
import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourcePermissionDiff
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceCapabilityBrokers
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.network.OkHttpSourceWebSocketBroker
import vn.nghetruyen.source.network.PartitionedSourceCookieJar
import vn.nghetruyen.source.runtime.FileSourceStorageBroker
import vn.nghetruyen.source.runtime.JcaSourceCryptoBroker
import vn.nghetruyen.source.vbook.VBookArchiveImporter
import vn.nghetruyen.source.lua.LuaNativeHookBroker
import vn.nghetruyen.source.lua.NativeLuaArchiveImporter
import vn.nghetruyen.source.vbook.VBookJsRuntime
import vn.nghetruyen.source.diagnostics.BoundedDiagnosticRecorder
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticJsonExporter
import vn.nghetruyen.source.diagnostics.DiagnosticLevel
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import vn.nghetruyen.source.diagnostics.SourceTraceExplorer
import vn.nghetruyen.source.network.OkHttpSourceNetworkBroker
import vn.nghetruyen.source.packagekit.SourcePackArchiveVerifier
import vn.nghetruyen.source.packagekit.VerifiedSourcePack
import vn.nghetruyen.source.repository.SourceRepositoryCatalog
import vn.nghetruyen.source.repository.SourceRepositoryPackageStatus
import vn.nghetruyen.source.repository.SourceRepositoryVerifier
import vn.nghetruyen.source.repository.VerifiedSourceRepository
import vn.nghetruyen.source.runtime.DeclarativeSourceRuntime
import vn.nghetruyen.source.runtime.MapSourceResourceProvider
import vn.nghetruyen.source.runtime.SourceFixtureExecutor
import vn.nghetruyen.source.runtime.SourceFixtureRunner
import vn.nghetruyen.source.store.SourcePackStore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

class SourcePlatformManager(
    context: Context,
    sourceSessionStore: SourceSessionStore,
    translationEngine: TranslationEngine,
) {
    private val appContext = context.applicationContext
    private val platformRoot = appContext.filesDir.resolve("source-platform-v2")
    private val repositoryRoot = platformRoot.resolve("repositories").also(File::mkdirs)
    private val diagnostics = BoundedDiagnosticRecorder(maxEvents = 8_000, level = DiagnosticLevel.BASIC)
    private val trustRegistry = SourceTrustRegistry(appContext)
    private val verifier = SourcePackArchiveVerifier(diagnostics = diagnostics)
    private val store = SourcePackStore(platformRoot, diagnostics)
    private val cookieJar = PartitionedSourceCookieJar(EncryptedSourceCookiePersistence(appContext))
    private val networkBroker = OkHttpSourceNetworkBroker(cookiePartition = cookieJar, diagnostics = diagnostics)
    private val browserBroker = AndroidSourceBrowserBroker(appContext, cookieJar, diagnostics)
    private val storageBroker = FileSourceStorageBroker(platformRoot.resolve("storage"))
    private val cryptoBroker = JcaSourceCryptoBroker(AndroidSourceSecretKeyProvider())
    private val webSocketBroker = OkHttpSourceWebSocketBroker(cookieJar, diagnostics)
    private val nativeHookBroker = LuaNativeHookBroker()
    private val graphicsBroker = AndroidSourceGraphicsBroker()
    private val translationBroker = AndroidSourceTranslationBroker(translationEngine)
    private val genericCommentLoader = GenericStoryCommentLoader(networkBroker, browserBroker)
    private val brokers = SourceCapabilityBrokers(
        network = networkBroker,
        browser = browserBroker,
        storage = storageBroker,
        crypto = cryptoBroker,
        websocket = webSocketBroker,
        nativeHooks = nativeHookBroker,
        graphics = graphicsBroker,
        translation = translationBroker,
        cookies = cookieJar,
    )
    private val declarativeRuntime = DeclarativeSourceRuntime(
        diagnostics = diagnostics,
        networkBroker = networkBroker,
        browserBroker = browserBroker,
        storageBroker = storageBroker,
        cryptoBroker = cryptoBroker,
        webSocketBroker = webSocketBroker,
    )
    private val vBookRuntime = VBookJsRuntime(brokers, diagnostics)
    private val executor = SourcePackActionExecutor { pack, resources, request ->
        when (pack.manifest.runtime.mode) {
            SourceRuntimeMode.DECLARATIVE -> declarativeRuntime.execute(pack.manifest, resources, request)
            SourceRuntimeMode.VBOOK_JS_COMPAT,
            SourceRuntimeMode.NATIVE_LUA_COMPAT -> vBookRuntime.execute(pack.manifest, resources, request)
        }
    }
    private val fixtureRunner = SourceFixtureRunner(
        SourceFixtureExecutor { manifest, resources, request, replayNetwork ->
            when (manifest.runtime.mode) {
                SourceRuntimeMode.DECLARATIVE -> declarativeRuntime.execute(
                    manifest, resources, request, replayNetwork,
                )
                SourceRuntimeMode.VBOOK_JS_COMPAT,
                SourceRuntimeMode.NATIVE_LUA_COMPAT -> VBookJsRuntime(
                    brokers.copy(network = replayNetwork ?: vn.nghetruyen.source.api.SourceNetworkBroker.DENY_ALL),
                    diagnostics,
                ).execute(manifest, resources, request)
            }
        },
        diagnostics,
    )
    private val repositoryVerifier = SourceRepositoryVerifier(diagnostics)
    private val repositoryHttpClient = SourceRepositoryHttpClient()
    private val repositories = linkedMapOf<String, CachedRepository>()
    private var pendingPack: VerifiedSourcePack? = null
    private var pendingWarnings: List<String> = emptyList()

    init {
        loadCachedRepositories()
        bootstrapBuiltinPack()
    }

    fun activeStorySources(): List<StorySource> = store.list()
        .filter { it.enabled && it.active != null }
        .mapNotNull { installed -> store.readActivePack(installed.sourceId) }
        .map { pack -> SourcePackStorySource(pack, executor, genericCommentLoader) }

    fun installedPacks(): List<SourcePackUiInfo> = store.list().map { installed ->
        val active = installed.active
        SourcePackUiInfo(
            id = installed.sourceId,
            name = active?.manifest?.name ?: installed.sourceId,
            version = installed.activeVersion?.toString().orEmpty(),
            enabled = installed.enabled,
            installedVersions = installed.versions.map { it.manifest.version.toString() },
            canRollback = installed.versions.size > 1,
            signerKeyId = active?.signerKeyId.orEmpty(),
            runtimeMode = active?.manifest?.runtime?.mode?.name.orEmpty(),
            commentCapability = when {
                active == null -> "NONE"
                vn.nghetruyen.source.api.SourceActionName.COMMENTS in active.manifest.actions -> "PAGED"
                active.manifest.capabilities.browser.navigate -> "DYNAMIC_BROWSER_FALLBACK"
                else -> "DIRECT_HTML_FALLBACK"
            },
            commentFixtureCount = active?.manifest?.fixtures?.count { it.action == vn.nghetruyen.source.api.SourceActionName.COMMENTS } ?: 0,
        )
    }

    fun repositories(): List<SourceRepositoryUiInfo> = repositories.values.map { cached ->
        val index = cached.repository.index
        SourceRepositoryUiInfo(
            id = index.repositoryId,
            name = index.name,
            url = cached.url,
            generatedAtEpochMs = index.generatedAtEpochMs,
            expiresAtEpochMs = index.expiresAtEpochMs,
            packageCount = index.packages.size,
            signerKeyId = index.signerKeyId,
        )
    }.sortedBy(SourceRepositoryUiInfo::name)

    fun repositoryPackages(): List<SourceRepositoryPackageUiInfo> {
        val installed = store.list().mapNotNull { source -> source.activeVersion?.let { source.sourceId to it } }.toMap()
        return repositories.values.flatMap { cached ->
            SourceRepositoryCatalog.compare(cached.repository.index, installed, CURRENT_APP_VERSION).map { view ->
                SourceRepositoryPackageUiInfo(
                    repositoryId = cached.repository.index.repositoryId,
                    sourceId = view.entry.sourceId,
                    name = view.entry.name,
                    version = view.entry.version.toString(),
                    installedVersion = view.installedVersion?.toString(),
                    description = view.entry.description,
                    changelog = view.entry.changelog,
                    packageBytes = view.entry.packageBytes,
                    status = view.status.name,
                    canInstall = view.status == SourceRepositoryPackageStatus.NOT_INSTALLED ||
                        view.status == SourceRepositoryPackageStatus.UPDATE_AVAILABLE,
                )
            }
        }.sortedWith(compareBy<SourceRepositoryPackageUiInfo> { it.status }.thenBy { it.name })
    }

    fun refreshRepository(url: String): Result<SourceRepositoryUiInfo> = runCatching {
        val normalized = normalizeRepositoryUrl(url)
        val raw = repositoryHttpClient.fetchIndex(normalized, SourceRepositoryVerifier.MAX_INDEX_BYTES)
        val verified = when (val result = repositoryVerifier.verify(raw, trustRegistry.allKeys())) {
            is SourcePlatformResult.Success -> result.value
            is SourcePlatformResult.Failure -> error("${result.error.code}: ${result.error.message}")
        }
        rememberRepository(normalized, raw, verified)
        repositories().first { it.id == verified.index.repositoryId }
    }

    fun removeRepository(repositoryId: String): Result<Unit> = runCatching {
        require(REPOSITORY_ID.matches(repositoryId)) { "Repository ID không hợp lệ." }
        repositories.remove(repositoryId) ?: error("Repository chưa được thêm.")
        repositoryDirectory(repositoryId).deleteRecursively()
    }

    fun prepareRepositoryInstall(repositoryId: String, sourceId: String): Result<SourceInstallPreview> = runCatching {
        val cached = repositories[repositoryId] ?: error("Repository chưa được tải hoặc đã hết hạn.")
        val entry = cached.repository.index.packages.firstOrNull { it.sourceId == sourceId }
            ?: error("Không tìm thấy gói nguồn trong repository.")
        val minAppVersion = entry.minAppVersion
        val maxAppVersion = entry.maxAppVersion
        val compatible = (minAppVersion == null || CURRENT_APP_VERSION >= minAppVersion) &&
            (maxAppVersion == null || CURRENT_APP_VERSION <= maxAppVersion)
        require(compatible) { "Phiên bản nguồn không tương thích với ứng dụng hiện tại." }
        val bytes = repositoryHttpClient.fetchPackage(
            url = entry.packageUrl,
            maxBytes = SourceRepositoryVerifier.MAX_PACKAGE_BYTES,
            expectedBytes = entry.packageBytes,
            expectedSha256 = entry.packageSha256,
        )
        val pack = when (val result = ByteArrayInputStream(bytes).use { verifier.verify(it, trustRegistry.allKeys()) }) {
            is SourcePlatformResult.Success -> result.value
            is SourcePlatformResult.Failure -> error("${result.error.code}: ${result.error.message}")
        }
        require(pack.manifest.id == entry.sourceId) { "Repository và gói nguồn không cùng sourceId." }
        require(pack.manifest.version == entry.version) { "Repository và gói nguồn không cùng phiên bản." }
        require(pack.packageSha256 == entry.packageSha256) { "Hash gói nguồn không khớp repository." }
        preparePack(pack)
    }

    fun prepareInstall(input: InputStream): Result<SourceInstallPreview> = runCatching {
        val pack = when (val result = verifier.verify(input, trustRegistry.allKeys())) {
            is SourcePlatformResult.Success -> result.value
            is SourcePlatformResult.Failure -> error("${result.error.code}: ${result.error.message}")
        }
        preparePack(pack)
    }

    fun prepareVBookImport(input: InputStream): Result<SourceInstallPreview> = runCatching {
        val (pack, warnings) = VBookArchiveImporter.import(input)
        pendingWarnings = warnings
        preparePack(pack)
    }

    fun prepareNativeLuaImport(input: InputStream): Result<SourceInstallPreview> = runCatching {
        val (pack, warnings) = NativeLuaArchiveImporter.import(input)
        pendingWarnings = warnings
        preparePack(pack)
    }

    fun pendingInstallWarnings(): List<String> = pendingWarnings

    fun trustKeys(): List<SourceTrustKeyUi> = trustRegistry.userKeys()

    fun enrollTrustKey(keyId: String, algorithm: String, publicKeyBase64: String, fingerprint: String): Result<SourceTrustKeyUi> =
        trustRegistry.enroll(keyId, algorithm, publicKeyBase64, fingerprint)

    fun revokeTrustKey(keyId: String): Result<Unit> = trustRegistry.revoke(keyId)

    fun applyTrustKeyRotation(raw: ByteArray): Result<SourceTrustKeyUi> = trustRegistry.applyRotation(raw)

    fun confirmPendingInstall(): Result<SourcePackUiInfo> = runCatching {
        val pack = pendingPack ?: error("Không có gói nguồn đang chờ xác nhận.")
        val installed = when (val result = store.install(pack, activate = true)) {
            is SourcePlatformResult.Success -> result.value
            is SourcePlatformResult.Failure -> error("${result.error.code}: ${result.error.message}")
        }
        pendingPack = null
        pendingWarnings = emptyList()
        installedPacks().first { it.id == installed.sourceId }
    }

    fun cancelPendingInstall() {
        pendingPack = null
        pendingWarnings = emptyList()
    }

    fun setEnabled(sourceId: String, enabled: Boolean): Result<Unit> = runCatching {
        when (val result = store.setEnabled(sourceId, enabled)) {
            is SourcePlatformResult.Success -> Unit
            is SourcePlatformResult.Failure -> error(result.error.message)
        }
    }

    fun rollback(sourceId: String): Result<Unit> = runCatching {
        when (val result = store.rollback(sourceId)) {
            is SourcePlatformResult.Success -> Unit
            is SourcePlatformResult.Failure -> error(result.error.message)
        }
    }

    fun diagnosticsSnapshot(sourceId: String? = null): List<DiagnosticEvent> = diagnostics.snapshot(sourceId)

    fun diagnosticTraces(limit: Int = 20): List<SourceTraceUi> = SourceTraceExplorer.summarize(diagnostics.snapshot())
        .take(limit.coerceIn(1, 200))
        .map { trace ->
            SourceTraceUi(
                traceId = trace.traceId,
                sourceId = trace.sourceId,
                eventCount = trace.eventCount,
                startedAtEpochMs = trace.startedAtEpochMs,
                endedAtEpochMs = trace.completedAtEpochMs,
                failed = trace.errorCount > 0,
            )
        }

    fun inspectSelector(html: String, selector: String, baseUrl: String): Result<SourceSelectorInspectionUi> = runCatching {
        require(html.length <= 2 * 1024 * 1024) { "HTML kiểm tra vượt quá 2 MiB." }
        val inspection = SourceSelectorInspector.inspect(html, selector, baseUrl.ifBlank { "https://example.invalid/" })
        SourceSelectorInspectionUi(inspection.selector, inspection.matchCount, inspection.samples)
    }

    fun diagnosticSummaries(limit: Int = 20): List<SourceDiagnosticUi> = diagnostics.snapshot()
        .takeLast(limit.coerceIn(1, 200))
        .asReversed()
        .map { event ->
            SourceDiagnosticUi(
                timestampEpochMs = event.timestampEpochMs,
                traceId = event.traceId,
                sourceId = event.sourceId,
                category = event.category.name,
                name = event.name,
                severity = event.severity.name,
                durationMs = event.durationMs,
                detail = event.attributes.entries.joinToString(" • ") { (key, value) -> "$key=$value" },
            )
        }

    fun exportDiagnostics(): ByteArray = DiagnosticJsonExporter.export(diagnostics.snapshot())

    fun clearDiagnostics() {
        diagnostics.clear()
    }

    private fun preparePack(pack: VerifiedSourcePack): SourceInstallPreview {
        validateCompatibility(pack)
        val selfTest = selfTest(pack)
        val diff = store.permissionDiff(pack.manifest)
        pendingPack = pack
        return SourceInstallPreview(
            sourceId = pack.manifest.id,
            name = pack.manifest.name,
            version = pack.manifest.version.toString(),
            signerKeyId = pack.signerKeyId,
            permissionDiff = diff,
            permissionSummary = permissionSummary(diff),
            fixtureCount = selfTest.checkCount,
        )
    }

    private fun bootstrapBuiltinPack() {
        BUILTIN_SOURCEPACK_ASSETS.forEach { assetName ->
            runCatching {
                appContext.assets.open("sourcepacks/$assetName").use { input ->
                    val pack = when (val result = verifier.verify(input, trustRegistry.allKeys())) {
                        is SourcePlatformResult.Success -> result.value
                        is SourcePlatformResult.Failure -> error("${result.error.code}: ${result.error.message}")
                    }
                    validateCompatibility(pack)
                    selfTest(pack)
                    val existing = store.load(pack.manifest.id)
                    val shouldInstall = existing?.versions?.none { it.manifest.version == pack.manifest.version } != false
                    if (shouldInstall) {
                        when (val result = store.install(pack, activate = true)) {
                            is SourcePlatformResult.Success -> Unit
                            is SourcePlatformResult.Failure -> error("${result.error.code}: ${result.error.message}")
                        }
                    }
                }
            }.onFailure { error ->
                diagnostics.emit(
                    DiagnosticEvent(
                        timestampEpochMs = System.currentTimeMillis(),
                        traceId = "bootstrap-sourcepack:$assetName",
                        sourceId = "builtin:$assetName",
                        category = DiagnosticCategory.STORE,
                        name = "BUILTIN_SOURCEPACK_BOOTSTRAP_FAILED",
                        severity = DiagnosticSeverity.ERROR,
                        attributes = mapOf(
                            "asset" to assetName,
                            "error" to (error.message ?: error.javaClass.simpleName),
                        ),
                    ),
                )
            }
        }
    }

    private fun selfTest(pack: VerifiedSourcePack): SelfTestSummary {
        val resources = MapSourceResourceProvider(pack.entries)
        if (pack.manifest.runtime.mode in setOf(SourceRuntimeMode.VBOOK_JS_COMPAT, SourceRuntimeMode.NATIVE_LUA_COMPAT)) {
            val compatibility = vBookRuntime.validateScripts(pack.manifest, resources)
            require(compatibility.actions.isNotEmpty()) { "VBOOK_ACTIONS_REQUIRED" }
            require(compatibility.allCompatible) {
                compatibility.actions.filterNot { it.compatible }
                    .joinToString(prefix = "VBOOK_COMPATIBILITY_FAILED: ", separator = "; ") { "${it.action}=${it.detail}" }
            }
        }

        if (pack.manifest.fixtures.isEmpty()) {
            require(pack.manifest.runtime.mode != SourceRuntimeMode.DECLARATIVE) { "SOURCE_FIXTURES_REQUIRED" }
            return SelfTestSummary(pack.manifest.actions.size)
        }

        val report = fixtureRunner.run(pack.manifest, resources)
        require(report.allPassed) {
            report.results.filterNot { it.passed }
                .joinToString(prefix = "SOURCE_FIXTURE_FAILED: ", separator = "; ") { "${it.name}=${it.detail}" }
        }
        return SelfTestSummary(report.results.size)
    }

    private fun validateCompatibility(pack: VerifiedSourcePack) {
        pack.manifest.minAppVersion?.let { require(CURRENT_APP_VERSION >= it) { "Gói nguồn yêu cầu ứng dụng từ $it." } }
        pack.manifest.maxAppVersion?.let { require(CURRENT_APP_VERSION <= it) { "Gói nguồn chỉ hỗ trợ ứng dụng đến $it." } }
    }

    private fun loadCachedRepositories() {
        repositoryRoot.listFiles().orEmpty().filter(File::isDirectory).forEach { directory ->
            runCatching {
                val url = File(directory, REPOSITORY_URL_FILE).readText(Charsets.UTF_8).trim()
                val raw = File(directory, REPOSITORY_INDEX_FILE).readBytes()
                val verified = when (val result = repositoryVerifier.verify(raw, trustRegistry.allKeys())) {
                    is SourcePlatformResult.Success -> result.value
                    is SourcePlatformResult.Failure -> error(result.error.message)
                }
                require(directory.name == verified.index.repositoryId) { "SOURCE_REPOSITORY_CACHE_ID_MISMATCH" }
                repositories[verified.index.repositoryId] = CachedRepository(url, verified)
            }.onFailure { error ->
                directory.deleteRecursively()
                diagnostics.emit(DiagnosticEvent(
                    timestampEpochMs = System.currentTimeMillis(), traceId = UUID.randomUUID().toString(),
                    sourceId = "repository:${directory.name}", category = DiagnosticCategory.STORE,
                    name = "REPOSITORY_CACHE_REJECTED", severity = DiagnosticSeverity.WARN,
                    attributes = mapOf("error" to (error.message ?: error.javaClass.simpleName)),
                ))
            }
        }
    }

    private fun rememberRepository(url: String, raw: ByteArray, verified: VerifiedSourceRepository) {
        val directory = repositoryDirectory(verified.index.repositoryId).also(File::mkdirs)
        atomicWrite(File(directory, REPOSITORY_INDEX_FILE), raw)
        atomicWrite(File(directory, REPOSITORY_URL_FILE), url.toByteArray(StandardCharsets.UTF_8))
        repositories[verified.index.repositoryId] = CachedRepository(url, verified)
    }

    private fun repositoryDirectory(repositoryId: String): File {
        require(REPOSITORY_ID.matches(repositoryId)) { "SOURCE_REPOSITORY_ID_INVALID" }
        val target = File(repositoryRoot, repositoryId).canonicalFile
        require(target.path.startsWith(repositoryRoot.canonicalPath + File.separator)) { "SOURCE_REPOSITORY_PATH_ESCAPE" }
        return target
    }

    private fun normalizeRepositoryUrl(raw: String): String {
        val value = raw.trim()
        require(value.length in 1..4096) { "Repository phải là URL HTTPS hợp lệ." }
        val uri = runCatching { URI(value) }.getOrNull() ?: error("Repository phải là URL HTTPS hợp lệ.")
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
            "Repository phải là URL HTTPS hợp lệ."
        }
        return uri.normalize().toASCIIString()
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        temp.writeBytes(bytes)
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun permissionSummary(diff: SourcePermissionDiff): List<String> = buildList {
        if (diff.addedOrigins.isNotEmpty()) add("Mạng: ${diff.addedOrigins.joinToString()}")
        if (diff.addedRedirectOrigins.isNotEmpty()) add("Chuyển hướng: ${diff.addedRedirectOrigins.joinToString()}")
        if (diff.addedNetworkMethods.isNotEmpty()) add("Phương thức HTTP: ${diff.addedNetworkMethods.joinToString()}")
        if (diff.cookieEscalated) add("Quyền cookie tăng")
        if (diff.browserEscalations.isNotEmpty()) add("Trình duyệt: ${diff.browserEscalations.joinToString()}")
        if (diff.storageIncreaseBytes > 0) add("Lưu trữ riêng: ${diff.storageIncreaseBytes} byte")
        if (diff.addedCrypto.isNotEmpty()) add("Mật mã: ${diff.addedCrypto.joinToString()}")
        if (diff.websocketEnabled) add("WebSocket")
        if (isEmpty()) add("Không yêu cầu thêm quyền")
    }

    private data class CachedRepository(
        val url: String,
        val repository: VerifiedSourceRepository,
    )

    private data class SelfTestSummary(val checkCount: Int)

    companion object {
        val CURRENT_APP_VERSION = SemanticVersion(2, 7, 0)
        private const val REPOSITORY_INDEX_FILE = "index.json"
        private const val REPOSITORY_URL_FILE = "url.txt"
        private val REPOSITORY_ID = Regex("^[a-z][a-z0-9]*(\\.[a-z0-9][a-z0-9-]*){2,}$")
        private val BUILTIN_SOURCEPACK_ASSETS = listOf(
            "demo.ntsource",
            "truyenfull.ntsource",
            "truyencv.ntsource",
            "truyencom.ntsource",
            "truyenyy.ntsource",
            "wikidich.ntsource",
            "sangtacviet.ntsource",
            "wattpad.ntsource",
        )
    }
}
