package vn.nghetruyen.app

import android.content.Context
import com.nghetruyen.source.platform.SourceArtifactDescriptor
import com.nghetruyen.source.platform.SourceTrustState
import com.nghetruyen.source.repository.VBookUpdateResult
import vn.nghetruyen.app.ai.EncryptedAiCredentialStore
import vn.nghetruyen.app.ai.AiRequestGovernor
import vn.nghetruyen.app.ai.OnlineAiServices
import vn.nghetruyen.app.ai.NarrationPlanCoordinator
import vn.nghetruyen.app.ai.vietphrase.VietPhraseOnlineUpdater
import vn.nghetruyen.app.audio.AudioExportScheduler
import vn.nghetruyen.app.data.local.AppDatabase
import vn.nghetruyen.app.data.repository.LibraryRepository
import vn.nghetruyen.app.data.settings.SettingsRepository
import vn.nghetruyen.app.downloads.DownloadScheduler
import vn.nghetruyen.app.importers.BookImporter
import vn.nghetruyen.app.following.FollowingUpdateScheduler
import vn.nghetruyen.app.playback.TtsVoiceCatalog
import vn.nghetruyen.app.sources.EncryptedSourceSessionStore
import vn.nghetruyen.app.sourceplatform.AndroidVBookQuickTranslationRegistry
import vn.nghetruyen.app.sourceplatform.SourcePlatformManager
import vn.nghetruyen.app.sourceplatform.UnifiedSourcePlatformManager
import vn.nghetruyen.app.sourceplatform.VBookRepositoryClient
import vn.nghetruyen.app.sourceplatform.VBookSourcePlatform
import vn.nghetruyen.app.sources.SourceHealthChecker
import vn.nghetruyen.app.sources.SourceRegistry
import vn.nghetruyen.app.transfer.BackupTransferManager
import vn.nghetruyen.app.transfer.BackupHistoryStore
import vn.nghetruyen.app.transfer.LegacyXpkBackupImporter
import vn.nghetruyen.app.transfer.VietPhraseTransferManager

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: AppDatabase by lazy { AppDatabase.create(appContext) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }
    val libraryRepository: LibraryRepository by lazy { LibraryRepository(database) }
    val sourceSessionStore: EncryptedSourceSessionStore by lazy { EncryptedSourceSessionStore(appContext) }

    private val vBookQuickTranslationInstalled: Unit by lazy {
        AndroidVBookQuickTranslationRegistry.install(libraryRepository)
    }

    val vBookSourcePlatform: VBookSourcePlatform by lazy {
        vBookQuickTranslationInstalled
        VBookSourcePlatform(appContext, sourceSessionStore, aiServices)
    }

    val vBookRepositoryClient: VBookRepositoryClient by lazy { VBookRepositoryClient() }

    private val legacySourcePlatformManager: SourcePlatformManager by lazy {
        vBookQuickTranslationInstalled
        SourcePlatformManager(
            context = appContext,
            sourceSessionStore = sourceSessionStore,
            translationEngine = aiServices,
            vBookSourcePlatform = vBookSourcePlatform,
            onVBookChanged = { refreshSourceRegistry() },
        )
    }

    val sourcePlatformManager: UnifiedSourcePlatformManager by lazy {
        UnifiedSourcePlatformManager(
            legacy = legacySourcePlatformManager,
            vBook = vBookSourcePlatform,
            vBookRepositories = vBookRepositoryClient,
            onExternalSourcesChanged = { refreshSourceRegistry() },
        )
    }

    val sourceRegistry: SourceRegistry by lazy {
        SourceRegistry(
            sessionStore = sourceSessionStore,
            sourcePackSources = currentExternalStorySources(),
        )
    }

    /** One authoritative refresh point after native or vBook install/update/rollback. */
    fun refreshSourceRegistry() {
        sourceRegistry.replaceExternalSources(currentExternalStorySources())
    }

    fun installOrUpdateVBook(
        repositoryId: String,
        remoteIdentity: String,
        version: String?,
        packageBytes: ByteArray,
        trust: SourceTrustState = SourceTrustState.REPOSITORY_TRUSTED,
    ): VBookUpdateResult {
        val result = vBookSourcePlatform.installOrUpdate(
            repositoryId = repositoryId,
            remoteIdentity = remoteIdentity,
            version = version,
            packageBytes = packageBytes,
            trust = trust,
        )
        refreshSourceRegistry()
        return result
    }

    fun rollbackVBook(repositoryId: String, remoteIdentity: String): SourceArtifactDescriptor {
        val restored = vBookSourcePlatform.rollback(repositoryId, remoteIdentity)
        refreshSourceRegistry()
        return restored
    }

    private fun currentExternalStorySources() = sourcePlatformManager.activeStorySources()

    val sourceHealthChecker: SourceHealthChecker by lazy { SourceHealthChecker(sourceRegistry) }
    val bookImporter: BookImporter by lazy { BookImporter(appContext.contentResolver) }
    val downloadScheduler: DownloadScheduler by lazy { DownloadScheduler(appContext) }
    val followingUpdateScheduler: FollowingUpdateScheduler by lazy { FollowingUpdateScheduler(appContext) }
    val ttsVoiceCatalog: TtsVoiceCatalog by lazy { TtsVoiceCatalog(appContext) }
    val aiCredentialStore: EncryptedAiCredentialStore by lazy { EncryptedAiCredentialStore(appContext) }
    val aiRequestGovernor: AiRequestGovernor by lazy { AiRequestGovernor(database, settingsRepository) }
    val aiServices: OnlineAiServices by lazy { OnlineAiServices(settingsRepository, aiCredentialStore, aiRequestGovernor, libraryRepository) }
    val narrationPlanCoordinator: NarrationPlanCoordinator by lazy {
        NarrationPlanCoordinator(libraryRepository, settingsRepository, aiServices, ttsVoiceCatalog)
    }
    val audioExportScheduler: AudioExportScheduler by lazy { AudioExportScheduler(appContext) }
    val vietPhraseTransferManager: VietPhraseTransferManager by lazy {
        VietPhraseTransferManager(appContext.contentResolver, libraryRepository)
    }
    val vietPhraseOnlineUpdater: VietPhraseOnlineUpdater by lazy {
        VietPhraseOnlineUpdater(libraryRepository)
    }
    val backupTransferManager: BackupTransferManager by lazy {
        BackupTransferManager(appContext, database, settingsRepository)
    }
    val legacyXpkBackupImporter: LegacyXpkBackupImporter by lazy {
        LegacyXpkBackupImporter(appContext, database, settingsRepository)
    }
    val backupHistoryStore: BackupHistoryStore by lazy { BackupHistoryStore(appContext) }
}
