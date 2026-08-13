package vn.nghetruyen.app.sourceplatform

import com.nghetruyen.source.platform.SourceArtifactIdentity
import com.nghetruyen.source.platform.SourceEcosystem
import com.nghetruyen.source.repository.VBookUpdateDisposition
import vn.nghetruyen.app.sources.StorySource
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.diagnostics.DiagnosticJsonExporter
import vn.nghetruyen.source.diagnostics.SourceTraceExplorer
import vn.nghetruyen.source.vbook.VBookHostManifestFactory
import vn.nghetruyen.source.vbook.VBookManifestParser
import vn.nghetruyen.source.vbook.VBookPackageReader
import vn.nghetruyen.source.vbook.VBookRepositorySnapshot
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * UI-facing facade that preserves the existing source-management surface while routing ownership
 * to the correct ecosystem. vBook artifacts never flow back through SourcePackStore.
 */
class UnifiedSourcePlatformManager(
    private val legacy: SourcePlatformManager,
    private val vBook: VBookSourcePlatform,
    private val vBookRepositories: VBookRepositoryClient,
    private val vBookRepositorySubscriptions: VBookRepositorySubscriptionStore,
    private val onExternalSourcesChanged: () -> Unit,
) {
    private val vBookSnapshots = linkedMapOf<String, VBookRepositorySnapshot>()
    private val vBookCatalog = VBookCatalogInstallService(vBookRepositories, vBook)
    private var pendingVBookCatalog: VBookPreparedCatalogInstall? = null

    fun activeStorySources(): List<StorySource> = legacy.activeStorySources() + vBook.activeStorySources()

    fun installedPacks(): List<SourcePackUiInfo> = buildList {
        addAll(legacy.installedPacks())
        addAll(vBook.installedSources().map(::installedUi))
    }.distinctBy(SourcePackUiInfo::id)

    fun repositories(): List<SourceRepositoryUiInfo> = buildList {
        addAll(legacy.repositories())
        addAll(vBookSnapshots.map { (uiId, snapshot) ->
            SourceRepositoryUiInfo(
                id = uiId,
                name = "vBook · ${snapshot.repositories.size} catalog",
                url = snapshot.indexUrl,
                generatedAtEpochMs = 0L,
                expiresAtEpochMs = 0L,
                packageCount = snapshot.items.size,
                signerKeyId = if (snapshot.complete) "vbook-index-hash" else "vbook-index-partial",
            )
        })
        vBookRepositorySubscriptions.urls().forEach { url ->
            val uiId = vBookIndexUiId(url)
            if (uiId !in vBookSnapshots) {
                add(SourceRepositoryUiInfo(
                    id = uiId,
                    name = "vBook · Đã lưu",
                    url = url,
                    generatedAtEpochMs = 0L,
                    expiresAtEpochMs = 0L,
                    packageCount = 0,
                    signerKeyId = "vbook-index-saved",
                ))
            }
        }
    }.distinctBy(SourceRepositoryUiInfo::id)

    fun repositoryPackages(): List<SourceRepositoryPackageUiInfo> = buildList {
        addAll(legacy.repositoryPackages())
        val installed = vBook.installedSources().associateBy { it.repositoryId to it.remoteIdentity }
        vBookSnapshots.forEach { (uiId, snapshot) ->
            snapshot.items.forEach { aggregated ->
                val local = installed[aggregated.repositoryId to aggregated.remoteIdentity]
                val state = repositoryState(local?.version, aggregated.item.version)
                val packageIsHttps = aggregated.item.packageUrl.startsWith("https://", ignoreCase = true)
                add(SourceRepositoryPackageUiInfo(
                    repositoryId = uiId,
                    sourceId = aggregated.installIdentity,
                    name = aggregated.item.name.ifBlank { aggregated.remoteIdentity },
                    version = aggregated.item.version,
                    installedVersion = local?.version,
                    description = buildString {
                        if (aggregated.item.description.isNotBlank()) append(aggregated.item.description.trim())
                        if (aggregated.repository.author.isNotBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append("Catalog: ").append(aggregated.repository.author)
                        }
                        if (!packageIsHttps) {
                            if (isNotEmpty()) append(" · ")
                            append("Gói HTTP không an toàn")
                        }
                    },
                    changelog = "",
                    packageBytes = 0,
                    status = if (packageIsHttps) state else "INSECURE_PACKAGE_URL",
                    // Lua-style compatibility: let the user deliberately reinstall the same version
                    // or install an older repository version. Exact-byte preview/validation still runs.
                    canInstall = packageIsHttps && state in setOf(
                        "NOT_INSTALLED", "UPDATE_AVAILABLE", "VERSION_UNKNOWN", "CURRENT", "REMOTE_OLDER",
                    ),
                ))
            }
        }
    }.distinctBy { it.repositoryId to it.sourceId }

    /** Try the native signed-repository schema first; fall back to the canonical vBook index schema. */
    fun refreshRepository(url: String): Result<SourceRepositoryUiInfo> {
        clearPendingCatalogInstall()
        val native = legacy.probeRepository(url)
        if (native.isSuccess) return native
        val nativeError = native.exceptionOrNull()?.message.orEmpty().take(2_000)
        return legacy.runExternalExtensionOperation(
            stage = "vbook_repository_refresh",
            sourceId = null,
            operationKind = "EXTENSION_REPOSITORY",
            extraAttributes = mapOf(
                "repositoryFingerprint" to vBookIndexUiId(url),
                "nativeRepositoryProbeError" to nativeError,
            ),
            block = {
                val snapshot = vBookRepositories.snapshot(url, strict = false)
                require(snapshot.repositories.isNotEmpty()) {
                    "vBook repository không có catalog hợp lệ: ${snapshot.errors.joinToString { it.code }}"
                }
                val uiId = vBookIndexUiId(snapshot.indexUrl)
                vBookSnapshots[uiId] = snapshot
                vBookRepositorySubscriptions.add(snapshot.indexUrl)
                repositories().first { it.id == uiId }
            },
        ).recoverCatching { vBookError ->
            error(listOf(nativeError, vBookError.message.orEmpty()).filter(String::isNotBlank).joinToString(" | "))
        }
    }

    fun removeRepository(repositoryId: String): Result<Unit> {
        clearPendingCatalogInstall()
        val snapshot = vBookSnapshots.remove(repositoryId)
        val persistedUrl = snapshot?.indexUrl ?: vBookRepositorySubscriptions.urls()
            .firstOrNull { vBookIndexUiId(it) == repositoryId }
        if (persistedUrl != null) {
            return runCatching {
                vBookRepositories.evictCachedDocument(persistedUrl)
                vBookRepositorySubscriptions.remove(persistedUrl)
            }
        }
        return legacy.removeRepository(repositoryId)
    }

    /** Rehydrates saved vBook repositories. Failed/offline URLs stay subscribed and remain visible. */
    fun restorePersistedRepositories(): Int {
        clearPendingCatalogInstall()
        var restored = 0
        vBookRepositorySubscriptions.urls().forEach { url ->
            runCatching { vBookRepositories.snapshot(url, strict = false) }
                .onSuccess { snapshot ->
                    if (snapshot.repositories.isNotEmpty()) {
                        vBookSnapshots[vBookIndexUiId(snapshot.indexUrl)] = snapshot
                        if (!snapshot.indexUrl.equals(url, ignoreCase = false)) {
                            runCatching {
                                vBookRepositorySubscriptions.remove(url)
                                vBookRepositorySubscriptions.add(snapshot.indexUrl)
                            }
                        }
                        restored += 1
                    }
                }
        }
        return restored
    }

    /**
     * Bind repository preview and confirmation to the same exact ZIP and canonical catalog identity.
     * The legacy manager is used only to render the existing permission-preview UI; it never owns
     * confirmation for a catalog vBook package.
     */
    fun prepareRepositoryInstall(repositoryId: String, sourceId: String): Result<SourceInstallPreview> {
        val snapshot = vBookSnapshots[repositoryId] ?: run {
            clearPendingCatalogInstall()
            return legacy.prepareRepositoryInstall(repositoryId, sourceId)
        }
        return legacy.runExternalExtensionOperation(
            stage = "vbook_repository_prepare_install",
            sourceId = sourceId,
            operationKind = "EXTENSION_REPOSITORY_INSTALL",
            extraAttributes = mapOf("repositoryId" to repositoryId.take(300)),
            block = {
                val item = snapshot.items.firstOrNull { it.installIdentity == sourceId }
                    ?: error("Không tìm thấy tiện ích vBook trong repository snapshot.")
                require(item.item.packageUrl.startsWith("https://", ignoreCase = true)) {
                    "VBOOK_REPOSITORY_PACKAGE_HTTPS_REQUIRED"
                }
                val prepared = vBookCatalog.prepare(item)
                require(prepared.preview.activatable) {
                    val blockers = prepared.preview.blockingFeatures.sortedBy(Enum<*>::name).joinToString { it.name }
                    val failures = prepared.preview.validation.failures.joinToString("; ") { it.message }
                    "VBOOK_IMPORT_NOT_ACTIVATABLE:${listOf(blockers, failures).filter(String::isNotBlank).joinToString(" | ")}"
                }
                val presentation = ByteArrayInputStream(prepared.bytes).use { legacy.prepareVBookImport(it).getOrThrow() }
                pendingVBookCatalog = prepared
                val identity = SourceArtifactIdentity(SourceEcosystem.VBOOK, item.repositoryId, item.remoteIdentity)
                presentation.copy(
                    sourceId = VBookHostManifestFactory.stableSourceId(identity.canonicalKey()),
                    name = prepared.preview.name,
                    version = prepared.preview.version ?: item.item.version,
                )
            },
        ).onFailure {
            pendingVBookCatalog = null
            legacy.cancelPendingInstall()
        }
    }

    /**
     * Generic file import must never convert a newly supplied vBook ZIP back into SourcePack.
     * Detect the exact raw package at the ecosystem facade and route it to the S4 vBook transaction.
     */
    fun prepareInstall(input: InputStream): Result<SourceInstallPreview> {
        clearPendingCatalogInstall()
        return runCatching { readBounded(input, MAX_IMPORT_BYTES) }.fold(
            onSuccess = { bytes ->
                val isVBook = runCatching {
                    val pkg = VBookPackageReader.read(bytes)
                    VBookManifestParser.parse(pkg.pluginJson())
                }.isSuccess
                if (isVBook) legacy.prepareVBookImport(ByteArrayInputStream(bytes))
                else legacy.prepareInstall(ByteArrayInputStream(bytes))
            },
            onFailure = { Result.failure(it) },
        )
    }

    fun prepareManualImport(
        input: InputStream,
        metadata: SourceImportFileMetadata = SourceImportFileMetadata(),
    ): Result<SourceInstallPreview> {
        clearPendingCatalogInstall()
        return legacy.prepareManualImport(input, metadata)
    }

    fun prepareVBookImport(input: InputStream): Result<SourceInstallPreview> {
        clearPendingCatalogInstall()
        return legacy.prepareVBookImport(input)
    }

    fun prepareNativeLuaImport(input: InputStream): Result<SourceInstallPreview> {
        clearPendingCatalogInstall()
        return legacy.prepareNativeLuaImport(input)
    }

    fun pendingInstallWarnings(): List<String> = buildList {
        addAll(legacy.pendingInstallWarnings())
        pendingVBookCatalog?.preview?.warnings?.let(::addAll)
    }.distinct()

    fun confirmPendingInstall(): Result<SourcePackUiInfo> {
        val prepared = pendingVBookCatalog ?: return legacy.confirmPendingInstall()
        pendingVBookCatalog = null
        legacy.cancelPendingInstall()
        return legacy.runExternalExtensionOperation(
            stage = "vbook_catalog_confirm_install",
            sourceId = prepared.item.installIdentity,
            operationKind = "EXTENSION_INSTALL_COMMIT",
            extraAttributes = mapOf(
                "repositoryId" to prepared.item.repositoryId.take(300),
                "remoteIdentity" to prepared.item.remoteIdentity.take(300),
                "version" to prepared.item.item.version.take(100),
            ),
            block = {
                val result = vBookCatalog.installPrepared(prepared)
                require(result.disposition == VBookUpdateDisposition.ACTIVATED && result.active != null) {
                    val blockers = result.validation.blockingFeatures.sortedBy(Enum<*>::name).joinToString { it.name }
                    "VBOOK_INSTALL_QUARANTINED:${blockers.ifBlank { result.validation.failures.joinToString("; ") { it.message } }}"
                }
                onExternalSourcesChanged()
                val info = vBook.installedSources().firstOrNull {
                    it.repositoryId == prepared.item.repositoryId && it.remoteIdentity == prepared.item.remoteIdentity
                } ?: error("VBOOK_INSTALLED_SOURCE_NOT_FOUND_AFTER_ACTIVATION")
                installedUi(info)
            },
        )
    }

    fun cancelPendingInstall() {
        pendingVBookCatalog = null
        legacy.cancelPendingInstall()
    }

    fun trustKeys() = legacy.trustKeys()
    fun enrollTrustKey(keyId: String, algorithm: String, publicKeyBase64: String, fingerprint: String) =
        legacy.enrollTrustKey(keyId, algorithm, publicKeyBase64, fingerprint)
    fun revokeTrustKey(keyId: String) = legacy.revokeTrustKey(keyId)
    fun applyTrustKeyRotation(raw: ByteArray) = legacy.applyTrustKeyRotation(raw)

    fun setEnabled(sourceId: String, enabled: Boolean): Result<Unit> {
        val vBookInstalled = vBook.installedSources().any { it.sourceId == sourceId }
        if (!vBookInstalled) return legacy.setEnabled(sourceId, enabled)
        return legacy.runExternalExtensionOperation(
            stage = if (enabled) "enable_vbook_source" else "disable_vbook_source",
            sourceId = sourceId,
            operationKind = "EXTENSION_ACTIVATION",
            extraAttributes = mapOf("enabled" to enabled.toString()),
            block = {
                vBook.setEnabled(sourceId, enabled)
                onExternalSourcesChanged()
            },
        )
    }

    fun rollback(sourceId: String): Result<Unit> {
        val vBookInstalled = vBook.installedSources().any { it.sourceId == sourceId }
        if (!vBookInstalled) return legacy.rollback(sourceId)
        return legacy.runExternalExtensionOperation(
            stage = "rollback_vbook_source",
            sourceId = sourceId,
            operationKind = "EXTENSION_ROLLBACK",
            block = {
                vBook.rollbackBySourceId(sourceId)
                onExternalSourcesChanged()
            },
        )
    }

    fun removeInstalledPack(sourceId: String): Result<Unit> {
        val vBookInstalled = vBook.installedSources().any { it.sourceId == sourceId }
        if (!vBookInstalled) return legacy.removeInstalledPack(sourceId)
        return legacy.runExternalExtensionOperation(
            stage = "remove_vbook_source",
            sourceId = sourceId,
            operationKind = "EXTENSION_REMOVE",
            block = {
                require(vBook.uninstallBySourceId(sourceId)) { "Không tìm thấy tiện ích vBook để xóa." }
                onExternalSourcesChanged()
            },
        )
    }

    fun saveConfiguration(sourceId: String, changes: Map<String, String>): Result<Unit> = runCatching {
        require(vBook.installedSources().any { it.sourceId == sourceId }) { "Cấu hình này chỉ áp dụng cho tiện ích vBook." }
        vBook.saveConfigBySourceId(sourceId, changes)
        onExternalSourcesChanged()
    }

    fun resetConfiguration(sourceId: String): Result<Unit> = runCatching {
        require(vBook.installedSources().any { it.sourceId == sourceId }) { "Cấu hình này chỉ áp dụng cho tiện ích vBook." }
        vBook.resetConfigBySourceId(sourceId)
        onExternalSourcesChanged()
    }

    fun checkInstalledPack(sourceId: String): Result<String> = runCatching {
        val installed = vBook.installedSources().firstOrNull { it.sourceId == sourceId }
            ?: error("Kiểm tra gói ngoại tuyến hiện chỉ áp dụng cho tiện ích vBook.")
        val validation = vBook.validateBySourceId(sourceId)
        require(validation.activatable) {
            val blockers = validation.blockingFeatures.sortedBy(Enum<*>::name).joinToString { it.name }
            val failures = validation.failures.joinToString("; ") { it.message }
            listOf(blockers, failures).filter(String::isNotBlank).joinToString(" | ").ifBlank { "Gói không thể kích hoạt." }
        }
        "${installed.name}: gói vBook gốc, cấu trúc, tập lệnh và khả năng tương thích đều hợp lệ."
    }

    fun vBookLoginInfo(sourceId: String): VBookLoginInfo? = runCatching {
        vBook.loginInfoBySourceId(sourceId)
    }.getOrNull()

    /** vBook export is the exact immutable ZIP originally staged, not a reconstructed archive. */
    fun exportInstalledPack(sourceId: String, output: OutputStream): Result<Unit> {
        val installed = vBook.installedSources().firstOrNull { it.sourceId == sourceId }
            ?: return legacy.exportInstalledPack(sourceId, output)
        return runCatching {
            val bytes = vBook.originalPackageBytes(installed.artifactId)
                ?: error("Không tìm thấy ZIP vBook gốc trong archive.")
            output.write(bytes)
            output.flush()
        }
    }

    fun inspectSelector(html: String, selector: String, baseUrl: String) = legacy.inspectSelector(html, selector, baseUrl)
    fun diagnosticsSnapshot(sourceId: String? = null) =
        (legacy.diagnosticsSnapshot(sourceId) + vBook.diagnosticsSnapshot(sourceId))
            .distinct()
            .sortedBy { it.timestampEpochMs }

    fun diagnosticTraces(limit: Int = 20): List<SourceTraceUi> = SourceTraceExplorer.summarize(diagnosticsSnapshot())
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

    fun diagnosticSummaries(limit: Int = 20): List<SourceDiagnosticUi> = diagnosticsSnapshot()
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

    fun exportDiagnostics(): ByteArray = DiagnosticJsonExporter.export(diagnosticsSnapshot())

    fun clearDiagnostics() {
        legacy.clearDiagnostics()
        vBook.clearDiagnostics()
    }

    private fun installedUi(installed: VBookInstalledSourceInfo): SourcePackUiInfo = SourcePackUiInfo(
        id = installed.sourceId,
        name = installed.name,
        version = installed.version,
        enabled = installed.enabled,
        installedVersions = installed.installedVersions,
        canRollback = installed.canRollback,
        signerKeyId = "vbook-unmodified",
        runtimeMode = SourceRuntimeMode.VBOOK_JS_COMPAT.name,
        commentCapability = "VBOOK_DYNAMIC",
        commentFixtureCount = 0,
        removable = true,
        ecosystem = "VBOOK",
        contentType = installed.contentType.name,
        compatibilityProfile = installed.profileId,
        configFields = runCatching { vBook.configFieldsBySourceId(installed.sourceId) }.getOrDefault(emptyList()),
        loginAvailable = installed.loginAvailable,
    )

    private fun clearPendingCatalogInstall() {
        pendingVBookCatalog = null
        legacy.cancelPendingInstall()
    }

    private fun repositoryState(local: String?, remote: String): String {
        if (local == null) return "NOT_INSTALLED"
        if (local == remote) return "CURRENT"
        val localNumber = local.toLongOrNull()
        val remoteNumber = remote.toLongOrNull()
        if (localNumber == null || remoteNumber == null) return "VERSION_UNKNOWN"
        return when {
            remoteNumber > localNumber -> "UPDATE_AVAILABLE"
            remoteNumber < localNumber -> "REMOTE_OLDER"
            else -> "CURRENT"
        }
    }

    private fun vBookIndexUiId(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "vbook.index.$digest"
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(64 * 1024, maxBytes))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { "SOURCE_IMPORT_TOO_LARGE" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    companion object {
        private const val MAX_IMPORT_BYTES = 64 * 1024 * 1024
    }
}
