package vn.nghetruyen.app.sourceplatform

import android.content.Context
import com.nghetruyen.source.platform.SourceArtifactDescriptor
import com.nghetruyen.source.platform.SourceArtifactIdentity
import com.nghetruyen.source.platform.SourceArtifactState
import com.nghetruyen.source.platform.SourceCompatibilityState
import com.nghetruyen.source.platform.SourceEcosystem
import com.nghetruyen.source.platform.SourceTrustState
import com.nghetruyen.source.repository.VBookUpdateCoordinator
import com.nghetruyen.source.repository.VBookUpdatePayload
import com.nghetruyen.source.repository.VBookUpdateResult
import com.nghetruyen.source.store.FileSourceArtifactStore
import com.nghetruyen.source.store.SourceArtifactLifecycle
import vn.nghetruyen.app.ai.TranslationEngine
import vn.nghetruyen.app.sources.SourceSessionStore
import vn.nghetruyen.app.sources.StorySource
import vn.nghetruyen.source.api.SourceCapabilityBrokers
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.diagnostics.BoundedDiagnosticRecorder
import vn.nghetruyen.source.diagnostics.DiagnosticLevel
import vn.nghetruyen.source.diagnostics.DiagnosticEvent
import vn.nghetruyen.source.diagnostics.DiagnosticEvidenceSink
import vn.nghetruyen.source.network.OkHttpSourceNetworkBroker
import vn.nghetruyen.source.network.OkHttpSourceWebSocketBroker
import vn.nghetruyen.source.network.PartitionedSourceCookieJar
import vn.nghetruyen.source.runtime.JcaSourceCryptoBroker
import vn.nghetruyen.source.lua.LuaNativeHookBroker
import vn.nghetruyen.source.vbook.VBookCandidate
import vn.nghetruyen.source.vbook.VBookCandidateValidation
import vn.nghetruyen.source.vbook.VBookConfigService
import vn.nghetruyen.source.vbook.VBookConfigSnapshot
import vn.nghetruyen.source.vbook.VBookCompositeConfigReader
import vn.nghetruyen.source.vbook.VBookContentType
import vn.nghetruyen.source.vbook.VBookExtensionManifest
import vn.nghetruyen.source.vbook.VBookFeature
import vn.nghetruyen.source.vbook.VBookHostManifestFactory
import vn.nghetruyen.source.vbook.VBookManifestParser
import vn.nghetruyen.source.vbook.VBookPackageReader
import vn.nghetruyen.source.vbook.VBookProviderSession
import vn.nghetruyen.source.vbook.VBookValidationFactory
import java.io.File
import java.net.URI

data class VBookInstallPreview(
    val repositoryId: String,
    val remoteIdentity: String,
    val version: String?,
    val name: String,
    val contentType: VBookContentType,
    val state: SourceCompatibilityState,
    val blockingFeatures: Set<VBookFeature>,
    val warnings: List<String>,
    val validation: VBookCandidateValidation,
) {
    val activatable: Boolean get() = validation.activatable
}

data class VBookInstalledSourceInfo(
    val sourceId: String,
    val artifactId: String,
    val repositoryId: String,
    val remoteIdentity: String,
    val name: String,
    val version: String,
    val enabled: Boolean,
    val contentType: VBookContentType,
    val profileId: String,
    val canRollback: Boolean,
    val installedVersions: List<String>,
    val loginAvailable: Boolean,
)

data class VBookLoginInfo(val sourceId: String, val loginUrl: String, val allowedHosts: Set<String>)

/**
 * Persistent vBook subsystem. It deliberately does not install vBook packages into SourcePackStore.
 * Original package bytes and repository identity remain authoritative across updates and rollback.
 */
