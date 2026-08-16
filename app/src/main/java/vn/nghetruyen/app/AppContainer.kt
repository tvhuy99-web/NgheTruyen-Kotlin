package vn.nghetruyen.app

import android.content.Context
import vn.nghetruyen.app.ai.EncryptedAiCredentialStore
import vn.nghetruyen.app.ai.AiRequestGovernor
import vn.nghetruyen.app.ai.OnlineTextAiServices
import vn.nghetruyen.app.ai.NarrationPlanCoordinator
import vn.nghetruyen.app.ai.XpkNarrationAiServices
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
import vn.nghetruyen.app.sourceplatform.SourceDiagnosticRuntime
import vn.nghetruyen.app.sourceplatform.UnifiedSourcePlatformManager
import vn.nghetruyen.app.sourceplatform.VBookRepositoryCacheStore
import vn.nghetruyen.app.sourceplatform.VBookRepositoryClient
import vn.nghetruyen.app.sourceplatform.VBookRepositorySubscriptionStore
import vn.nghetruyen.app.sourceplatform.VBookSourcePlatform
import vn.nghetruyen.app.sources.SourceHealthChecker
import vn.nghetruyen.app.sources.SourceRegistry
import vn.nghetruyen.app.sources.withStableDefaultLuaId
import vn.nghetruyen.app.transfer.BackupTransferManager
import vn.nghetruyen.app.transfer.BackupHistoryStore
import vn.nghetruyen.app.transfer.LegacyXpkBackupImporter
import vn.nghetruyen.app.transfer.LegacyXpkCompleteRestoreCoordinator
import vn.nghetruyen.app.transfer.LegacyXpkDeepRepairCoordinator
import vn.nghetruyen.app.transfer.LegacyXpkEverythingRestoreCoordinator
import vn.nghetruyen.app.transfer.LegacyXpkVerifiedRestoreCoordinator
import vn.nghetruyen.app.transfer.VietPhraseTransferManager
import vn.nghetruyen.source.diagnostics.ScreenScopedDiagnosticEvidenceSink

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: AppDatabase by lazy { AppDatabase.create(appContext) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }
    val libraryRepository: LibraryRepository by lazy { LibraryRepository(database) }
    val sourceSessionStore: EncryptedSourceSessionStore by lazy { EncryptedSourceSessionStore(appContext) }
    val sourceDiagnostics: SourceDiagnosticRuntime = SourceDiagnosticRuntime(appContext)
    private val screenScopedSourceEvidence = ScreenScopedDiagnosticEvidenceSink(
        scope = sourceDiagnostics.recorder,
        delegate = sourceDiagnostics.evidence,
    )

    private val vBookQuickTranslationInstalled: Unit by lazy {
        AndroidVBookQuickTranslationRegistry.install(libraryRepository)
    }

    val vBookSourcePlatform: VBookSourcePlatform by lazy {
        vBookQuickTranslationInstalled
        VBookSourcePlatform(
            appContext,
            sourceSessionStore,
            aiServices,
            diagnostics = sourceDiagnostics.recorder,
            evidence = screenScopedSourceEvidence,
        )
    }

    val vBookRepositoryCacheStore: VBookRepositoryCacheStore by lazy {
        VBookRepositoryCacheStore(appContext)
    }

    val vBookRepositoryClient: VBookRepositoryClient by lazy {
        VBookRepositoryClient(
            cache = vBookRepositoryCacheStore,
            diagnostics = sourceDiagnostics.recorder,
            evidence = screenScopedSourceEvidence,
        )
    }

    val vBookRepositorySubscriptionStore: VBookRepositorySubscriptionStore by lazy {
        VBookRepositorySubscriptionStore(appContext)
    }

    private val legacySourcePlatformManager: SourcePlatformManager by lazy {
        vBookQuickTranslationInstalled
        SourcePlatformManager(
            context = appContext,
            sourceSessionStore = sourceSessionStore,
            translationEngine = aiServices,
            vBookSourcePlatform = vBookSourcePlatform,
            onVBookChanged = { refreshSourceRegistry() },
            diagnostics = sourceDiagnostics.recorder,
            evidence = screenScopedSourceEvidence,
        )
    }

    val sourcePlatformManager: UnifiedSourcePlatformManager by lazy {
        UnifiedSourcePlatformManager(
            legacyProvider = { legacySourcePlatformManager },
            vBookProvider = { vBookSourcePlatform },
            vBookRepositoriesProvider = { vBookRepositoryClient },
            vBookRepositorySubscriptionsProvider = { vBookRepositorySubscriptionStore },
            onExternalSourcesChanged = { refreshSourceRegistry() },
        )
    }

    val sourceRegistry: SourceRegistry by lazy {
        SourceRegistry(
            sessionStore = sourceSessionStore,
            sourcePackSources = currentExternalStorySources(),
            diagnostics = sourceDiagnostics,
        )
    }

    /** One authoritative refresh point after native or vBook install/update/rollback. */
    fun refreshSourceRegistry() {
        sourceRegistry.replaceExternalSources(currentExternalStorySources())
    }

    private fun currentExternalStorySources() = sourcePlatformManager.activeStorySources()
        .map { source -> source.withStableDefaultLuaId() }

    val sourceHealthChecker: SourceHealthChecker by lazy { SourceHealthChecker(sourceRegistry) }
    val bookImporter: BookImporter by lazy { BookImporter(appContext.contentResolver) }
    val downloadScheduler: DownloadScheduler by lazy { DownloadScheduler(appContext) }
    val followingUpdateScheduler: FollowingUpdateScheduler by lazy { FollowingUpdateScheduler(appContext) }
    val ttsVoiceCatalog: TtsVoiceCatalog by lazy { TtsVoiceCatalog(appContext) }
    val aiCredentialStore: EncryptedAiCredentialStore by lazy { EncryptedAiCredentialStore(appContext) }
    val aiRequestGovernor: AiRequestGovernor by lazy { AiRequestGovernor(database, settingsRepository) }
    val aiServices: OnlineTextAiServices by lazy {
        OnlineTextAiServices(settingsRepository, aiCredentialStore, aiRequestGovernor, libraryRepository, sourceDiagnostics)
    }
    val xpkNarrationAiServices: XpkNarrationAiServices by lazy {
        XpkNarrationAiServices(appContext, settingsRepository, aiCredentialStore, aiRequestGovernor, libraryRepository)
    }
    val narrationPlanCoordinator: NarrationPlanCoordinator by lazy {
        NarrationPlanCoordinator(libraryRepository, settingsRepository, xpkNarrationAiServices)
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
    val legacyXpkBackupImporter: LegacyXpkDeepRepairCoordinator by lazy {
        val complete = LegacyXpkCompleteRestoreCoordinator(
            context = appContext,
            legacyImporter = LegacyXpkBackupImporter(appContext, database, settingsRepository),
            database = database,
            settingsRepository = settingsRepository,
            sourcePlatformManager = sourcePlatformManager,
            onSourcesChanged = ::refreshSourceRegistry,
        )
        val everything = LegacyXpkEverythingRestoreCoordinator(
            context = appContext,
            completeCoordinator = complete,
            database = database,
        )
        val verified = LegacyXpkVerifiedRestoreCoordinator(
            context = appContext,
            delegate = everything,
            database = database,
            sourceRegistry = sourceRegistry,
            sourcePlatformManager = sourcePlatformManager,
        )
        LegacyXpkDeepRepairCoordinator(
            context = appContext,
            delegate = verified,
            database = database,
            settingsRepository = settingsRepository,
            sourcePlatformManager = sourcePlatformManager,
            onSourcesChanged = ::refreshSourceRegistry,
        )
    }
    val backupHistoryStore: BackupHistoryStore by lazy { BackupHistoryStore(appContext) }
}
