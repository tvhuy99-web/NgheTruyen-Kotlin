package vn.nghetruyen.app.sourceplatform

import android.content.Context
import com.nghetruyen.source.platform.SourceArtifactDescriptor
import com.nghetruyen.source.platform.SourceArtifactIdentity
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
import vn.nghetruyen.source.vbook.VBookContentType
import vn.nghetruyen.source.vbook.VBookManifestParser
import vn.nghetruyen.source.vbook.VBookPackageReader
import vn.nghetruyen.source.vbook.VBookValidationFactory
import java.io.File

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
    private val coordinator = VBookUpdateCoordinator(
        validator = VBookValidationFactory.production(),
        registry = store,
        archive = store,
    )

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
        val artifactId = "vbook-" + SourceArtifactLifecycle.sha256(
            (identity.canonicalKey() + "\n" + packageSha).toByteArray(Charsets.UTF_8),
        ).take(40)
        val now = clockMs()
        return coordinator.installOrUpdate(
            VBookUpdatePayload(
                artifactId = artifactId,
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
            VBookStorySource(artifact, bytes, brokers)
        }.getOrNull()
    }

    fun originalPackageBytes(artifactId: String): ByteArray? = store.originalBytes(artifactId)
}
