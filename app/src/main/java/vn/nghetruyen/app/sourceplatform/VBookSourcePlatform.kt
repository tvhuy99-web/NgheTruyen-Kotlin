package vn.nghetruyen.app.sourceplatform

import android.content.Context
import com.nghetruyen.source.platform.SourceArtifactDescriptor
import com.nghetruyen.source.platform.SourceArtifactIdentity
import com.nghetruyen.source.platform.SourceCompatibilityState
import com.nghetruyen.source.platform.SourceEcosystem
import com.nghetruyen.source.platform.SourceTrustState
import com.nghetruyen.source.repository.VBookUpdateCoordinator
import com.nghetruyen.source.repository.VBookUpdatePayload
import com.nghetruyen.source.repository.VBookUpdateResult
import com.nghetruyen.source.store.FileSourceArtifactStore
import com.nghetruyen.source.store.SourceArtifactLifecycle
import vn.nghetruyen.app.ai.TranslationEngine
import vn.nghetruyen.app.sources.EncryptedSourceSessionStore
import vn.nghetruyen.app.sources.StorySource
import vn.nghetruyen.source.api.SourceCapabilityBrokers
import vn.nghetruyen.source.diagnostics.PersistentDiagnosticStore
import vn.nghetruyen.source.network.OkHttpSourceNetworkBroker
import vn.nghetruyen.source.network.OkHttpSourceWebSocketBroker
import vn.nghetruyen.source.network.PersistentSourceCookiePartition
import vn.nghetruyen.source.vbook.VBookCandidate
import vn.nghetruyen.source.vbook.VBookCandidateValidation
import vn.nghetruyen.source.vbook.VBookConfigService
import vn.nghetruyen.source.vbook.VBookConfigSnapshot
import vn.nghetruyen.source.vbook.VBookContentType
import vn.nghetruyen.source.vbook.VBookFeature
import vn.nghetruyen.source.vbook.VBookManifestParser
import vn.nghetruyen.source.vbook.VBookPackageReader
import vn.nghetruyen.source.vbook.VBookValidationFactory
import java.io.File

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

/**
 * Persistent vBook subsystem. It deliberately does not install vBook packages into SourcePackStore.
 * Original package bytes and repository identity remain authoritative across updates and rollback.
 */
class VBookSourcePlatform(
    context: Context,
    sourceSessionStore: EncryptedSourceSessionStore,
    translationEngine: TranslationEngine,
    root: File = File(context.filesDir, "source-platform/vbook"),
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext
    private val diagnostics = PersistentDiagnosticStore(appContext)
    private val cookiePartition = PersistentSourceCookiePartition(appContext)
    private val translationBroker = AndroidSourceTranslationBroker(translationEngine)
    private val configStore = AndroidVBookConfigStore(appContext)
    private val configService = VBookConfigService(configStore)
    private val brokers = SourceCapabilityBrokers(
        network = OkHttpSourceNetworkBroker(cookiePartition, diagnostics),
        browser = AndroidSourceBrowserBroker(appContext, cookiePartition, diagnostics),
        storage = EncryptedSourceStorageBroker(sourceSessionStore),
        crypto = AndroidSourceCryptoBroker(appContext),
        websocket = OkHttpSourceWebSocketBroker(cookiePartition, diagnostics),
        nativeHooks = AndroidSourceNativeHookBroker(),
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

    fun activeStorySources(): List<StorySource> = activeArtifacts().mapNotNull { artifact ->
        val bytes = store.originalBytes(artifact.artifactId) ?: return@mapNotNull null
        runCatching {
            val pkg = VBookPackageReader.read(bytes)
            val plugin = VBookManifestParser.parse(pkg.pluginJson())
            if (plugin.metadata.type !in setOf(VBookContentType.NOVEL, VBookContentType.CHINESE_NOVEL)) {
                return@runCatching null
            }
            VBookStorySource(artifact, bytes, brokers, configStore)
        }.getOrNull()
    }

    fun config(repositoryId: String, remoteIdentity: String): VBookConfigSnapshot {
        val identity = SourceArtifactIdentity(SourceEcosystem.VBOOK, repositoryId.trim(), remoteIdentity.trim())
        val active = store.active(identity) ?: error("VBOOK_ACTIVE_ARTIFACT_NOT_FOUND")
        val bytes = store.originalBytes(active.artifactId) ?: error("VBOOK_ACTIVE_ARTIFACT_BYTES_MISSING")
        val manifest = VBookManifestParser.parse(VBookPackageReader.read(bytes).pluginJson())
        return configService.load(identity.canonicalKey(), manifest)
    }

    fun saveConfig(repositoryId: String, remoteIdentity: String, changes: Map<String, String>): VBookConfigSnapshot {
        val identity = SourceArtifactIdentity(SourceEcosystem.VBOOK, repositoryId.trim(), remoteIdentity.trim())
        val active = store.active(identity) ?: error("VBOOK_ACTIVE_ARTIFACT_NOT_FOUND")
        val bytes = store.originalBytes(active.artifactId) ?: error("VBOOK_ACTIVE_ARTIFACT_BYTES_MISSING")
        val manifest = VBookManifestParser.parse(VBookPackageReader.read(bytes).pluginJson())
        return configService.save(identity.canonicalKey(), manifest, changes)
    }

    fun resetConfig(repositoryId: String, remoteIdentity: String): VBookConfigSnapshot {
        val identity = SourceArtifactIdentity(SourceEcosystem.VBOOK, repositoryId.trim(), remoteIdentity.trim())
        val active = store.active(identity) ?: error("VBOOK_ACTIVE_ARTIFACT_NOT_FOUND")
        val bytes = store.originalBytes(active.artifactId) ?: error("VBOOK_ACTIVE_ARTIFACT_BYTES_MISSING")
        val manifest = VBookManifestParser.parse(VBookPackageReader.read(bytes).pluginJson())
        return configService.reset(identity.canonicalKey(), manifest)
    }

    fun originalPackageBytes(artifactId: String): ByteArray? = store.originalBytes(artifactId)

    private fun artifactId(identity: SourceArtifactIdentity, packageSha: String): String =
        "vbook-" + SourceArtifactLifecycle.sha256(
            (identity.canonicalKey() + "\n" + packageSha).toByteArray(Charsets.UTF_8),
        ).take(40)
}
