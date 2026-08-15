package vn.nghetruyen.app.sourceplatform

import android.content.Context
import com.nghetruyen.source.platform.SourceArtifactIdentity
import com.nghetruyen.source.platform.SourceEcosystem
import vn.nghetruyen.app.BuildConfig
import vn.nghetruyen.app.sources.SourceSessionStore
import vn.nghetruyen.app.ai.TranslationEngine
import vn.nghetruyen.app.sources.StorySource
import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourcePermissionDiff
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceCapabilityBrokers
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.network.OkHttpSourceWebSocketBroker
import vn.nghetruyen.source.network.PartitionedSourceCookieJar
import vn.nghetruyen.source.runtime.FileSourceStorageBroker
import vn.nghetruyen.source.runtime.JcaSourceCryptoBroker
import vn.nghetruyen.source.lua.LuaNativeHookBroker
import vn.nghetruyen.source.lua.NativeLuaArchiveImporter
import vn.nghetruyen.source.lua.NativeLuaRuntimeOverlay
import vn.nghetruyen.source.vbook.VBookHostManifestFactory
import vn.nghetruyen.source.vbook.VBookJsRuntime
import vn.nghetruyen.source.vbook.VBookManifestParser
import vn.nghetruyen.source.vbook.VBookPackageLimits
import vn.nghetruyen.source.vbook.VBookPackageReader
import vn.nghetruyen.source.vbook.VBookPackageResourceProvider
import vn.nghetruyen.source.diagnostics.BoundedDiagnosticRecorder
import vn.nghetruyen.source.diagnostics.DiagnosticArtifactInspector
import vn.nghetruyen.source.diagnostics.DiagnosticArtifactMetadata
import vn.nghetruyen.source.diagnostics.DiagnosticCategory
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticEvidence
import vn.nghetruyen.source.diagnostics.DiagnosticEvidenceSink
import vn.nghetruyen.source.diagnostics.DiagnosticJsonExporter
import vn.nghetruyen.source.diagnostics.DiagnosticLevel
import vn.nghetruyen.source.diagnostics.DiagnosticOperationContract
import vn.nghetruyen.source.diagnostics.DiagnosticOperationState
import vn.nghetruyen.source.diagnostics.DiagnosticSeverity
import vn.nghetruyen.source.diagnostics.DiagnosticThrowableFormatter
import vn.nghetruyen.source.diagnostics.SourceTraceExplorer
import vn.nghetruyen.source.network.OkHttpSourceNetworkBroker
import vn.nghetruyen.source.packagekit.SourcePackArchiveVerifier
import vn.nghetruyen.source.packagekit.VerifiedSourcePack
import vn.nghetruyen.source.repository.SourceRepositoryCatalog
import vn.nghetruyen.source.repository.SourceRepositoryPackageStatus
import vn.nghetruyen.source.repository.SourceRepositoryVerifier
import com.nghetruyen.source.repository.VBookUpdateDisposition
import vn.nghetruyen.source.repository.VerifiedSourceRepository
import vn.nghetruyen.source.runtime.DeclarativeSourceRuntime
import vn.nghetruyen.source.runtime.MapSourceResourceProvider
import vn.nghetruyen.source.runtime.SourceFixtureExecutor
import vn.nghetruyen.source.runtime.SourceFixtureRunner
import vn.nghetruyen.source.store.SourcePackStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SourcePlatformManager(
    context: Context,
    sourceSessionStore: SourceSessionStore,
    translationEngine: TranslationEngine,
    private val vBookSourcePlatform: VBookSourcePlatform? = null,
    private val onVBookChanged: () -> Unit = {},
    private val diagnostics: BoundedDiagnosticRecorder = BoundedDiagnosticRecorder(maxEvents = 8_000, level = DiagnosticLevel.BASIC),
    private val evidence: DiagnosticEvidenceSink = DiagnosticEvidenceSink.NONE,
) {
    private val appContext = context.applicationContext
    private val platformRoot = appContext.filesDir.resolve("source-platform-v2")
    private val repositoryRoot = platformRoot.resolve("repositories").also(File::mkdirs)
    private val trustRegistry = SourceTrustRegistry(appContext)
    private val verifier = SourcePackArchiveVerifier(diagnostics = diagnostics)
    private val store = SourcePackStore(platformRoot, diagnostics)
    private val cookieJar = PartitionedSourceCookieJar(EncryptedSourceCookiePersistence(appContext))
    private val networkBroker = OkHttpSourceNetworkBroker(cookiePartition = cookieJar, diagnostics = diagnostics)
    private val browserBroker = AndroidSourceBrowserBroker(appContext, cookieJar, diagnostics, evidence = evidence)
    private val storageBroker = FileSourceStorageBroker(platformRoot.resolve("storage"))
    private val cryptoBroker = JcaSourceCryptoBroker(AndroidSourceSecretKeyProvider())
    private val webSocketBroker = OkHttpSourceWebSocketBroker(cookieJar, diagnostics)
    private val nativeHookBroker = LuaNativeHookBroker()
    private val nativeRuntimeOverlayCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, ByteArray>>()
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
    private val vBookRuntime = VBookJsRuntime(brokers, diagnostics, evidence = evidence)
    private val executor = SourcePackActionExecutor { pack, resources, request ->
        val startedAt = System.currentTimeMillis()
        val timeoutMs = pack.manifest.actions[request.action]?.timeoutMs ?: pack.manifest.runtime.actionTimeoutMs
        val operationId = "source-action:${request.traceId.ifBlank { "no-trace" }}:${request.action.name}:${UUID.randomUUID()}"
        diagnostics.emit(
            DiagnosticEvent(
                timestampEpochMs = startedAt,
                traceId = request.traceId,
                sourceId = pack.manifest.id,
                sourceVersion = pack.manifest.version.toString(),
                category = DiagnosticCategory.RUNTIME,
                name = "SOURCE_ACTION_STARTED",
                attributes = DiagnosticOperationContract.attributes(
                    id = operationId,
                    kind = request.action.name,
                    flow = "runtime",
                    state = DiagnosticOperationState.STARTED,
                    stage = "SOURCE_ACTION_STARTED",
                    timeoutMs = timeoutMs,
                    deadlineEpochMs = startedAt + timeoutMs,
                ) + mapOf(
                    "action" to request.action.name,
                    "runtimeMode" to pack.manifest.runtime.mode.name,
                ),
            ),
        )
        val result = when (pack.manifest.runtime.mode) {
            SourceRuntimeMode.DECLARATIVE -> declarativeRuntime.execute(pack.manifest, resources, request)
            SourceRuntimeMode.VBOOK_JS_COMPAT,
            SourceRuntimeMode.NATIVE_LUA_COMPAT -> vBookRuntime.execute(pack.manifest, resources, request)
        }
        val failure = result as? SourcePlatformResult.Failure
        diagnostics.emit(
            DiagnosticEvent(
                timestampEpochMs = System.currentTimeMillis(),
                traceId = request.traceId,
                sourceId = pack.manifest.id,
                sourceVersion = pack.manifest.version.toString(),
                category = DiagnosticCategory.RUNTIME,
                name = if (failure == null) "SOURCE_ACTION_COMPLETED" else "SOURCE_ACTION_FAILED",
                severity = if (failure == null) DiagnosticSeverity.INFO else DiagnosticSeverity.ERROR,
                durationMs = System.currentTimeMillis() - startedAt,
                attributes = DiagnosticOperationContract.attributes(
                    id = operationId,
                    kind = request.action.name,
                    flow = "runtime",
                    state = if (failure == null) DiagnosticOperationState.COMPLETED else DiagnosticOperationState.FAILED,
                    stage = if (failure == null) "SOURCE_ACTION_COMPLETED" else "SOURCE_ACTION_FAILED",
                ) + buildMap {
                    put("action", request.action.name)
                    put("runtimeMode", pack.manifest.runtime.mode.name)
                    failure?.let {
                        put("errorCode", it.error.code.name)
                        put("error", it.error.message.take(1_000))
                        it.error.cause?.let { cause -> putAll(DiagnosticThrowableFormatter.attributes(cause)) }
                    }
                },
            ),
        )
        result
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
    private val builtinSourceIds = linkedSetOf<String>()
    private val builtinRemovalPreferences = appContext.getSharedPreferences(
        BUILTIN_REMOVAL_PREFERENCES,
        Context.MODE_PRIVATE,
    )
    private var pendingPack: VerifiedSourcePack? = null
    private var pendingVBook: PendingVBookImport? = null
    private var pendingWarnings: List<String> = emptyList()
    private var pendingImportContext: PendingImportContext? = null

    init {
        loadCachedRepositories()
        bootstrapBuiltinPack()
    }

    /** Legacy/native SourcePack sources only. vBook active artifacts live in VBookSourcePlatform. */
    fun activeStorySources(): List<StorySource> = store.list()
        .filter { it.enabled && it.active != null }
        .mapNotNull { installed -> store.readActivePack(installed.sourceId) }
        .map(::runtimePack)
        .map { pack -> SourcePackStorySource(pack, executor, genericCommentLoader) }

    private fun runtimePack(pack: VerifiedSourcePack): VerifiedSourcePack {
        if (pack.manifest.runtime.mode != SourceRuntimeMode.NATIVE_LUA_COMPAT) return pack
        val runtimeEntries = nativeRuntimeOverlayCache[pack.packageSha256] ?: try {
            NativeLuaRuntimeOverlay.refresh(pack).entries.also { entries ->
                nativeRuntimeOverlayCache[pack.packageSha256] = entries
            }
        } catch (error: Exception) {
            diagnostics.emit(
                DiagnosticEvent(
                    timestampEpochMs = System.currentTimeMillis(),
                    traceId = "native-runtime-overlay:${UUID.randomUUID()}",
                    sourceId = pack.manifest.id,
                    sourceVersion = pack.manifest.version.toString(),
                    category = DiagnosticCategory.RUNTIME,
                    name = "NATIVE_LUA_RUNTIME_OVERLAY_FAILED",
                    severity = DiagnosticSeverity.WARN,
                    attributes = mapOf(
                        "errorType" to error.javaClass.name.take(240),
                        "error" to error.message.orEmpty().take(1_000),
                    ),
                ),
            )
            return pack
        }
        return if (runtimeEntries === pack.entries) pack else pack.copy(entries = runtimeEntries)
    }

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
            removable = true,
            ecosystem = if (active?.manifest?.runtime?.mode == SourceRuntimeMode.VBOOK_JS_COMPAT) "VBOOK" else "NATIVE",
            contentType = active?.manifest?.contentType?.name.orEmpty(),
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

    fun refreshRepository(url: String): Result<SourceRepositoryUiInfo> = refreshRepositoryOperation(
        url = url,
        failureSeverity = DiagnosticSeverity.ERROR,
    )

    /** A schema mismatch is expected while the unified facade tries the vBook repository schema. */
    internal fun probeRepository(url: String): Result<SourceRepositoryUiInfo> = refreshRepositoryOperation(
        url = url,
        failureSeverity = DiagnosticSeverity.INFO,
    )

    private fun refreshRepositoryOperation(
        url: String,
        failureSeverity: DiagnosticSeverity,
    ): Result<SourceRepositoryUiInfo> = runExtensionOperation(
        stage = "repository_refresh",
        sourceId = null,
        operationKind = "EXTENSION_REPOSITORY",
        extraAttributes = mapOf("repositoryHost" to runCatching { URI(url.trim()).host.orEmpty() }.getOrDefault("invalid")),
        failureSeverity = failureSeverity,
        block = {
            val normalized = normalizeRepositoryUrl(url)
            val raw = repositoryHttpClient.fetchIndex(normalized, SourceRepositoryVerifier.MAX_INDEX_BYTES)
            val verified = when (val result = repositoryVerifier.verify(raw, trustRegistry.allKeys())) {
                is SourcePlatformResult.Success -> result.value
                is SourcePlatformResult.Failure -> error("${result.error.code}: ${result.error.message}")
            }
            rememberRepository(normalized, raw, verified)
            repositories().first { it.id == verified.index.repositoryId }
        },
    )

    fun removeRepository(repositoryId: String): Result<Unit> = runCatching {
        require(REPOSITORY_ID.matches(repositoryId)) { "Repository ID không hợp lệ." }
        repositories.remove(repositoryId) ?: error("Repository chưa được thêm.")
        repositoryDirectory(repositoryId).deleteRecursively()
    }

    fun prepareRepositoryInstall(repositoryId: String, sourceId: String): Result<SourceInstallPreview> = runExtensionOperation(
        stage = "repository_prepare_install",
        sourceId = sourceId,
        operationKind = "EXTENSION_REPOSITORY_INSTALL",
        extraAttributes = mapOf("repositoryId" to repositoryId.take(300)),
        block = {
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
        },
    )

    /**
     * One authoritative manual-file import transaction. All format probes share one trace and one
     * safe file fingerprint, so a report can reconstruct the exact attempt without retaining a raw
     * content URI or file contents.
     */
    fun prepareManualImport(
        input: InputStream,
        metadata: SourceImportFileMetadata = SourceImportFileMetadata(),
    ): Result<SourceInstallPreview> {
        cancelPendingInstall()
        val traceId = "extension-import:${UUID.randomUUID()}"
        val operationId = "$traceId:manual-file"
        val startedAt = System.currentTimeMillis()
        val declared = DiagnosticArtifactMetadata(metadata.displayName, metadata.mimeType, metadata.declaredSizeBytes)
        val declaredAttributes = mapOf(
            "fileName" to DiagnosticArtifactInspector.safeDisplayName(metadata.displayName),
            "mimeType" to metadata.mimeType.trim().replace(Regex("[\\p{Cntrl}\\s]+"), " ").take(160),
            "declaredBytes" to (metadata.declaredSizeBytes?.takeIf { it >= 0L }?.toString() ?: "unknown"),
            "importAttemptId" to traceId,
        ) + diagnosticBuildAttributes()
        emitImportParent(
            traceId = traceId,
            operationId = operationId,
            state = DiagnosticOperationState.STARTED,
            stage = "read_selected_file",
            name = "SOURCE_EXTENSION_IMPORT_STARTED",
            startedAt = startedAt,
            attributes = declaredAttributes,
        )

        val bytes = runCatching {
            readBounded(input, MAX_MANUAL_IMPORT_BYTES, "SOURCE_IMPORT_TOO_LARGE", "SOURCE_IMPORT_EMPTY")
        }.getOrElse { error ->
            recordExtensionFailure(
                stage = "read_selected_file",
                sourceId = "manual-import",
                error = error,
                traceId = traceId,
                operationId = operationId,
                startedAt = startedAt,
                operationKind = "EXTENSION_IMPORT",
                extraAttributes = declaredAttributes,
            )
            return Result.failure(error)
        }
        val artifactAttributes = DiagnosticArtifactInspector.inspect(bytes, declared) +
            mapOf("importAttemptId" to traceId) + diagnosticBuildAttributes()
        val failures = linkedMapOf<String, Throwable>()

        fun probe(
            stage: String,
            sourceId: String?,
            block: () -> SourceInstallPreview,
        ): Result<SourceInstallPreview> = runExtensionOperation(
            stage = stage,
            sourceId = sourceId,
            block = block,
            traceId = traceId,
            operationId = "$traceId:$stage",
            operationKind = "EXTENSION_FORMAT_PROBE",
            extraAttributes = artifactAttributes,
            failureSeverity = DiagnosticSeverity.INFO,
        ).also { result -> result.exceptionOrNull()?.let { failures[stage] = it } }

        val probePlan: List<Pair<String, () -> Result<SourceInstallPreview>>> = listOf(
            "sourcepack_prepare_install" to {
                probe("sourcepack_prepare_install", null) { prepareSourcePackInternal(ByteArrayInputStream(bytes)) }
            },
            "vbook_prepare_import" to {
                probe("vbook_prepare_import", "vbook-import") {
                    prepareVBookImportInternal(ByteArrayInputStream(bytes), traceId)
                }
            },
            "native_lua_prepare_import" to {
                probe("native_lua_prepare_import", "native-lua-import") { prepareNativeLuaImportInternal(ByteArrayInputStream(bytes)) }
            },
        )
        val probes = mutableListOf<Pair<String, Result<SourceInstallPreview>>>()
        var recognized: Pair<String, Result<SourceInstallPreview>>? = null
        for ((name, attempt) in probePlan) {
            val result = attempt()
            probes += name to result
            if (result.isSuccess) {
                recognized = name to result
                break
            }
        }
        val recognizedProbe = recognized
        if (recognizedProbe != null) {
            val preview = recognizedProbe.second.getOrThrow()
            val context = PendingImportContext(traceId, operationId, startedAt, artifactAttributes)
            pendingImportContext = context
            emitImportParent(
                traceId = traceId,
                operationId = operationId,
                state = DiagnosticOperationState.STAGE,
                stage = "format_recognized",
                name = "SOURCE_EXTENSION_IMPORT_FORMAT_RECOGNIZED",
                startedAt = startedAt,
                sourceId = preview.sourceId,
                attributes = artifactAttributes + mapOf(
                    "recognizedFormat" to recognizedProbe.first,
                    "sourceName" to preview.name.take(300),
                    "sourceVersion" to preview.version.take(100),
                    "probeFailureCount" to failures.size.toString(),
                ),
            )
            return Result.success(preview)
        }

        val combined = IllegalArgumentException(
            "Không nhận diện được .ntsource, vBook ZIP hoặc Lua Native Source API 2. " +
                "SourcePack: ${failures["sourcepack_prepare_install"]?.message.orEmpty()}; " +
                "vBook: ${failures["vbook_prepare_import"]?.message.orEmpty()}; " +
                "Lua: ${failures["native_lua_prepare_import"]?.message.orEmpty()}",
            failures.values.lastOrNull(),
        )
        recordExtensionFailure(
            stage = "detect_extension_format",
            sourceId = "manual-import",
            error = combined,
            traceId = traceId,
            operationId = operationId,
            startedAt = startedAt,
            operationKind = "EXTENSION_IMPORT",
            extraAttributes = artifactAttributes + mapOf(
                "probeOrder" to probes.joinToString(",") { it.first },
                "probeFailureCount" to failures.size.toString(),
                "sourcePackError" to failures["sourcepack_prepare_install"]?.message.orEmpty().take(2_000),
                "vBookError" to failures["vbook_prepare_import"]?.message.orEmpty().take(2_000),
                "nativeLuaError" to failures["native_lua_prepare_import"]?.message.orEmpty().take(2_000),
            ),
        )
        return Result.failure(combined)
    }

    fun prepareInstall(input: InputStream): Result<SourceInstallPreview> = runExtensionOperation("sourcepack_prepare_install", null) {
        prepareSourcePackInternal(input)
    }

    private fun prepareSourcePackInternal(input: InputStream): SourceInstallPreview {
        val pack = when (val result = verifier.verify(input, trustRegistry.allKeys())) {
            is SourcePlatformResult.Success -> result.value
            is SourcePlatformResult.Failure -> error("${result.error.code}: ${result.error.message}")
        }
        return preparePack(pack)
    }

    /**
     * Preview a raw vBook ZIP without converting it to SourcePack. The existing UI preview model is
     * reused only as presentation; the pending install retains the exact ZIP bytes and stable vBook
     * identity, and confirmation is committed by VBookSourcePlatform.
     */
    fun prepareVBookImport(input: InputStream): Result<SourceInstallPreview> = runExtensionOperation("vbook_prepare_import", "vbook-import") {
        prepareVBookImportInternal(input)
    }

    private fun prepareVBookImportInternal(
        input: InputStream,
        importTrace: String = "vbook-import:${UUID.randomUUID()}",
    ): SourceInstallPreview {
        val platform = vBookSourcePlatform ?: error("VBOOK_SUBSYSTEM_UNAVAILABLE")
        val bytes = readBounded(input, VBookPackageLimits().maxZipBytes)
        evidence.capture(DiagnosticEvidence(
            timestampEpochMs = System.currentTimeMillis(),
            traceId = importTrace,
            sourceId = "vbook-import",
            category = DiagnosticCategory.PACKAGE,
            name = "manual-vbook-package.zip",
            contentType = "application/zip",
            data = bytes,
        ))
        val pkg = VBookPackageReader.read(bytes)
        val plugin = VBookManifestParser.parse(pkg.pluginJson())
        val identity = manualVBookIdentity(plugin.metadata.author, plugin.metadata.name, plugin.metadata.type.name, plugin.metadata.locale)
        val version = plugin.metadata.version.toString()
        val preview = platform.preview(
            repositoryId = identity.repositoryId,
            remoteIdentity = identity.remoteIdentity,
            version = version,
            packageBytes = bytes,
        )
        require(preview.activatable) {
            val blockers = preview.blockingFeatures.sortedBy(Enum<*>::name).joinToString { it.name }
            val failures = preview.validation.failures.joinToString("; ") { it.message }
            "VBOOK_IMPORT_NOT_ACTIVATABLE:${listOf(blockers, failures).filter(String::isNotBlank).joinToString(" | ")}"
        }
        val resources = VBookPackageResourceProvider(pkg)
        val hostManifest = VBookHostManifestFactory.create(identity.canonicalKey(), plugin, resources)
        val diff = store.permissionDiff(hostManifest)
        pendingPack = null
        pendingVBook = PendingVBookImport(identity, version, bytes.copyOf(), hostManifest.id, preview.name)
        pendingWarnings = preview.warnings + listOf(
            "VBOOK_RAW_PACKAGE_PRESERVED",
            "VBOOK_RUNTIME:${preview.validation.profile?.id ?: "unknown"}",
        )
        return SourceInstallPreview(
            sourceId = hostManifest.id,
            name = preview.name,
            version = version,
            signerKeyId = "vbook-unmodified",
            permissionDiff = diff,
            permissionSummary = permissionSummary(diff),
            fixtureCount = preview.validation.audit?.features?.size ?: 0,
        )
    }

    fun prepareNativeLuaImport(input: InputStream): Result<SourceInstallPreview> = runExtensionOperation("native_lua_prepare_import", "native-lua-import") {
        prepareNativeLuaImportInternal(input)
    }

    private fun prepareNativeLuaImportInternal(input: InputStream): SourceInstallPreview {
        val (pack, warnings) = NativeLuaArchiveImporter.import(input)
        pendingWarnings = warnings
        return preparePack(pack)
    }

    fun pendingInstallWarnings(): List<String> = pendingWarnings

    internal fun <T> runExternalExtensionOperation(
        stage: String,
        sourceId: String?,
        operationKind: String,
        extraAttributes: Map<String, String> = emptyMap(),
        failureSeverity: DiagnosticSeverity = DiagnosticSeverity.ERROR,
        block: () -> T,
    ): Result<T> = runExtensionOperation(
        stage = stage,
        sourceId = sourceId,
        block = block,
        operationKind = operationKind,
        extraAttributes = extraAttributes,
        failureSeverity = failureSeverity,
    )

    fun trustKeys(): List<SourceTrustKeyUi> = trustRegistry.userKeys()

    fun enrollTrustKey(keyId: String, algorithm: String, publicKeyBase64: String, fingerprint: String): Result<SourceTrustKeyUi> =
        trustRegistry.enroll(keyId, algorithm, publicKeyBase64, fingerprint)

    fun revokeTrustKey(keyId: String): Result<Unit> = trustRegistry.revoke(keyId)

    fun applyTrustKeyRotation(raw: ByteArray): Result<SourceTrustKeyUi> = trustRegistry.applyRotation(raw)

    fun confirmPendingInstall(): Result<SourcePackUiInfo> {
        val context = pendingImportContext
        val sourceId = pendingVBook?.sourceId ?: pendingPack?.manifest?.id
        val traceId = context?.traceId ?: "extension-install:${UUID.randomUUID()}"
        val result = runExtensionOperation(
            stage = "confirm_install",
            sourceId = sourceId,
            block = ::commitPendingInstall,
            traceId = traceId,
            operationId = "$traceId:confirm_install",
            operationKind = "EXTENSION_INSTALL_COMMIT",
            extraAttributes = context?.attributes.orEmpty(),
            // A correlated manual import emits one durable parent failure below. Keep this child
            // stage in live/continuous logs without counting the same failed attempt twice.
            failureSeverity = if (context != null) DiagnosticSeverity.INFO else DiagnosticSeverity.ERROR,
        )
        if (context != null) {
            result.onSuccess { installed ->
                emitImportParent(
                    traceId = context.traceId,
                    operationId = context.operationId,
                    state = DiagnosticOperationState.COMPLETED,
                    stage = "installed_and_activated",
                    name = "SOURCE_EXTENSION_IMPORT_COMPLETED",
                    startedAt = context.startedAt,
                    sourceId = installed.id,
                    attributes = context.attributes + mapOf(
                        "installedSourceId" to installed.id,
                        "installedName" to installed.name.take(300),
                        "installedVersion" to installed.version.take(100),
                        "installDisposition" to "ACTIVATED",
                    ),
                )
            }.onFailure { error ->
                val codes = extensionErrorCodes(error)
                emitImportParent(
                    traceId = context.traceId,
                    operationId = context.operationId,
                    state = DiagnosticOperationState.FAILED,
                    stage = "confirm_install",
                    name = "SOURCE_EXTENSION_IMPORT_FAILED",
                    startedAt = context.startedAt,
                    sourceId = sourceId ?: "manual-import",
                    attributes = context.attributes + DiagnosticThrowableFormatter.attributes(error) + mapOf(
                        "code" to codes.lastOrNull().orEmpty().ifBlank { "UNKNOWN_INSTALL_ERROR" },
                        "outerCode" to codes.firstOrNull().orEmpty().ifBlank { "UNKNOWN_INSTALL_ERROR" },
                        "rootCauseCode" to codes.lastOrNull().orEmpty().ifBlank { "UNKNOWN_INSTALL_ERROR" },
                        "errorCodes" to codes.joinToString(","),
                        "message" to (error.message ?: error.javaClass.simpleName).take(4_000),
                        "pendingWarnings" to pendingWarnings.take(20).joinToString(" | ").take(2_000),
                    ),
                )
            }
            pendingImportContext = null
        }
        return result
    }

    private fun commitPendingInstall(): SourcePackUiInfo {
        val pendingVBookValue = pendingVBook
        if (pendingVBookValue != null) {
            val platform = vBookSourcePlatform ?: error("VBOOK_SUBSYSTEM_UNAVAILABLE")
            val result = platform.installOrUpdate(
                repositoryId = pendingVBookValue.identity.repositoryId,
                remoteIdentity = pendingVBookValue.identity.remoteIdentity,
                version = pendingVBookValue.version,
                packageBytes = pendingVBookValue.bytes,
            )
            require(result.disposition == VBookUpdateDisposition.ACTIVATED && result.active != null) {
                val blockers = result.validation.blockingFeatures.sortedBy(Enum<*>::name).joinToString { it.name }
                "VBOOK_INSTALL_QUARANTINED:${blockers.ifBlank { result.validation.failures.joinToString("; ") { it.message } }}"
            }
            pendingVBook = null
            pendingWarnings = emptyList()
            onVBookChanged()
            return SourcePackUiInfo(
                id = pendingVBookValue.sourceId,
                name = pendingVBookValue.name,
                version = pendingVBookValue.version,
                enabled = true,
                installedVersions = listOf(pendingVBookValue.version),
                canRollback = false,
                signerKeyId = "vbook-unmodified",
                runtimeMode = SourceRuntimeMode.VBOOK_JS_COMPAT.name,
                commentCapability = "VBOOK_DYNAMIC",
                commentFixtureCount = 0,
                removable = true,
                ecosystem = "VBOOK",
            )
        }

        val pack = pendingPack ?: error("Không có gói nguồn đang chờ xác nhận.")
        val installed = when (val result = store.install(pack, activate = true)) {
            is SourcePlatformResult.Success -> result.value
            is SourcePlatformResult.Failure -> error("${result.error.code}: ${result.error.message}")
        }
        pendingPack = null
        pendingWarnings = emptyList()
        return installedPacks().first { it.id == installed.sourceId }
    }

    fun cancelPendingInstall() {
        pendingImportContext?.let { context ->
            emitImportParent(
                traceId = context.traceId,
                operationId = context.operationId,
                state = DiagnosticOperationState.CANCELLED,
                stage = "cancelled_by_user",
                name = "SOURCE_EXTENSION_IMPORT_CANCELLED",
                startedAt = context.startedAt,
                attributes = context.attributes,
            )
        }
        pendingImportContext = null
        pendingPack = null
        pendingVBook = null
        pendingWarnings = emptyList()
    }

    fun setEnabled(sourceId: String, enabled: Boolean): Result<Unit> = runExtensionOperation(
        stage = if (enabled) "enable_source" else "disable_source",
        sourceId = sourceId,
        operationKind = "EXTENSION_ACTIVATION",
        extraAttributes = mapOf("enabled" to enabled.toString()),
        block = {
            when (val result = store.setEnabled(sourceId, enabled)) {
                is SourcePlatformResult.Success -> Unit
                is SourcePlatformResult.Failure -> error(result.error.message)
            }
        },
    )

    fun rollback(sourceId: String): Result<Unit> = runExtensionOperation(
        stage = "rollback_source",
        sourceId = sourceId,
        operationKind = "EXTENSION_ROLLBACK",
        block = {
            when (val result = store.rollback(sourceId)) {
                is SourcePlatformResult.Success -> Unit
                is SourcePlatformResult.Failure -> error(result.error.message)
            }
        },
    )

    fun removeInstalledPack(sourceId: String): Result<Unit> = runExtensionOperation(
        stage = "remove_source",
        sourceId = sourceId,
        operationKind = "EXTENSION_REMOVE",
        block = {
            require(store.remove(sourceId)) { "Không tìm thấy tiện ích để xóa." }
            if (sourceId in builtinSourceIds) rememberBuiltinRemoved(sourceId)
        },
    )

    fun exportInstalledPack(sourceId: String, output: OutputStream): Result<Unit> = runCatching {
        val pack = store.readActivePack(sourceId) ?: error("Không tìm thấy gói tiện ích đang hoạt động.")
        ZipOutputStream(output.buffered()).use { zip ->
            pack.entries.toSortedMap().forEach { (entryPath, bytes) ->
                SourceManifest.requireSafeRelativePath(entryPath)
                zip.putNextEntry(ZipEntry(entryPath).apply { time = 0L })
                zip.write(bytes)
                zip.closeEntry()
            }
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
        .takeLast(limit.coerceIn(1, 2_000))
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

    private fun removedBuiltinSourceIds(): Set<String> =
        builtinRemovalPreferences.getStringSet(REMOVED_BUILTIN_SOURCE_IDS, emptySet<String>())
            ?.toSet()
            .orEmpty()

    private fun isBuiltinRemoved(sourceId: String): Boolean = sourceId in removedBuiltinSourceIds()

    private fun rememberBuiltinRemoved(sourceId: String) {
        val removed = removedBuiltinSourceIds().toMutableSet()
        if (removed.add(sourceId)) {
            builtinRemovalPreferences.edit().putStringSet(REMOVED_BUILTIN_SOURCE_IDS, removed).apply()
        }
    }

    private fun preparePack(pack: VerifiedSourcePack): SourceInstallPreview {
        validateCompatibility(pack)
        val selfTest = selfTest(pack)
        val diff = store.permissionDiff(pack.manifest)
        pendingVBook = null
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
        bootstrapSignedBuiltinPack(DEMO_BUILTIN_SOURCEPACK_ASSET)
        BUILTIN_LUA_SOURCES.forEach(::bootstrapLuaBuiltin)
    }

    private fun bootstrapSignedBuiltinPack(assetName: String) {
        runCatching {
            appContext.assets.open("sourcepacks/$assetName").use { input ->
                val pack = when (val result = verifier.verify(input, trustRegistry.allKeys())) {
                    is SourcePlatformResult.Success -> result.value
                    is SourcePlatformResult.Failure -> error("${result.error.code}: ${result.error.message}")
                }
                builtinSourceIds += pack.manifest.id
                if (isBuiltinRemoved(pack.manifest.id)) return@use
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

    private fun bootstrapLuaBuiltin(spec: BuiltinLuaSourceSpec) {
        runCatching {
            val legacyPack = runCatching { readLegacyBuiltinPack(spec.legacySourcePackAsset) }
                .onFailure { error ->
                    diagnostics.emit(
                        DiagnosticEvent(
                            timestampEpochMs = System.currentTimeMillis(),
                            traceId = "bootstrap-legacy-metadata:${spec.legacySourcePackAsset}",
                            sourceId = "builtin:${spec.legacySourcePackAsset}",
                            category = DiagnosticCategory.STORE,
                            name = "BUILTIN_LEGACY_METADATA_REJECTED",
                            severity = DiagnosticSeverity.WARN,
                            attributes = mapOf("error" to (error.message ?: error.javaClass.simpleName).take(1_000)),
                        ),
                    )
                }
                .getOrNull()
            legacyPack?.let { builtinSourceIds += it.manifest.id }
            val legacyWasRemoved = legacyPack?.manifest?.id?.let(::isBuiltinRemoved) == true
            val legacyInstalled = legacyPack?.let { store.load(it.manifest.id) }
            val legacyIsExactBundledCopy = legacyPack != null &&
                legacyInstalled?.active?.packageSha256 == legacyPack.packageSha256

            val exactBytes = appContext.assets.open("source-lua/${spec.gzipAsset}").use { compressed ->
                GZIPInputStream(compressed).use { source -> readBounded(source, MAX_BUILTIN_LUA_BYTES) }
            }
            val exactSha256 = sha256(exactBytes)
            require(exactSha256 == spec.sourceSha256) {
                "BUILTIN_LUA_ASSET_HASH_MISMATCH:${spec.gzipAsset}:$exactSha256"
            }
            val (imported, warnings) = NativeLuaArchiveImporter.import(ByteArrayInputStream(exactBytes))
            require(imported.packageSha256 == spec.sourceSha256) { "BUILTIN_LUA_IMPORT_HASH_MISMATCH:${spec.gzipAsset}" }
            require(imported.manifest.id == spec.sourceId) {
                "BUILTIN_LUA_SOURCE_ID_MISMATCH:${spec.gzipAsset}:${imported.manifest.id}"
            }
            require(imported.manifest.runtime.mode == spec.runtimeMode) {
                "BUILTIN_LUA_RUNTIME_MISMATCH:${spec.gzipAsset}:${imported.manifest.runtime.mode}"
            }
            builtinSourceIds += imported.manifest.id
            if (legacyWasRemoved || isBuiltinRemoved(imported.manifest.id)) {
                if (legacyWasRemoved) rememberBuiltinRemoved(imported.manifest.id)
                return@runCatching
            }
            validateCompatibility(imported)
            selfTest(imported)

            val existingBefore = store.load(imported.manifest.id)
            val exactVersionBefore = existingBefore?.versions?.firstOrNull {
                it.manifest.version == imported.manifest.version && it.packageSha256 == imported.packageSha256
            }
            val installedByBootstrap = when {
                existingBefore == null -> {
                    when (val result = store.install(imported, activate = true)) {
                        is SourcePlatformResult.Success -> true
                        is SourcePlatformResult.Failure -> error("${result.error.code}: ${result.error.message}")
                    }
                }
                exactVersionBefore != null -> false
                else -> {
                    diagnostics.emit(
                        DiagnosticEvent(
                            timestampEpochMs = System.currentTimeMillis(),
                            traceId = "bootstrap-lua-conflict:${spec.sourceId}",
                            sourceId = spec.sourceId,
                            sourceVersion = imported.manifest.version.toString(),
                            category = DiagnosticCategory.STORE,
                            name = "BUILTIN_LUA_SOURCE_CONFLICT_PRESERVED",
                            severity = DiagnosticSeverity.WARN,
                            attributes = mapOf(
                                "asset" to spec.gzipAsset,
                                "expectedSha256" to spec.sourceSha256,
                                "activeSha256" to existingBefore.active?.packageSha256.orEmpty(),
                                "activeSigner" to existingBefore.active?.signerKeyId.orEmpty(),
                            ),
                        ),
                    )
                    return@runCatching
                }
            }

            val exactInstalled = store.load(imported.manifest.id)
            val exactActive = exactInstalled?.active?.packageSha256 == imported.packageSha256
            require(exactInstalled?.versions?.any {
                it.manifest.version == imported.manifest.version && it.packageSha256 == imported.packageSha256
            } == true) { "BUILTIN_LUA_INSTALL_NOT_VISIBLE:${spec.sourceId}" }

            if (legacyIsExactBundledCopy && exactActive) {
                if (installedByBootstrap && legacyInstalled != null && legacyInstalled.enabled != exactInstalled.enabled) {
                    when (val result = store.setEnabled(imported.manifest.id, legacyInstalled.enabled)) {
                        is SourcePlatformResult.Success -> Unit
                        is SourcePlatformResult.Failure -> error("${result.error.code}: ${result.error.message}")
                    }
                }
                val legacyId = requireNotNull(legacyPack).manifest.id
                if (legacyId != imported.manifest.id && store.remove(legacyId)) {
                    diagnostics.emit(
                        DiagnosticEvent(
                            timestampEpochMs = System.currentTimeMillis(),
                            traceId = "bootstrap-lua-migrate:${spec.sourceId}",
                            sourceId = spec.sourceId,
                            sourceVersion = imported.manifest.version.toString(),
                            category = DiagnosticCategory.STORE,
                            name = "BUILTIN_SOURCE_MIGRATED_TO_EXACT_LUA",
                            attributes = mapOf(
                                "legacySourceId" to legacyId,
                                "legacyPackageSha256" to legacyPack.packageSha256,
                                "sourceSha256" to spec.sourceSha256,
                                "runtimeMode" to imported.manifest.runtime.mode.name,
                            ),
                        ),
                    )
                }
            }

            diagnostics.emit(
                DiagnosticEvent(
                    timestampEpochMs = System.currentTimeMillis(),
                    traceId = "bootstrap-lua:${spec.sourceId}",
                    sourceId = spec.sourceId,
                    sourceVersion = imported.manifest.version.toString(),
                    category = DiagnosticCategory.STORE,
                    name = "BUILTIN_EXACT_LUA_READY",
                    attributes = mapOf(
                        "asset" to spec.gzipAsset,
                        "sha256" to spec.sourceSha256,
                        "runtimeMode" to imported.manifest.runtime.mode.name,
                        "warningCount" to warnings.size.toString(),
                        "installedByBootstrap" to installedByBootstrap.toString(),
                    ),
                ),
            )
        }.onFailure { error ->
            diagnostics.emit(
                DiagnosticEvent(
                    timestampEpochMs = System.currentTimeMillis(),
                    traceId = "bootstrap-lua:${spec.sourceId}",
                    sourceId = spec.sourceId,
                    category = DiagnosticCategory.STORE,
                    name = "BUILTIN_EXACT_LUA_BOOTSTRAP_FAILED",
                    severity = DiagnosticSeverity.ERROR,
                    attributes = mapOf(
                        "asset" to spec.gzipAsset,
                        "expectedSha256" to spec.sourceSha256,
                        "error" to (error.message ?: error.javaClass.simpleName).take(1_000),
                    ),
                ),
            )
        }
    }

    private fun readLegacyBuiltinPack(assetName: String): VerifiedSourcePack =
        appContext.assets.open("sourcepacks/$assetName").use { input ->
            when (val result = verifier.verify(input, trustRegistry.allKeys())) {
                is SourcePlatformResult.Success -> result.value
                is SourcePlatformResult.Failure -> error("${result.error.code}: ${result.error.message}")
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

    private fun readBounded(
        input: InputStream,
        maxBytes: Int,
        tooLargeCode: String = "VBOOK_ZIP_SIZE_INVALID",
        emptyCode: String = tooLargeCode,
    ): ByteArray {
        require(maxBytes > 0) { "VBOOK_IMPORT_LIMIT_INVALID" }
        val output = ByteArrayOutputStream(minOf(maxBytes, 256 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { tooLargeCode }
            output.write(buffer, 0, read)
        }
        return output.toByteArray().also { require(it.isNotEmpty()) { emptyCode } }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun manualVBookIdentity(author: String, name: String, type: String, locale: String): SourceArtifactIdentity {
        val canonical = listOf(author, name, type, locale)
            .joinToString("\n") { it.trim().lowercase(Locale.ROOT) }
        require(canonical.replace("\n", "").isNotBlank()) { "VBOOK_MANUAL_IDENTITY_METADATA_REQUIRED" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return SourceArtifactIdentity(
            ecosystem = SourceEcosystem.VBOOK,
            repositoryId = MANUAL_VBOOK_REPOSITORY_ID,
            remoteIdentity = digest,
        )
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

    private fun <T> runExtensionOperation(
        stage: String,
        sourceId: String?,
        traceId: String = "extension-install:${UUID.randomUUID()}",
        operationId: String = "$traceId:$stage",
        operationKind: String = "EXTENSION_INSTALL",
        extraAttributes: Map<String, String> = emptyMap(),
        failureSeverity: DiagnosticSeverity = DiagnosticSeverity.ERROR,
        block: () -> T,
    ): Result<T> {
        val startedAt = System.currentTimeMillis()
        diagnostics.emit(DiagnosticEvent(
            timestampEpochMs = startedAt,
            traceId = traceId,
            sourceId = sourceId?.takeIf(String::isNotBlank) ?: "source-platform",
            category = DiagnosticCategory.PACKAGE,
            name = "SOURCE_EXTENSION_INSTALL_STARTED",
            attributes = DiagnosticOperationContract.attributes(
                id = operationId,
                kind = operationKind,
                flow = "package",
                state = DiagnosticOperationState.STARTED,
                stage = stage,
            ) + mapOf("installStage" to stage) + diagnosticBuildAttributes() + extraAttributes,
        ))
        return runCatching(block).onSuccess {
            diagnostics.emit(DiagnosticEvent(
                timestampEpochMs = System.currentTimeMillis(),
                traceId = traceId,
                sourceId = sourceId?.takeIf(String::isNotBlank) ?: "source-platform",
                category = DiagnosticCategory.PACKAGE,
                name = "SOURCE_EXTENSION_INSTALL_COMPLETED",
                durationMs = System.currentTimeMillis() - startedAt,
                attributes = DiagnosticOperationContract.attributes(
                    id = operationId,
                    kind = operationKind,
                    flow = "package",
                    state = DiagnosticOperationState.COMPLETED,
                    stage = stage,
                ) + mapOf("installStage" to stage) + diagnosticBuildAttributes() + extraAttributes,
            ))
        }.onFailure {
            recordExtensionFailure(
                stage, sourceId, it, traceId, operationId, startedAt, operationKind, extraAttributes,
                failureSeverity,
            )
        }
    }

    private fun recordExtensionFailure(
        stage: String,
        sourceId: String?,
        error: Throwable,
        traceId: String = "extension-install:${UUID.randomUUID()}",
        operationId: String = "$traceId:$stage",
        startedAt: Long? = null,
        operationKind: String = "EXTENSION_INSTALL",
        extraAttributes: Map<String, String> = emptyMap(),
        severity: DiagnosticSeverity = DiagnosticSeverity.ERROR,
    ) {
        val message = error.message ?: error.javaClass.simpleName
        // Action labels such as GENRE/SEARCH/DETAIL are context, not error codes. Extension error
        // codes consistently contain an underscore, so keep the causal chain free of those labels.
        val codes = extensionErrorCodes(error)
        diagnostics.emit(
            DiagnosticEvent(
                timestampEpochMs = System.currentTimeMillis(),
                traceId = traceId,
                sourceId = sourceId?.takeIf(String::isNotBlank) ?: "source-platform",
                category = DiagnosticCategory.PACKAGE,
                name = when (operationKind) {
                    "EXTENSION_IMPORT" -> "SOURCE_EXTENSION_IMPORT_FAILED"
                    "EXTENSION_FORMAT_PROBE" -> "SOURCE_EXTENSION_FORMAT_PROBE_REJECTED"
                    else -> "SOURCE_EXTENSION_INSTALL_FAILED"
                },
                severity = severity,
                durationMs = startedAt?.let { System.currentTimeMillis() - it },
                attributes = DiagnosticOperationContract.attributes(
                    id = operationId,
                    kind = operationKind,
                    flow = "package",
                    state = DiagnosticOperationState.FAILED,
                    stage = stage,
                ) + mapOf(
                    "code" to codes.lastOrNull().orEmpty().ifBlank {
                        if (operationKind == "EXTENSION_FORMAT_PROBE") "FORMAT_NOT_RECOGNIZED" else "UNKNOWN_INSTALL_ERROR"
                    },
                    "outerCode" to codes.firstOrNull().orEmpty().ifBlank {
                        if (operationKind == "EXTENSION_FORMAT_PROBE") "FORMAT_NOT_RECOGNIZED" else "UNKNOWN_INSTALL_ERROR"
                    },
                    "rootCauseCode" to codes.lastOrNull().orEmpty().ifBlank {
                        if (operationKind == "EXTENSION_FORMAT_PROBE") "FORMAT_NOT_RECOGNIZED" else "UNKNOWN_INSTALL_ERROR"
                    },
                    "errorCodes" to codes.joinToString(","),
                    "stage" to stage,
                    "message" to message.take(4_000),
                    "pendingWarnings" to pendingWarnings.take(20).joinToString(" | ").take(2_000),
                ) + diagnosticBuildAttributes() + DiagnosticThrowableFormatter.attributes(error) + extraAttributes,
            ),
        )
    }

    private fun emitImportParent(
        traceId: String,
        operationId: String,
        state: DiagnosticOperationState,
        stage: String,
        name: String,
        startedAt: Long,
        sourceId: String = "manual-import",
        attributes: Map<String, String>,
        severity: DiagnosticSeverity = if (state == DiagnosticOperationState.FAILED) DiagnosticSeverity.ERROR else DiagnosticSeverity.INFO,
    ) {
        diagnostics.emit(DiagnosticEvent(
            timestampEpochMs = System.currentTimeMillis(),
            traceId = traceId,
            sourceId = sourceId,
            category = DiagnosticCategory.PACKAGE,
            name = name,
            severity = severity,
            durationMs = if (state in setOf(
                    DiagnosticOperationState.COMPLETED,
                    DiagnosticOperationState.FAILED,
                    DiagnosticOperationState.CANCELLED,
                )) System.currentTimeMillis() - startedAt else null,
            attributes = DiagnosticOperationContract.attributes(
                id = operationId,
                kind = "EXTENSION_IMPORT",
                flow = "package",
                state = state,
                stage = stage,
            ) + attributes,
        ))
    }

    private fun diagnosticBuildAttributes(): Map<String, String> = mapOf(
        "appVersionName" to BuildConfig.VERSION_NAME,
        "appVersionCode" to BuildConfig.VERSION_CODE.toString(),
        "buildType" to BuildConfig.BUILD_TYPE,
        "diagnosticBuildId" to BuildConfig.DIAGNOSTIC_BUILD_ID,
        "symbolMappingIdentity" to "${BuildConfig.VERSION_CODE}:${BuildConfig.DIAGNOSTIC_BUILD_ID}",
    )

    private fun extensionErrorCodes(error: Throwable): List<String> = generateSequence(error) { current ->
        current.cause?.takeUnless { it === current }
    }.take(8).flatMap { cause ->
        EXTENSION_ERROR_CODE.findAll(cause.message.orEmpty()).map(MatchResult::value)
    }.filter { '_' in it }.distinct().take(20).toList()

    private data class PendingImportContext(
        val traceId: String,
        val operationId: String,
        val startedAt: Long,
        val attributes: Map<String, String>,
    )

    private data class PendingVBookImport(
        val identity: SourceArtifactIdentity,
        val version: String,
        val bytes: ByteArray,
        val sourceId: String,
        val name: String,
    )

    private data class CachedRepository(
        val url: String,
        val repository: VerifiedSourceRepository,
    )

    private data class SelfTestSummary(val checkCount: Int)

    private data class BuiltinLuaSourceSpec(
        val legacySourcePackAsset: String,
        val gzipAsset: String,
        val sourceId: String,
        val sourceSha256: String,
        val runtimeMode: SourceRuntimeMode,
    )

    companion object {
        val CURRENT_APP_VERSION: SemanticVersion = SemanticVersion.parse(BuildConfig.VERSION_NAME)
        private const val REPOSITORY_INDEX_FILE = "index.json"
        private const val REPOSITORY_URL_FILE = "url.txt"
        private const val MANUAL_VBOOK_REPOSITORY_ID = "manual.vbook.local"
        private const val DEMO_BUILTIN_SOURCEPACK_ASSET = "demo.ntsource"
        private const val MAX_BUILTIN_LUA_BYTES = 1024 * 1024
        private const val MAX_MANUAL_IMPORT_BYTES = 64 * 1024 * 1024
        private const val BUILTIN_REMOVAL_PREFERENCES = "source_platform_builtin_removals"
        private const val REMOVED_BUILTIN_SOURCE_IDS = "removed_source_ids"
        private val REPOSITORY_ID = Regex("^[a-z][a-z0-9]*(\\.[a-z0-9][a-z0-9-]*){2,}$")
        private val EXTENSION_ERROR_CODE = Regex("[A-Z][A-Z0-9_]{2,}")
        private val BUILTIN_LUA_SOURCES = listOf(
            BuiltinLuaSourceSpec(
                legacySourcePackAsset = "truyenfull.ntsource",
                gzipAsset = "nguon_truyenfull_native.lua.gz",
                sourceId = "vn.nghetruyen.native.truyenfull-native",
                sourceSha256 = "77d4a70859592c391763ed883048d219bb973931aef4131c0ae4e5a10b8d3c68",
                runtimeMode = SourceRuntimeMode.NATIVE_LUA_COMPAT,
            ),
            BuiltinLuaSourceSpec(
                legacySourcePackAsset = "truyencv.ntsource",
                gzipAsset = "nguon_truyencv_native.lua.gz",
                sourceId = "vn.nghetruyen.native.truyencv-io-default-native",
                sourceSha256 = "5bcb34b1c6e87ab0c63f430e34b3b14e41eb2903cff99f1a8310e650b1a83d8b",
                runtimeMode = SourceRuntimeMode.NATIVE_LUA_COMPAT,
            ),
            BuiltinLuaSourceSpec(
                legacySourcePackAsset = "truyencom.ntsource",
                gzipAsset = "nguon_truyencom_native.lua.gz",
                sourceId = "vn.nghetruyen.native.truyencom-default-native",
                sourceSha256 = "1052cddf2059b973f04a7a2e02d0ddea06d0f4e0ef49210a359fc4651102d58f",
                runtimeMode = SourceRuntimeMode.NATIVE_LUA_COMPAT,
            ),
            BuiltinLuaSourceSpec(
                legacySourcePackAsset = "truyenyy.ntsource",
                gzipAsset = "nguon_truyenyy_native.lua.gz",
                sourceId = "vn.nghetruyen.native.truyenyy-co-native",
                sourceSha256 = "2f9b40b0c7fa2274ef57e1994b314472b937be5dd13bb9287bdbfd7557f7bffc",
                runtimeMode = SourceRuntimeMode.NATIVE_LUA_COMPAT,
            ),
            BuiltinLuaSourceSpec(
                legacySourcePackAsset = "wikidich.ntsource",
                gzipAsset = "nguon_wikidich_native.lua.gz",
                sourceId = "vn.nghetruyen.native.wikidich-default-native-v9-complete-scroll",
                sourceSha256 = "d49c62c4cd14f2111e43b53491e8d5623a7f2eaf6821233bc1aa7d0950501ecc",
                runtimeMode = SourceRuntimeMode.NATIVE_LUA_COMPAT,
            ),
            BuiltinLuaSourceSpec(
                legacySourcePackAsset = "sangtacviet.ntsource",
                gzipAsset = "nguon_sangtacviet_native.lua.gz",
                sourceId = "vn.nghetruyen.native.sangtacviet-native-instant-fast-v50",
                sourceSha256 = "f51d7eeed874eb93220fda0750670d9bf72dcdcd6648440f43f41315d83b0577",
                runtimeMode = SourceRuntimeMode.NATIVE_LUA_COMPAT,
            ),
            BuiltinLuaSourceSpec(
                legacySourcePackAsset = "wattpad.ntsource",
                gzipAsset = "nguon_wattpad_vbook.lua.gz",
                sourceId = "vn.nghetruyen.vbook.wattpad-default-vbook",
                sourceSha256 = "bd678f1a7245dbf979d24f0423920bb3c4f00654ae89b4beca03164ec4d79b0b",
                runtimeMode = SourceRuntimeMode.VBOOK_JS_COMPAT,
            ),
        )
    }
}