class VBookSourcePlatform(
    context: Context,
    sourceSessionStore: SourceSessionStore,
    translationEngine: TranslationEngine,
    root: File = File(context.filesDir, "source-platform/vbook"),
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val diagnostics: BoundedDiagnosticRecorder = BoundedDiagnosticRecorder(maxEvents = 8_000, level = DiagnosticLevel.BASIC),
    private val evidence: DiagnosticEvidenceSink = DiagnosticEvidenceSink.NONE,
) {
    private val appContext = context.applicationContext
    private val cookiePartition = VBookSessionCookiePartition(
        PartitionedSourceCookieJar(EncryptedSourceCookiePersistence(appContext)),
        sourceSessionStore,
    )
    private val translationBroker = AndroidSourceTranslationBroker(translationEngine)
    private val configStore = AndroidVBookConfigStore(appContext)
    private val secretStore = AndroidVBookSecretStore(appContext)
    private val configReader = VBookCompositeConfigReader(configStore, secretStore)
    private val configService = VBookConfigService(configStore, secretStore)
    private val brokers = SourceCapabilityBrokers(
        network = OkHttpSourceNetworkBroker(cookiePartition, diagnostics),
        browser = AndroidSourceBrowserBroker(appContext, cookiePartition, diagnostics, evidence = evidence),
        storage = EncryptedSourceStorageBroker(File(root, "storage")),
        crypto = JcaSourceCryptoBroker(AndroidSourceSecretKeyProvider()),
        websocket = OkHttpSourceWebSocketBroker(cookiePartition, diagnostics),
        nativeHooks = LuaNativeHookBroker(),
        graphics = AndroidSourceGraphicsBroker(),
        translation = translationBroker,
        cookies = cookiePartition,
        quickTranslation = AndroidVBookQuickTranslationRegistry,
    )
    private val store = FileSourceArtifactStore(root.toPath())
    private val validator = VBookValidationFactory.production()
    private val coordinator = VBookUpdateCoordinator(
        validator = validator,
        registry = store,
        archive = store,
    )
    private val storySourceCache = ArtifactValueCache<VBookStorySource>(32)
    private val manifestCache = ArtifactValueCache<VBookExtensionManifest>(64)

    fun preview(
        repositoryId: String,
        remoteIdentity: String,
        version: String?,
        packageBytes: ByteArray,
    ): VBookInstallPreview {
        require(repositoryId.isNotBlank()) { "VBOOK_REPOSITORY_ID_REQUIRED" }
        require(remoteIdentity.isNotBlank()) { "VBOOK_REMOTE_IDENTITY_REQUIRED" }
        val identity = SourceArtifactIdentity(SourceEcosystem.VBOOK, repositoryId.trim(), remoteIdentity.trim())
        val packageSha = SourceArtifactLifecycle.sha256(packageBytes)
        val artifactId = artifactId(identity, packageSha)
        val pkg = VBookPackageReader.read(packageBytes)
        val pluginJson = pkg.pluginJson()
        val manifest = VBookManifestParser.parse(pluginJson)
        val validation = validator.validate(VBookCandidate(artifactId, pluginJson, pkg.decodeScripts()))
        return VBookInstallPreview(
            repositoryId = identity.repositoryId,
            remoteIdentity = identity.remoteIdentity,
            version = version,
            name = manifest.metadata.name.ifBlank { identity.remoteIdentity },
            contentType = manifest.metadata.type,
            state = validation.state,
            blockingFeatures = validation.blockingFeatures,
            warnings = validation.warnings,
            validation = validation,
        )
    }

    fun installOrUpdate(
        repositoryId: String,
        remoteIdentity: String,
        version: String?,
        packageBytes: ByteArray,
        trust: SourceTrustState = SourceTrustState.REPOSITORY_TRUSTED,
    ): VBookUpdateResult {
        require(repositoryId.isNotBlank()) { "VBOOK_REPOSITORY_ID_REQUIRED" }
        require(remoteIdentity.isNotBlank()) { "VBOOK_REMOTE_IDENTITY_REQUIRED" }
        val identity = SourceArtifactIdentity(SourceEcosystem.VBOOK, repositoryId.trim(), remoteIdentity.trim())
        val packageSha = SourceArtifactLifecycle.sha256(packageBytes)
        val now = clockMs()
        return coordinator.installOrUpdate(
            VBookUpdatePayload(
                artifactId = artifactId(identity, packageSha),
                identity = identity,
                version = version,
                originalPackageBytes = packageBytes.copyOf(),
                trust = trust,
                installedAtEpochMs = now,
            ),
            activatedAtEpochMs = now,
        )
    }

    fun rollback(repositoryId: String, remoteIdentity: String): SourceArtifactDescriptor =
        coordinator.rollback(
            SourceArtifactIdentity(SourceEcosystem.VBOOK, repositoryId.trim(), remoteIdentity.trim()),
            activatedAtEpochMs = clockMs(),
        )

    fun activeArtifacts(): List<SourceArtifactDescriptor> = store.activeArtifacts(SourceEcosystem.VBOOK)

    fun installedSources(): List<VBookInstalledSourceInfo> {
        val installed = store.installedArtifacts(SourceEcosystem.VBOOK)
        manifestCache.retainKeys(installed.mapTo(linkedSetOf()) { it.artifactId })
        return installed.mapNotNull { current ->
            val manifest = runCatching { manifestFor(current) }.getOrNull() ?: return@mapNotNull null
        val previous = store.previousKnownGood(current.identity)
        VBookInstalledSourceInfo(
            sourceId = VBookHostManifestFactory.stableSourceId(current.identity.canonicalKey()),
            artifactId = current.artifactId,
            repositoryId = current.identity.repositoryId,
            remoteIdentity = current.identity.remoteIdentity,
            name = manifest.metadata.name.ifBlank { current.identity.remoteIdentity },
            version = current.version.orEmpty(),
            enabled = current.state == SourceArtifactState.ACTIVE,
            contentType = manifest.metadata.type,
            profileId = current.compatibilityProfile?.id.orEmpty(),
            canRollback = current.state == SourceArtifactState.ACTIVE && previous != null,
            installedVersions = listOfNotNull(current.version, previous?.version).distinct(),
            loginAvailable = loginInfo(manifest, sourceId = VBookHostManifestFactory.stableSourceId(current.identity.canonicalKey())) != null,
        )
        }
    }

    fun loginInfoBySourceId(sourceId: String): VBookLoginInfo? {
        val current = installedBySourceId(sourceId)
        val manifest = runCatching { manifestFor(current) }.getOrNull() ?: return null
        return loginInfo(manifest, sourceId)
    }

    fun setEnabled(sourceId: String, enabled: Boolean): VBookInstalledSourceInfo {
        val current = installedBySourceId(sourceId)
        when {
            enabled && current.state == SourceArtifactState.DISABLED ->
                store.commit(SourceArtifactLifecycle.enable(current, clockMs()))
            !enabled && current.state == SourceArtifactState.ACTIVE ->
                store.commit(SourceArtifactLifecycle.disable(current))
        }
        return installedSources().first { it.sourceId == sourceId }
    }

    fun rollbackBySourceId(sourceId: String): SourceArtifactDescriptor {
        val current = installedBySourceId(sourceId)
        require(current.state == SourceArtifactState.ACTIVE) { "VBOOK_ROLLBACK_ACTIVE_REQUIRED" }
        return coordinator.rollback(current.identity, clockMs())
    }

    /** Removes install/config/runtime state pointers while retaining immutable artifact bytes for audit. */
    fun uninstallBySourceId(sourceId: String): Boolean {
        val current = installedBySourceId(sourceId)
        when (val cleared = brokers.storage.clear(sourceId)) {
            is SourcePlatformResult.Success -> Unit
            is SourcePlatformResult.Failure -> error("VBOOK_STORAGE_CLEANUP_${cleared.error.code}:${cleared.error.message}")
        }
        brokers.cookies.clear(sourceId)
        configStore.clear(current.identity.canonicalKey())
        secretStore.clear(current.identity.canonicalKey())
        return store.uninstall(current.identity)
    }

    fun activeStorySources(): List<StorySource> {
        val artifacts = activeArtifacts()
        storySourceCache.retainKeys(artifacts.mapTo(linkedSetOf()) { it.artifactId })
        return artifacts.mapNotNull { artifact ->
            storySourceCache.getOrLoad(artifact.artifactId, cacheNull = false) {
                val bytes = store.originalBytes(artifact.artifactId) ?: return@getOrLoad null
                runCatching {
                    VBookStorySource(artifact, bytes, brokers, configReader, diagnostics, evidence)
                }.getOrNull()
            }
        }
    }

    /** All active vBook provider sessions, including non-StorySource content types. */
    fun activeProviderSessions(contentType: VBookContentType? = null): List<VBookProviderSession> =
        activeArtifacts().mapNotNull { artifact ->
            val bytes = store.originalBytes(artifact.artifactId) ?: return@mapNotNull null
            runCatching {
                VBookProviderSession(
                    artifactIdentity = artifact.identity.canonicalKey(),
                    packageBytes = bytes,
                    brokers = brokers,
                    configReader = configReader,
                    diagnostics = diagnostics,
                )
            }.getOrNull()?.takeIf { contentType == null || it.contentType == contentType }
        }

    fun activeComicProviders(): List<VBookProviderSession> = activeProviderSessions(VBookContentType.COMIC)
    fun activeTtsProviders(): List<VBookProviderSession> = activeProviderSessions(VBookContentType.TTS)
    fun activeTranslationProviders(): List<VBookProviderSession> = activeProviderSessions(VBookContentType.TRANSLATE)
    fun activeMediaProviders(): List<VBookProviderSession> = activeProviderSessions().filter {
        it.contentType in setOf(VBookContentType.VIDEO, VBookContentType.AUDIO)
    }

    fun config(repositoryId: String, remoteIdentity: String): VBookConfigSnapshot {
        val identity = SourceArtifactIdentity(SourceEcosystem.VBOOK, repositoryId.trim(), remoteIdentity.trim())
        val current = store.active(identity) ?: store.disabled(identity) ?: error("VBOOK_INSTALLED_ARTIFACT_NOT_FOUND")
        val manifest = manifestFor(current)
        return configService.load(identity.canonicalKey(), manifest)
    }

    fun saveConfig(repositoryId: String, remoteIdentity: String, changes: Map<String, String>): VBookConfigSnapshot {
        val identity = SourceArtifactIdentity(SourceEcosystem.VBOOK, repositoryId.trim(), remoteIdentity.trim())
        val current = store.active(identity) ?: store.disabled(identity) ?: error("VBOOK_INSTALLED_ARTIFACT_NOT_FOUND")
        val manifest = manifestFor(current)
        return configService.save(identity.canonicalKey(), manifest, changes)
    }

    fun resetConfig(repositoryId: String, remoteIdentity: String): VBookConfigSnapshot {
        val identity = SourceArtifactIdentity(SourceEcosystem.VBOOK, repositoryId.trim(), remoteIdentity.trim())
        val current = store.active(identity) ?: store.disabled(identity) ?: error("VBOOK_INSTALLED_ARTIFACT_NOT_FOUND")
        val manifest = manifestFor(current)
        return configService.reset(identity.canonicalKey(), manifest)
    }

    fun configFieldsBySourceId(sourceId: String): List<SourceConfigFieldUi> {
        val current = installedBySourceId(sourceId)
        val manifest = manifestFor(current)
        val extensionKey = current.identity.canonicalKey()
        val snapshot = configService.load(extensionKey, manifest)
        val persistedSecrets = secretStore.read(extensionKey)
        return manifest.config.values.map { field ->
            SourceConfigFieldUi(
                key = field.key,
                title = field.title.ifBlank { field.key },
                subtitle = field.subtitle,
                value = if (field.sensitive) "" else snapshot.values[field.key].orEmpty(),
                defaultValue = field.defaultValue,
                options = field.values,
                mode = field.mode.name,
                format = field.format.name,
                sensitive = field.sensitive,
                configured = if (field.sensitive) !persistedSecrets[field.key].isNullOrEmpty() else true,
            )
        }
    }

    fun saveConfigBySourceId(sourceId: String, changes: Map<String, String>): VBookConfigSnapshot {
        val current = installedBySourceId(sourceId)
        val manifest = manifestFor(current)
        return configService.save(current.identity.canonicalKey(), manifest, changes)
    }

    fun resetConfigBySourceId(sourceId: String): VBookConfigSnapshot {
        val current = installedBySourceId(sourceId)
        val manifest = manifestFor(current)
        return configService.reset(current.identity.canonicalKey(), manifest)
    }

    /** Revalidates the exact archived ZIP without touching the active pointer or contacting a site. */
    fun validateBySourceId(sourceId: String): VBookCandidateValidation {
        val current = installedBySourceId(sourceId)
        val bytes = store.originalBytes(current.artifactId) ?: error("VBOOK_INSTALLED_ARTIFACT_BYTES_MISSING")
        val pkg = VBookPackageReader.read(bytes)
        return validator.validate(VBookCandidate(current.artifactId, pkg.pluginJson(), pkg.decodeScripts()))
    }

    fun diagnosticsSnapshot(sourceId: String? = null): List<DiagnosticEvent> = diagnostics.snapshot(sourceId)

    fun clearDiagnostics() {
        diagnostics.clear()
    }

    fun originalPackageBytes(artifactId: String): ByteArray? = store.originalBytes(artifactId)

    private fun manifestFor(current: SourceArtifactDescriptor): VBookExtensionManifest =
        manifestCache.getOrLoad(current.artifactId) {
            val bytes = store.originalBytes(current.artifactId) ?: error("VBOOK_INSTALLED_ARTIFACT_BYTES_MISSING")
            VBookManifestParser.parse(VBookPackageReader.read(bytes).pluginJson())
        } ?: error("VBOOK_INSTALLED_ARTIFACT_MANIFEST_MISSING")

    private fun installedBySourceId(sourceId: String): SourceArtifactDescriptor =
        store.installedArtifacts(SourceEcosystem.VBOOK).firstOrNull {
            VBookHostManifestFactory.stableSourceId(it.identity.canonicalKey()) == sourceId
        } ?: error("VBOOK_INSTALLED_SOURCE_NOT_FOUND:$sourceId")

    private fun loginInfo(manifest: vn.nghetruyen.source.vbook.VBookExtensionManifest, sourceId: String): VBookLoginInfo? {
        val uri = runCatching { URI(manifest.metadata.source) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.takeIf(String::isNotBlank) ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null || uri.fragment != null) return null
        return VBookLoginInfo(sourceId, uri.toASCIIString(), setOf(host))
    }

    private fun artifactId(identity: SourceArtifactIdentity, packageSha: String): String =
        "vbook-" + SourceArtifactLifecycle.sha256(
            (identity.canonicalKey() + "\n" + packageSha).toByteArray(Charsets.UTF_8),
        ).take(40)
}
