package vn.nghetruyen.app.ui

import android.app.ActivityManager
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.audio.AudioExportPackaging
import vn.nghetruyen.app.audio.AudioExportRequest
import vn.nghetruyen.app.audio.AudioExportScope
import vn.nghetruyen.app.audio.SceneMusicAnalysisWorker
import vn.nghetruyen.app.ai.ChapterAiWorkflow
import vn.nghetruyen.app.ai.TranslationRequest
import vn.nghetruyen.app.ai.VietPhraseImprovementRequest
import vn.nghetruyen.app.ai.vietphrase.VietPhraseEngine
import vn.nghetruyen.app.ai.vietphrase.ReferenceVietPhraseRuntime
import vn.nghetruyen.app.ai.vietphrase.VietPhraseDictionaryKind
import vn.nghetruyen.app.ai.vietphrase.VietPhraseScope
import vn.nghetruyen.app.ai.vietphrase.VietPhraseOptions
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.core.model.AudioExportFormat
import vn.nghetruyen.app.core.model.AudioInterruptionMode
import vn.nghetruyen.app.core.model.SceneMusicPlaybackMode
import vn.nghetruyen.app.core.model.ChapterContent
import vn.nghetruyen.app.core.model.ChapterSummary
import vn.nghetruyen.app.core.model.DownloadSelectionMode
import vn.nghetruyen.app.core.model.DownloadState
import vn.nghetruyen.app.core.model.ReaderDisplaySettings
import vn.nghetruyen.app.core.model.ReaderLayoutMode
import vn.nghetruyen.app.core.model.ReaderMode
import vn.nghetruyen.app.core.model.ReaderThemeMode
import vn.nghetruyen.app.core.model.SearchSortMode
import vn.nghetruyen.app.core.model.StoryComment
import vn.nghetruyen.app.core.model.StoryDetail
import vn.nghetruyen.app.core.model.StorySummary
import vn.nghetruyen.app.core.model.TtsEngineOption
import vn.nghetruyen.app.core.model.TtsVoiceOption
import vn.nghetruyen.app.core.model.VoiceRoleDraft
import vn.nghetruyen.app.core.model.GLOBAL_VOICE_PROFILE_STORY_ID
import vn.nghetruyen.app.data.local.AudioExportJobEntity
import vn.nghetruyen.app.data.local.AiUsageDailyEntity
import vn.nghetruyen.app.data.local.ChapterTransformEntity
import vn.nghetruyen.app.data.local.ChapterNoteEntity
import vn.nghetruyen.app.data.local.ChapterDownloadFailureEntity
import vn.nghetruyen.app.data.local.ChapterVoiceAssignmentEntity
import vn.nghetruyen.app.data.local.SceneMusicCueEntity
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity
import vn.nghetruyen.app.data.local.VietPhraseEntity
import vn.nghetruyen.app.data.local.VietPhraseSnapshotEntity
import vn.nghetruyen.app.data.local.VietPhraseDictionaryStateEntity
import vn.nghetruyen.app.data.local.VietPhraseSuggestionEntity
import vn.nghetruyen.app.data.local.BookmarkEntity
import vn.nghetruyen.app.data.local.DownloadJobEntity
import vn.nghetruyen.app.data.local.FollowedStoryEntity
import vn.nghetruyen.app.data.local.StoryEntity
import vn.nghetruyen.app.data.local.OfflineStoryStorage
import vn.nghetruyen.app.data.local.PronunciationEntity
import vn.nghetruyen.app.data.local.ReadingProgressEntity
import vn.nghetruyen.app.data.local.ReadingHistoryEntity
import vn.nghetruyen.app.data.local.StorageUsage
import vn.nghetruyen.app.data.local.StoryTtsProfileEntity
import vn.nghetruyen.app.data.local.StoryAiProfileEntity
import vn.nghetruyen.app.data.local.VoiceRoleEntity
import vn.nghetruyen.app.data.settings.AiOnlineSettings
import vn.nghetruyen.app.data.settings.AiProvider
import vn.nghetruyen.app.playback.PlaybackQueueStore
import vn.nghetruyen.app.playback.PlaybackPreparationState
import vn.nghetruyen.app.playback.PlaybackSnapshot
import vn.nghetruyen.app.playback.ReaderPlaybackService
import vn.nghetruyen.app.playback.ReaderDocumentNormalizer
import vn.nghetruyen.app.ui.reference.ReferenceVoiceRolePersistence
import vn.nghetruyen.app.ui.reference.ReferenceVoiceRoleExtra
import vn.nghetruyen.app.ui.reference.ReferenceVoiceRoleExtras
import vn.nghetruyen.app.playback.ReaderPositionResolver
import vn.nghetruyen.app.playback.ReaderChapterNavigation
import vn.nghetruyen.app.sources.SourceCheckReport
import vn.nghetruyen.app.sources.SourceDescriptor
import vn.nghetruyen.app.sources.SourceDiagnosticBrowserActivity
import vn.nghetruyen.app.sources.SourceLoginActivity
import vn.nghetruyen.app.sources.StorySearch
import vn.nghetruyen.app.sources.SourceUiSurface
import vn.nghetruyen.app.sourceplatform.SourceInstallPreview
import vn.nghetruyen.app.sourceplatform.SourceDiagnosticUi
import vn.nghetruyen.app.sourceplatform.StoryCommentCache
import vn.nghetruyen.app.sourceplatform.SourcePackUiInfo
import vn.nghetruyen.app.sourceplatform.SourceRepositoryPackageUiInfo
import vn.nghetruyen.app.sourceplatform.SourceRepositoryUiInfo
import vn.nghetruyen.app.sourceplatform.SourceSelectorInspectionUi
import vn.nghetruyen.app.sourceplatform.SourceTraceUi
import vn.nghetruyen.app.sourceplatform.SourceTrustKeyUi
import vn.nghetruyen.app.downloads.DownloadRequest
import vn.nghetruyen.app.downloads.DownloadStorageGuard
import vn.nghetruyen.app.downloads.StoryDownloadPlanner
import vn.nghetruyen.app.transfer.BackupComponent
import vn.nghetruyen.app.transfer.BackupHistoryEntry
import vn.nghetruyen.app.transfer.VietPhraseTransferManager
import vn.nghetruyen.app.diagnostics.PerformanceDiagnostics
import java.text.Normalizer
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

enum class RootTab { EXPLORE, LIBRARY, PERSONAL }
enum class LibrarySection { READING, DOWNLOADED, BOOKMARKS, NOTES, FOLLOWING }
enum class ChapterTextMode { ORIGINAL, VIETPHRASE, AI_TRANSLATION }
enum class ExploreMode { HOME, SEARCH, CATEGORY }

sealed interface Destination {
    data object Root : Destination
    data object Story : Destination
    data object Reader : Destination
}

data class MainUiState(
    val destination: Destination = Destination.Root,
    val rootTab: RootTab = RootTab.EXPLORE,
    val librarySection: LibrarySection = LibrarySection.READING,
    val sources: List<SourceDescriptor> = emptyList(),
    val selectedSourceId: String = "truyenfull",
    val categories: List<String> = emptyList(),
    val query: String = "",
    val sourceSuggestions: List<String> = emptyList(),
    val stories: List<StorySummary> = emptyList(),
    val explorePage: Int = 1,
    val exploreMode: ExploreMode = ExploreMode.HOME,
    val activeCategory: String? = null,
    val canLoadMoreStories: Boolean = false,
    val searchAllSources: Boolean = false,
    val searchSortMode: SearchSortMode = SearchSortMode.RELEVANCE,
    val searchedSourceCount: Int = 0,
    val totalSearchSourceCount: Int = 0,
    val storyDetail: StoryDetail? = null,
    val storyDetailTab: String = "intro",
    val storyAdvancedOptionsRequested: Boolean = false,
    val storyAdvancedOptionsMode: String? = null,
    val storyComments: List<StoryComment> = emptyList(),
    val storyCommentsAvailable: Boolean = false,
    val storyCommentsRefreshable: Boolean = false,
    val storyCommentsLoading: Boolean = false,
    val storyCommentsLoaded: Boolean = false,
    val storyCommentsNextPageUrl: String? = null,
    val storyCommentsFromCache: Boolean = false,
    val storyCommentsMessage: String? = null,
    val chapterContent: ChapterContent? = null,
    val originalChapterContent: ChapterContent? = null,
    val chapterTextMode: ChapterTextMode = ChapterTextMode.ORIGINAL,
    val aiBusy: Boolean = false,
    val loading: Boolean = false,
    val message: String? = null,
    val readingStories: List<StoryEntity> = emptyList(),
    val readingProgress: Map<String, ReadingProgressEntity> = emptyMap(),
    val readingHistory: List<ReadingHistoryEntity> = emptyList(),
    val downloadedStories: List<StoryEntity> = emptyList(),
    val bookmarks: List<BookmarkEntity> = emptyList(),
    val notes: List<ChapterNoteEntity> = emptyList(),
    val following: List<FollowedStoryEntity> = emptyList(),
    val downloads: List<DownloadJobEntity> = emptyList(),
    val downloadFailures: List<ChapterDownloadFailureEntity> = emptyList(),
    val playback: PlaybackSnapshot = PlaybackSnapshot(),
    val autoPlayNextChapter: Boolean = true,
    val continueAvailable: Boolean = false,
    val offlineStorage: Map<String, OfflineStoryStorage> = emptyMap(),
    val downloadedChapterIds: Set<String> = emptySet(),
    val storageUsage: StorageUsage = StorageUsage(0, 0, 0, 0),
    val ttsEngines: List<TtsEngineOption> = emptyList(),
    val ttsVoices: List<TtsVoiceOption> = emptyList(),
    val selectedTtsEnginePackage: String? = null,
    val selectedTtsVoiceName: String? = null,
    val selectedTtsLanguageTag: String = "vi-VN",
    val ttsVoiceLoading: Boolean = false,
    val roleEditorVoiceLoading: Boolean = false,
    val roleEditorEnginePackage: String? = null,
    val roleEditorVoices: List<TtsVoiceOption> = emptyList(),
    val ttsVolume: Float = 1.0f,
    val audioInterruptionMode: AudioInterruptionMode = AudioInterruptionMode.PAUSE,
    val backgroundMusicUri: String? = null,
    val backgroundMusicEnabled: Boolean = false,
    val backgroundMusicVolume: Float = 0.18f,
    val backgroundMusicDuckFactor: Float = 0.63095734f,
    val headsetMultiClickEnabled: Boolean = true,
    val headsetSingleClickAction: String = "TOGGLE",
    val headsetDoubleClickAction: String = "NEXT",
    val headsetTripleClickAction: String = "PREVIOUS",
    val headsetLongPressAction: String = "STOP",
    val pauseOnHeadsetDisconnect: Boolean = true,
    val restorePlaybackAfterProcessDeath: Boolean = true,
    val autoVoiceCastEnabled: Boolean = false,
    val autoSceneMusicEnabled: Boolean = false,
    val prefetchNarrationPlansEnabled: Boolean = true,
    val narrationPrefetchWindowChapters: Int = 2,
    val sceneMusicCrossfadeMillis: Int = 1_600,
    val sceneMusicContinueAcrossChapters: Boolean = true,
    val sceneMusicPlaybackMode: SceneMusicPlaybackMode = SceneMusicPlaybackMode.SEQUENTIAL,
    val sceneMusicTargetLufs: Float = -24f,
    val sceneMusicAvoidRepeatWindow: Int = 4,
    val sonicProcessingEnabled: Boolean = true,
    val sonicDefaultSpeed: Float = 1f,
    val sonicDefaultPitch: Float = 1f,
    val ttsCacheEnabled: Boolean = true,
    val ttsCacheLimitMiB: Int = 64,
    val normalizeTtsVolumeEnabled: Boolean = true,
    val ttsTargetLufs: Float = -18f,
    val followingUpdatesEnabled: Boolean = false,
    val pronunciations: List<PronunciationEntity> = emptyList(),
    val vietPhraseRules: List<VietPhraseEntity> = emptyList(),
    val vietPhraseSnapshots: List<VietPhraseSnapshotEntity> = emptyList(),
    val vietPhraseDictionaryStates: List<VietPhraseDictionaryStateEntity> = emptyList(),
    val vietPhraseSuggestions: List<VietPhraseSuggestionEntity> = emptyList(),
    val pendingVietPhraseImport: VietPhraseTransferManager.ImportPreview? = null,
    val vietPhraseOnlineBusy: Boolean = false,
    val vietPhraseOnlineStatus: String = "",
    val vietPhraseEnabled: Boolean = false,
    val vietPhraseFallbackHanViet: Boolean = true,
    val backupComponents: Set<BackupComponent> = BackupComponent.entries.toSet(),
    val backupHistory: List<BackupHistoryEntry> = emptyList(),
    val backupLogPath: String = "",
    val backupLogText: String = "",
    val sceneMusicTracks: List<SceneMusicTrackEntity> = emptyList(),
    val aiOnline: AiOnlineSettings = AiOnlineSettings(),
    val aiHasApiKey: Boolean = false,
    val aiHasGeminiApiKey: Boolean = false,
    val aiHasOpenAiApiKey: Boolean = false,
    val aiAvailableModels: List<String> = emptyList(),
    val aiModelDiscoveryBusy: Boolean = false,
    val aiModelDiscoveryStatus: String = "",
    val readerCacheLimitMiB: Int = 64,
    val readerMode: ReaderMode = ReaderMode.TEXT,
    val chapterSortDescending: Boolean = false,
    val diagnosticsMode: String = "off",
    val sleepTimerStatus: String = "Đang tắt",
    val readerDisplay: ReaderDisplaySettings = ReaderDisplaySettings(),
    val storyTtsProfiles: Map<String, StoryTtsProfileEntity> = emptyMap(),
    val storyAiProfiles: Map<String, StoryAiProfileEntity> = emptyMap(),
    val voiceRoles: List<VoiceRoleEntity> = emptyList(),
    val audioExports: List<AudioExportJobEntity> = emptyList(),
    val sourceHealthReports: Map<String, SourceCheckReport> = emptyMap(),
    val sourceHealthChecking: Set<String> = emptySet(),
    val sourceSessions: Set<String> = emptySet(),
    val sourcePacks: List<SourcePackUiInfo> = emptyList(),
    val sourceRepositories: List<SourceRepositoryUiInfo> = emptyList(),
    val sourceRepositoryPackages: List<SourceRepositoryPackageUiInfo> = emptyList(),
    val sourceRepositoryRefreshing: Boolean = false,
    val pendingSourceInstall: SourceInstallPreview? = null,
    val pendingSourceInstallWarnings: List<String> = emptyList(),
    val sourceTrustKeys: List<SourceTrustKeyUi> = emptyList(),
    val sourceDiagnosticCount: Int = 0,
    val sourceDiagnostics: List<SourceDiagnosticUi> = emptyList(),
    val sourceTraces: List<SourceTraceUi> = emptyList(),
    val sourceSelectorInspection: SourceSelectorInspectionUi? = null,
    val performanceReport: String? = null,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as NgheTruyenApplication
    private val container = app.container
    private val referenceVietPhraseRuntime = ReferenceVietPhraseRuntime.also { it.load(application) }
    private val mutableState = MutableStateFlow(
        MainUiState(
            sources = container.sourceRegistry.descriptors(),
            sourcePacks = container.sourcePlatformManager.installedPacks(),
            sourceRepositories = container.sourcePlatformManager.repositories(),
            sourceRepositoryPackages = container.sourcePlatformManager.repositoryPackages(),
            sourceTrustKeys = container.sourcePlatformManager.trustKeys(),
            sourceDiagnosticCount = container.sourcePlatformManager.diagnosticsSnapshot().size,
            sourceDiagnostics = container.sourcePlatformManager.diagnosticSummaries(200),
            sourceTraces = container.sourcePlatformManager.diagnosticTraces(100),
            diagnosticsMode = container.sourceDiagnostics.mode,
            backupHistory = container.backupHistoryStore.entries(),
            backupLogPath = container.backupHistoryStore.logPath(),
            backupLogText = container.backupHistoryStore.logText(),
            vietPhraseEnabled = referenceVietPhraseRuntime.enabled,
            vietPhraseFallbackHanViet = referenceVietPhraseRuntime.fallbackHanViet,
        ),
    )
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()
    private var scheduledFollowingUpdates: Boolean? = null
    private var appliedCacheLimitMiB: Int? = null
    private var searchJob: Job? = null
    private var suggestionJob: Job? = null
    private var storyLoadJob: Job? = null
    private var chapterLoadJob: Job? = null
    private var aiTranslationJob: Job? = null
    private var commentsLoadJob: Job? = null
    private val storyCommentCache = StoryCommentCache()
    private var sourceCheckAllJob: Job? = null
    private var readingPersistenceJob: Job? = null
    private var lastPersistedReadingKey: String = ""
    private var pendingReadingPersistenceKey: String = ""
    private var chapterSleepRemaining: Int? = null
    private var chapterSleepLastChapterId: String = ""

    init {
        observeSettings()
        observeDiagnostics()
        observeLibrary()
        observePlayback()
        refreshTtsVoices()
        refreshSourceSessions()
        refreshSourcePlatformState()
        refreshAiCredentialState()
        viewModelScope.launch { container.libraryRepository.ensureGlobalVoiceProfiles() }
        search("")
    }

    private fun observeSettings() {
        viewModelScope.launch {
            container.settingsRepository.settings.collect { settings ->
                val descriptor = container.sourceRegistry.get(settings.selectedSourceId)?.descriptor
                val resolvedSourceId = descriptor?.id ?: "truyenfull"
                val sourceChanged = mutableState.value.selectedSourceId != resolvedSourceId
                val activeProfile = mutableState.value.storyTtsProfiles[PlaybackQueueStore.state.value.storyId]
                PlaybackQueueStore.updateVoice(
                    activeProfile?.rate ?: settings.ttsRate,
                    activeProfile?.pitch ?: settings.ttsPitch,
                    activeProfile?.volume ?: settings.ttsVolume,
                )
                mutableState.update {
                    it.copy(
                        selectedSourceId = resolvedSourceId,
                        categories = descriptor?.categories.orEmpty(),
                        autoPlayNextChapter = settings.autoPlayNextChapter,
                        selectedTtsEnginePackage = settings.ttsEnginePackage,
                        selectedTtsVoiceName = settings.ttsVoiceName,
                        selectedTtsLanguageTag = settings.ttsLanguageTag,
                        ttsVolume = settings.ttsVolume,
                        audioInterruptionMode = settings.audioInterruptionMode,
                        backgroundMusicUri = settings.backgroundMusicUri,
                        backgroundMusicEnabled = settings.backgroundMusicEnabled,
                        backgroundMusicVolume = settings.backgroundMusicVolume,
                        backgroundMusicDuckFactor = settings.backgroundMusicDuckFactor,
                        headsetMultiClickEnabled = settings.headsetMultiClickEnabled,
                        headsetSingleClickAction = settings.headsetSingleClickAction,
                        headsetDoubleClickAction = settings.headsetDoubleClickAction,
                        headsetTripleClickAction = settings.headsetTripleClickAction,
                        headsetLongPressAction = settings.headsetLongPressAction,
                        pauseOnHeadsetDisconnect = settings.pauseOnHeadsetDisconnect,
                        restorePlaybackAfterProcessDeath = settings.restorePlaybackAfterProcessDeath,
                        autoVoiceCastEnabled = settings.autoVoiceCastEnabled,
                        autoSceneMusicEnabled = settings.autoSceneMusicEnabled,
                        prefetchNarrationPlansEnabled = settings.prefetchNarrationPlansEnabled,
                        narrationPrefetchWindowChapters = settings.narrationPrefetchWindowChapters,
                        sceneMusicCrossfadeMillis = settings.sceneMusicCrossfadeMillis,
                        sceneMusicContinueAcrossChapters = settings.sceneMusicContinueAcrossChapters,
                        sceneMusicPlaybackMode = settings.sceneMusicPlaybackMode,
                        sceneMusicTargetLufs = settings.sceneMusicTargetLufs,
                        sceneMusicAvoidRepeatWindow = settings.sceneMusicAvoidRepeatWindow,
                        sonicProcessingEnabled = settings.sonicProcessingEnabled,
                        sonicDefaultSpeed = settings.sonicDefaultSpeed,
                        sonicDefaultPitch = settings.sonicDefaultPitch,
                        ttsCacheEnabled = settings.ttsCacheEnabled,
                        ttsCacheLimitMiB = settings.ttsCacheLimitMiB,
                        normalizeTtsVolumeEnabled = settings.normalizeTtsVolumeEnabled,
                        ttsTargetLufs = settings.ttsTargetLufs,
                        followingUpdatesEnabled = settings.followingUpdatesEnabled,
                        readerCacheLimitMiB = settings.readerCacheLimitMiB,
                        readerMode = settings.readerMode,
                        chapterSortDescending = settings.chapterSortDescending,
                        readerDisplay = settings.readerDisplay,
                        aiOnline = settings.aiOnline,
                        aiHasApiKey = container.aiCredentialStore.hasApiKey(settings.aiOnline.provider),
                        aiHasGeminiApiKey = container.aiCredentialStore.hasApiKey(AiProvider.GEMINI),
                        aiHasOpenAiApiKey = container.aiCredentialStore.hasApiKey(AiProvider.OPENAI_COMPATIBLE),
                    )
                }
                if (scheduledFollowingUpdates != settings.followingUpdatesEnabled) {
                    scheduledFollowingUpdates = settings.followingUpdatesEnabled
                    container.followingUpdateScheduler.setEnabled(settings.followingUpdatesEnabled)
                }
                if (appliedCacheLimitMiB != settings.readerCacheLimitMiB) {
                    appliedCacheLimitMiB = settings.readerCacheLimitMiB
                    trimReaderCache(settings.readerCacheLimitMiB, announce = false)
                }
                if (sourceChanged) search("")
            }
        }
    }

    private fun observeDiagnostics() {
        viewModelScope.launch {
            while (true) {
                val events = container.sourcePlatformManager.diagnosticsSnapshot()
                mutableState.update { current ->
                    current.copy(
                        diagnosticsMode = container.sourceDiagnostics.mode,
                        sourceDiagnosticCount = events.size,
                        sourceDiagnostics = container.sourcePlatformManager.diagnosticSummaries(200),
                        sourceTraces = container.sourcePlatformManager.diagnosticTraces(100),
                    )
                }
                delay(if (container.sourceDiagnostics.mode == "off") 2_000 else 750)
            }
        }
    }

    private fun observeLibrary() {
        viewModelScope.launch {
            container.libraryRepository.observeReading().collect { items ->
                mutableState.update { it.copy(readingStories = items) }
            }
        }
        viewModelScope.launch {
            container.libraryRepository.observeReadingProgress().collect { items ->
                mutableState.update { it.copy(readingProgress = items.associateBy(ReadingProgressEntity::storyId)) }
            }
        }
        viewModelScope.launch {
            container.libraryRepository.observeReadingHistory().collect { items ->
                mutableState.update { it.copy(readingHistory = items) }
            }
        }
        viewModelScope.launch {
            container.libraryRepository.observeOffline().collect { items ->
                mutableState.update { it.copy(downloadedStories = items) }
            }
        }
        viewModelScope.launch {
            container.libraryRepository.observeBookmarks().collect { items ->
                mutableState.update { it.copy(bookmarks = items) }
            }
        }
        viewModelScope.launch {
            container.libraryRepository.observeNotes().collect { items ->
                mutableState.update { it.copy(notes = items) }
            }
        }
        viewModelScope.launch {
            container.libraryRepository.observeFollowing().collect { items ->
                mutableState.update { it.copy(following = items) }
            }
        }
        viewModelScope.launch {
            container.libraryRepository.observeDownloads().collect { items ->
                mutableState.update { it.copy(downloads = items) }
            }
        }
        viewModelScope.launch {
            container.libraryRepository.observeDownloadFailures().collect { items ->
                mutableState.update { it.copy(downloadFailures = items) }
            }
        }
        viewModelScope.launch {
            container.libraryRepository.observeOfflineStorage().collect { items ->
                mutableState.update { it.copy(offlineStorage = items.associateBy(OfflineStoryStorage::storyId)) }
            }
        }
        viewModelScope.launch {
            container.libraryRepository.observeDownloadedChapterIds().collect { ids ->
                mutableState.update { it.copy(downloadedChapterIds = ids.toSet()) }
            }
        }
        viewModelScope.launch {
            container.libraryRepository.observeStorageUsage().collect { usage ->
                mutableState.update { it.copy(storageUsage = usage) }
            }
        }
        viewModelScope.launch {
            container.libraryRepository.observePronunciations().collect { rules ->
                mutableState.update { it.copy(pronunciations = rules) }
            }
        }
        viewModelScope.launch {
            container.libraryRepository.observeVietPhraseRules().collect { rules ->
                mutableState.update { it.copy(vietPhraseRules = rules) }
            }
        }
        viewModelScope.launch {
            container.libraryRepository.observeVietPhraseSnapshots().collect { snapshots ->
                mutableState.update { it.copy(vietPhraseSnapshots = snapshots) }
            }
        }
        viewModelScope.launch {
            container.libraryRepository.observeVietPhraseDictionaryStates().collect { dictionaries ->
                mutableState.update { it.copy(vietPhraseDictionaryStates = dictionaries) }
            }
        }
        viewModelScope.launch {
            container.libraryRepository.observeVietPhraseSuggestions().collect { suggestions ->
                mutableState.update { it.copy(vietPhraseSuggestions = suggestions) }
            }
        }
        viewModelScope.launch {
            container.libraryRepository.observeStoryAiProfiles().collect { profiles ->
                mutableState.update { it.copy(storyAiProfiles = profiles.associateBy(StoryAiProfileEntity::storyId)) }
            }
        }
        viewModelScope.launch {
            container.libraryRepository.observeSceneMusicTracks().collect { tracks ->
                mutableState.update { it.copy(sceneMusicTracks = tracks) }
            }
        }
        viewModelScope.launch {
            container.libraryRepository.observeStoryTtsProfiles().collect { profiles ->
                val mapped = profiles.associateBy(StoryTtsProfileEntity::storyId)
                mutableState.update { it.copy(storyTtsProfiles = mapped) }
                val active = mapped[PlaybackQueueStore.state.value.storyId]
                if (active != null) PlaybackQueueStore.updateVoice(active.rate, active.pitch, active.volume)
            }
        }
        viewModelScope.launch {
            container.libraryRepository.observeVoiceRoles().collect { roles ->
                mutableState.update { it.copy(voiceRoles = roles) }
            }
        }
        viewModelScope.launch {
            container.libraryRepository.observeAudioExports().collect { jobs ->
                mutableState.update { it.copy(audioExports = jobs) }
            }
        }
    }

    private fun observePlayback() {
        viewModelScope.launch {
            PlaybackQueueStore.state.collect { playback ->
                val previousChapterId = chapterSleepLastChapterId
                val currentChapterId = playback.chapterId
                val remaining = chapterSleepRemaining
                if (
                    remaining != null &&
                    previousChapterId.isNotBlank() &&
                    currentChapterId.isNotBlank() &&
                    currentChapterId != previousChapterId
                ) {
                    val nextRemaining = remaining - 1
                    if (nextRemaining <= 0) {
                        chapterSleepRemaining = null
                        ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_PAUSE)
                        mutableState.update {
                            it.copy(
                                playback = playback,
                                sleepTimerStatus = "Đang tắt",
                                message = "Đã dừng đọc theo hẹn giờ chương.",
                            )
                        }
                    } else {
                        chapterSleepRemaining = nextRemaining
                        mutableState.update {
                            it.copy(
                                playback = playback,
                                sleepTimerStatus = "Còn $nextRemaining chương",
                            )
                        }
                    }
                } else {
                    mutableState.update { it.copy(playback = playback) }
                }
                if (currentChapterId.isNotBlank()) chapterSleepLastChapterId = currentChapterId
                scheduleReadingPersistence(playback)
            }
        }
    }

    private fun scheduleReadingPersistence(playback: PlaybackSnapshot) {
        val content = state.value.chapterContent ?: return
        if (playback.chapterId.isBlank() || playback.chapterId != content.chapter.id) return
        val paragraphIndex = playback.paragraphIndex.coerceIn(0, content.paragraphs.lastIndex.coerceAtLeast(0))
        val persistenceKey = "${content.chapter.id}:$paragraphIndex"
        if (persistenceKey == lastPersistedReadingKey || persistenceKey == pendingReadingPersistenceKey) return
        readingPersistenceJob?.cancel()
        pendingReadingPersistenceKey = persistenceKey
        readingPersistenceJob = viewModelScope.launch {
            try {
                delay(650)
                val latestContent = state.value.chapterContent ?: return@launch
                val latestPlayback = state.value.playback
                if (latestContent.chapter.id != latestPlayback.chapterId) return@launch
                val latestIndex = latestPlayback.paragraphIndex.coerceIn(0, latestContent.paragraphs.lastIndex.coerceAtLeast(0))
                container.libraryRepository.saveProgress(
                    storyId = latestContent.chapter.storyId,
                    chapterId = latestContent.chapter.id,
                    paragraphIndex = latestIndex,
                    totalParagraphs = latestContent.paragraphs.size,
                )
                val story = state.value.storyDetail?.story
                container.libraryRepository.recordReadingHistory(
                    sourceId = latestPlayback.sourceId.ifBlank { story?.sourceId.orEmpty() },
                    storyTitle = story?.title.orEmpty(),
                    chapter = latestContent.chapter,
                    paragraphIndex = latestIndex,
                    totalParagraphs = latestContent.paragraphs.size,
                )
                lastPersistedReadingKey = "${latestContent.chapter.id}:$latestIndex"
            } finally {
                if (pendingReadingPersistenceKey == persistenceKey) pendingReadingPersistenceKey = ""
            }
        }
    }


    fun addPronunciation(original: String, replacement: String) {
        viewModelScope.launch {
            container.libraryRepository.savePronunciation(original, replacement)
                .onSuccess { showMessage("Đã lưu quy tắc phát âm.") }
                .onFailure { showMessage(it.message ?: "Không lưu được quy tắc phát âm.") }
        }
    }

    fun updatePronunciation(id: Long, original: String, replacement: String) {
        viewModelScope.launch {
            container.libraryRepository.updatePronunciation(id, original, replacement)
                .onSuccess { showMessage("Đã cập nhật.") }
                .onFailure { showMessage(it.message ?: "Không thể cập nhật cách đọc.") }
        }
    }

    fun setPronunciationEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch {
            container.libraryRepository.setPronunciationEnabled(id, enabled)
        }
    }

    fun deletePronunciation(id: Long) {
        viewModelScope.launch {
            container.libraryRepository.deletePronunciation(id)
            showMessage("Đã xóa quy tắc phát âm.")
        }
    }

    fun setReaderCacheLimitMiB(value: Int) {
        viewModelScope.launch { container.settingsRepository.setReaderCacheLimitMiB(value) }
    }

    fun trimReaderCacheNow() {
        trimReaderCache(state.value.readerCacheLimitMiB, announce = true)
    }

    private fun trimReaderCache(
        limitMiB: Int,
        announce: Boolean,
        protectedChapterIds: Set<String> = emptySet(),
    ) {
        viewModelScope.launch {
            val protected = protectedChapterIds.toMutableSet()
            state.value.chapterContent?.chapter?.id?.let(protected::add)
            state.value.playback.chapterId.takeIf(String::isNotBlank)?.let(protected::add)
            val result = container.libraryRepository.trimTransientReaderCache(
                limitBytes = limitMiB.coerceAtLeast(0).toLong() * 1024L * 1024L,
                protectedChapterIds = protected,
            )
            if (announce) {
                showMessage(
                    if (result.removedChapters == 0) "Bộ nhớ đệm đang nằm trong giới hạn."
                    else "Đã dọn ${result.removedChapters} chương cache (${formatBytesForMessage(result.freedBytes)}).",
                )
            }
        }
    }

    fun setReaderTheme(value: ReaderThemeMode) {
        viewModelScope.launch { container.settingsRepository.setReaderTheme(value) }
    }

    fun setReaderLayoutMode(value: ReaderLayoutMode) {
        viewModelScope.launch { container.settingsRepository.setReaderLayoutMode(value) }
    }

    fun setReaderFontSizeSp(value: Int) {
        viewModelScope.launch { container.settingsRepository.setReaderFontSizeSp(value) }
    }

    fun setReaderLineHeightPercent(value: Int) {
        viewModelScope.launch { container.settingsRepository.setReaderLineHeightPercent(value) }
    }

    fun setReaderHorizontalPaddingDp(value: Int) {
        viewModelScope.launch { container.settingsRepository.setReaderHorizontalPaddingDp(value) }
    }

    fun setReaderParagraphSpacingDp(value: Int) {
        viewModelScope.launch { container.settingsRepository.setReaderParagraphSpacingDp(value) }
    }

    fun setReaderKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setReaderKeepScreenOn(enabled) }
    }

    fun setReaderVolumeKeysNavigate(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setReaderVolumeKeysNavigate(enabled) }
    }


    fun setReaderMode(mode: ReaderMode) {
        ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_PAUSE)
        mutableState.update { it.copy(readerMode = mode) }
        viewModelScope.launch {
            container.settingsRepository.setReaderMode(mode)
            if (mode == ReaderMode.TTS) ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
        }
        showMessage(if (mode == ReaderMode.TTS) "Đã chuyển sang chế độ TTS." else "Đã chuyển sang chế độ Văn bản.")
    }

    fun setChapterSortDescending(descending: Boolean) {
        mutableState.update { it.copy(chapterSortDescending = descending) }
        viewModelScope.launch { container.settingsRepository.setChapterSortDescending(descending) }
    }

    fun saveReadingPositionNow() {
        val snapshot = state.value
        val content = snapshot.chapterContent ?: return
        viewModelScope.launch {
            container.libraryRepository.saveProgress(
                content.chapter.storyId,
                content.chapter.id,
                snapshot.playback.paragraphIndex,
                content.paragraphs.size,
            )
            val story = snapshot.storyDetail?.story
            container.libraryRepository.recordReadingHistory(
                sourceId = snapshot.playback.sourceId.ifBlank { story?.sourceId.orEmpty() },
                storyTitle = story?.title.orEmpty(),
                chapter = content.chapter,
                paragraphIndex = snapshot.playback.paragraphIndex,
                totalParagraphs = content.paragraphs.size,
            )
            showMessage("Đã lưu vị trí đọc tại đoạn ${snapshot.playback.paragraphIndex + 1}.")
        }
    }

    fun readerActionMessage(message: String) {
        showMessage(message)
    }

    fun moveToParagraph(index: Int) {
        if (index == state.value.playback.paragraphIndex) return
        if (!PlaybackQueueStore.moveTo(index)) return
        if (state.value.playback.isPlaying) {
            ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
        }
    }

    fun setTtsRate(value: Float) {
        viewModelScope.launch { container.settingsRepository.setTtsRate(value) }
    }

    fun setTtsPitch(value: Float) {
        viewModelScope.launch { container.settingsRepository.setTtsPitch(value) }
    }

    fun setTtsVolume(value: Float) {
        viewModelScope.launch {
            container.settingsRepository.setTtsVolume(value)
            ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
        }
    }

    fun setAutoPlayNextChapter(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setAutoPlayNextChapter(enabled) }
    }

    fun refreshTtsVoices() {
        viewModelScope.launch {
            mutableState.update { it.copy(ttsVoiceLoading = true) }
            val engineResult = container.ttsVoiceCatalog.loadEngines()
            val selectedEngine = container.settingsRepository.snapshot().ttsEnginePackage
            val voiceResult = container.ttsVoiceCatalog.load(selectedEngine)
            mutableState.update { current ->
                current.copy(
                    ttsVoiceLoading = false,
                    ttsEngines = (engineResult as? AppResult.Success)?.value.orEmpty(),
                    ttsVoices = (voiceResult as? AppResult.Success)?.value.orEmpty(),
                    message = (engineResult as? AppResult.Failure)?.message
                        ?: (voiceResult as? AppResult.Failure)?.message
                        ?: current.message,
                )
            }
        }
    }

    fun selectTtsEngine(engine: TtsEngineOption?) {
        viewModelScope.launch {
            container.settingsRepository.setTtsEngine(engine?.packageName)
            ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
            refreshTtsVoices()
            showMessage(if (engine == null) "Đã dùng bộ máy TTS mặc định." else "Đã chọn bộ máy ${engine.label}.")
        }
    }

    fun selectTtsVoice(voice: TtsVoiceOption?) {
        viewModelScope.launch {
            container.settingsRepository.setTtsVoice(voice?.name, voice?.languageTag ?: "vi-VN")
            if (state.value.playback.chapterId.isNotBlank()) {
                ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
            }
            showMessage(if (voice == null) "Đã dùng giọng TTS mặc định." else "Đã chọn ${voice.displayName}.")
        }
    }

    fun setAudioInterruptionMode(mode: AudioInterruptionMode) {
        viewModelScope.launch {
            container.settingsRepository.setAudioInterruptionMode(mode)
            ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
        }
    }

    fun setBackgroundMusic(uri: Uri?) {
        viewModelScope.launch {
            container.settingsRepository.setBackgroundMusic(uri?.toString())
            ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
            showMessage(if (uri == null) "Đã bỏ nhạc nền." else "Đã chọn nhạc nền cục bộ.")
        }
    }

    fun setBackgroundMusicEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled && state.value.backgroundMusicUri.isNullOrBlank()) {
                showMessage("Hãy chọn một tệp nhạc nền trước.")
                return@launch
            }
            container.settingsRepository.setBackgroundMusicEnabled(enabled)
            ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
        }
    }

    fun setBackgroundMusicVolume(value: Float) {
        viewModelScope.launch {
            container.settingsRepository.setBackgroundMusicVolume(value)
            ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
        }
    }

    fun setBackgroundMusicDuckFactor(value: Float) {
        viewModelScope.launch {
            container.settingsRepository.setBackgroundMusicDuckFactor(value)
            ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
        }
    }

    fun setHeadsetMultiClickEnabled(value: Boolean) = viewModelScope.launch {
        container.settingsRepository.setHeadsetMultiClickEnabled(value)
        ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
    }
    fun setHeadsetSingleClickAction(value: String) = viewModelScope.launch {
        container.settingsRepository.setHeadsetSingleClickAction(value)
        ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
    }
    fun setHeadsetDoubleClickAction(value: String) = viewModelScope.launch {
        container.settingsRepository.setHeadsetDoubleClickAction(value)
        ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
    }
    fun setHeadsetTripleClickAction(value: String) = viewModelScope.launch {
        container.settingsRepository.setHeadsetTripleClickAction(value)
        ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
    }
    fun setHeadsetLongPressAction(value: String) = viewModelScope.launch {
        container.settingsRepository.setHeadsetLongPressAction(value)
        ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
    }
    fun setPauseOnHeadsetDisconnect(value: Boolean) = viewModelScope.launch {
        container.settingsRepository.setPauseOnHeadsetDisconnect(value)
        ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
    }
    fun setRestorePlaybackAfterProcessDeath(value: Boolean) = viewModelScope.launch {
        container.settingsRepository.setRestorePlaybackAfterProcessDeath(value)
    }
    fun setAutoVoiceCastEnabled(value: Boolean) = viewModelScope.launch {
        container.settingsRepository.setAutoVoiceCastEnabled(value)
        ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
    }
    fun setAutoSceneMusicEnabled(value: Boolean) = viewModelScope.launch {
        container.settingsRepository.setAutoSceneMusicEnabled(value)
        ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
    }
    fun setPrefetchNarrationPlansEnabled(value: Boolean) = viewModelScope.launch {
        container.settingsRepository.setPrefetchNarrationPlansEnabled(value)
    }
    fun setNarrationPrefetchWindowChapters(value: Int) = viewModelScope.launch {
        container.settingsRepository.setNarrationPrefetchWindowChapters(value)
    }
    fun setSceneMusicCrossfadeMillis(value: Int) = viewModelScope.launch {
        container.settingsRepository.setSceneMusicCrossfadeMillis(value)
        ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
    }
    fun setSceneMusicContinueAcrossChapters(value: Boolean) = viewModelScope.launch {
        container.settingsRepository.setSceneMusicContinueAcrossChapters(value)
        ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
    }
    fun setSceneMusicPlaybackMode(value: SceneMusicPlaybackMode) = viewModelScope.launch {
        container.settingsRepository.setSceneMusicPlaybackMode(value)
        ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
    }
    fun setSceneMusicTargetLufs(value: Float) = viewModelScope.launch {
        container.settingsRepository.setSceneMusicTargetLufs(value)
        ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
    }
    fun setSceneMusicAvoidRepeatWindow(value: Int) = viewModelScope.launch {
        container.settingsRepository.setSceneMusicAvoidRepeatWindow(value)
        ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
    }
    fun setSonicProcessingEnabled(value: Boolean) = viewModelScope.launch {
        container.settingsRepository.setSonicProcessingEnabled(value)
        ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
    }
    fun setSonicDefaultSpeed(value: Float) = viewModelScope.launch {
        container.settingsRepository.setSonicDefaultSpeed(value)
        ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
    }
    fun setSonicDefaultPitch(value: Float) = viewModelScope.launch {
        container.settingsRepository.setSonicDefaultPitch(value)
        ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
    }
    fun setTtsCacheEnabled(value: Boolean) = viewModelScope.launch {
        container.settingsRepository.setTtsCacheEnabled(value)
        ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
    }
    fun setTtsCacheLimitMiB(value: Int) = viewModelScope.launch {
        container.settingsRepository.setTtsCacheLimitMiB(value)
        ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
    }
    fun setNormalizeTtsVolumeEnabled(value: Boolean) = viewModelScope.launch {
        container.settingsRepository.setNormalizeTtsVolumeEnabled(value)
        ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
    }
    fun setTtsTargetLufs(value: Float) = viewModelScope.launch {
        container.settingsRepository.setTtsTargetLufs(value)
        ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
    }

    fun previewTtsVoice() {
        if (state.value.playback.isPlaying) {
            showMessage("Hãy tạm dừng truyện trước khi nghe thử giọng.")
            return
        }
        ReaderPlaybackService.preview(
            getApplication(),
            "Xin chào. Đây là giọng đọc đang được chọn trong ứng dụng Nghe Truyện. AI và các từ trong từ điển phát âm cũng sẽ được đọc theo quy tắc của bạn.",
        )
    }

    fun openTtsSettings() {
        val context = getApplication<Application>()
        val preferred = Intent("com.android.settings.TTS_SETTINGS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallback = Intent(Settings.ACTION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(preferred) }
            .recoverCatching { context.startActivity(fallback) }
            .onFailure { showMessage("Không mở được phần cài đặt TTS trên thiết bị này.") }
    }

    fun setFollowingUpdatesEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setFollowingUpdatesEnabled(enabled) }
    }

    fun checkFollowingNow() {
        container.followingUpdateScheduler.checkNow()
        showMessage("Đã bắt đầu kiểm tra truyện theo dõi.")
    }

    fun clearAllDownloadedStories() {
        val storyIds = state.value.downloadedStories
            .filter { it.sourceId != "offline" }
            .map { it.id }
            .distinct()
        if (storyIds.isEmpty()) {
            showMessage("Không có truyện đã tải để xóa.")
            return
        }
        storyIds.forEach(::removeOfflineStory)
        showMessage("Đã bắt đầu xóa ${storyIds.size} truyện đã tải. Tiến độ đọc, lịch sử và dấu trang được giữ lại.")
    }

    fun factoryResetApplication() {
        ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_STOP)
        val manager = getApplication<Application>().getSystemService(ActivityManager::class.java)
        if (manager?.clearApplicationUserData() != true) {
            showMessage("Không thể đặt lại dữ liệu ứng dụng trên thiết bị này.")
        }
    }

    fun removeOfflineStory(storyId: String) {
        viewModelScope.launch {
            container.downloadScheduler.cancelStory(storyId)
            if (state.value.playback.storyId == storyId) {
                ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_STOP)
            }
            container.libraryRepository.removeOfflineContent(storyId)
            showMessage("Đã giải phóng nội dung ngoại tuyến của truyện.")
        }
    }

    fun clearReaderCache() {
        viewModelScope.launch {
            container.libraryRepository.clearTransientReaderCache()
            showMessage("Đã xóa bộ nhớ đệm chương đã đọc.")
        }
    }

    fun refreshSourceRepository(url: String) {
        val normalized = url.trim()
        if (normalized.isBlank()) {
            showMessage("Hãy nhập URL HTTPS của repository.json hoặc plugin.json vBook.")
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(sourceRepositoryRefreshing = true) }
            val result = withContext(Dispatchers.IO) { container.sourcePlatformManager.refreshRepository(normalized) }
            result.onSuccess { repository ->
                refreshSourcePlatformState()
                showMessage("Đã xác minh ${repository.name}: ${repository.packageCount} gói nguồn.")
            }.onFailure { error ->
                showMessage(error.message ?: "Không tải hoặc xác minh được repository nguồn.")
            }
            mutableState.update { it.copy(sourceRepositoryRefreshing = false) }
        }
    }

    fun removeSourceRepository(repositoryId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.sourcePlatformManager.removeRepository(repositoryId) }
                .onSuccess {
                    refreshSourcePlatformState()
                    showMessage("Đã gỡ repository nguồn khỏi ứng dụng.")
                }
                .onFailure { showMessage(it.message ?: "Không gỡ được repository nguồn.") }
        }
    }

    fun prepareRepositorySourceInstall(repositoryId: String, sourceId: String) {
        viewModelScope.launch {
            mutableState.update { it.copy(sourceRepositoryRefreshing = true) }
            val result = withContext(Dispatchers.IO) {
                container.sourcePlatformManager.prepareRepositoryInstall(repositoryId, sourceId)
            }
            result.onSuccess { preview ->
                mutableState.update {
                    it.copy(
                        pendingSourceInstall = preview,
                        pendingSourceInstallWarnings = container.sourcePlatformManager.pendingInstallWarnings(),
                    )
                }
                showMessage("Đã tải và xác minh ${preview.name} ${preview.version}. Hãy duyệt quyền trước khi cài.")
            }.onFailure { error ->
                showMessage(error.message ?: "Không tải được gói nguồn từ repository.")
            }
            refreshSourcePlatformState()
            mutableState.update { it.copy(sourceRepositoryRefreshing = false) }
        }
    }

    fun prepareSourcePack(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val resolver = getApplication<Application>().contentResolver
                val signedAttempt = runCatching {
                    resolver.openInputStream(uri)?.use { input ->
                        container.sourcePlatformManager.prepareInstall(input).getOrThrow()
                    } ?: error("Không mở được gói nguồn.")
                }
                if (signedAttempt.isSuccess) signedAttempt else {
                    val vBookAttempt = runCatching {
                        resolver.openInputStream(uri)?.use { input ->
                            container.sourcePlatformManager.prepareVBookImport(input).getOrThrow()
                        } ?: error("Không mở được gói vBook.")
                    }
                    if (vBookAttempt.isSuccess) vBookAttempt else runCatching {
                        resolver.openInputStream(uri)?.use { input ->
                            container.sourcePlatformManager.prepareNativeLuaImport(input).getOrThrow()
                        } ?: error("Không mở được extension Lua Native Source API 2.")
                    }.recoverCatching { luaError ->
                        error(
                            "Không nhận diện được .ntsource, vBook ZIP hoặc Lua Native Source API 2. " +
                                "SourcePack: ${signedAttempt.exceptionOrNull()?.message.orEmpty()}; " +
                                "vBook: ${vBookAttempt.exceptionOrNull()?.message.orEmpty()}; Lua: ${luaError.message}",
                        )
                    }
                }
            }
            result.onSuccess { preview ->
                mutableState.update {
                    it.copy(
                        pendingSourceInstall = preview,
                        pendingSourceInstallWarnings = container.sourcePlatformManager.pendingInstallWarnings(),
                    )
                }
                showMessage("Đã kiểm tra ${preview.name} ${preview.version}. Hãy duyệt quyền và cảnh báo trước khi cài.")
            }.onFailure { error ->
                showMessage(error.message ?: "Gói nguồn không hợp lệ.")
            }
            refreshSourcePlatformState()
        }
    }

    fun confirmSourcePackInstall() {
        viewModelScope.launch {
            container.sourcePlatformManager.confirmPendingInstall()
                .onSuccess { pack ->
                    mutableState.update { it.copy(pendingSourceInstall = null, pendingSourceInstallWarnings = emptyList()) }
                    refreshSourcePlatformState()
                    showMessage("Đã cài và kích hoạt ${pack.name} ${pack.version}.")
                }
                .onFailure { showMessage(it.message ?: "Không cài được gói nguồn.") }
        }
    }

    fun cancelSourcePackInstall() {
        container.sourcePlatformManager.cancelPendingInstall()
        mutableState.update { it.copy(pendingSourceInstall = null, pendingSourceInstallWarnings = emptyList()) }
    }

    fun enrollSourceTrustKey(keyId: String, algorithm: String, publicKeyBase64: String, fingerprint: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                container.sourcePlatformManager.enrollTrustKey(keyId, algorithm, publicKeyBase64, fingerprint)
            }.onSuccess {
                refreshSourcePlatformState()
                showMessage("Đã thêm khóa tin cậy ${it.keyId} sau khi đối chiếu fingerprint.")
            }.onFailure { showMessage(it.message ?: "Không thêm được khóa tin cậy.") }
        }
    }

    fun revokeSourceTrustKey(keyId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.sourcePlatformManager.revokeTrustKey(keyId) }
                .onSuccess {
                    refreshSourcePlatformState()
                    showMessage("Đã thu hồi khóa tin cậy $keyId.")
                }
                .onFailure { showMessage(it.message ?: "Không thu hồi được khóa tin cậy.") }
        }
    }

    fun applySourceTrustRotation(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Không mở được tệp xoay khóa.")
                    require(raw.size <= 256 * 1024) { "Tệp xoay khóa vượt quá 256 KiB." }
                    container.sourcePlatformManager.applyTrustKeyRotation(raw).getOrThrow()
                }
            }
            result.onSuccess {
                refreshSourcePlatformState()
                showMessage("Đã xác minh và xoay sang khóa ${it.keyId}.")
            }.onFailure { showMessage(it.message ?: "Không áp dụng được tệp xoay khóa.") }
        }
    }

    fun inspectSourceSelector(html: String, selector: String, baseUrl: String) {
        container.sourcePlatformManager.inspectSelector(html, selector, baseUrl)
            .onSuccess { inspection ->
                mutableState.update { it.copy(sourceSelectorInspection = inspection) }
                showMessage("Selector khớp ${inspection.matchCount} phần tử trong snapshot đã làm sạch.")
            }
            .onFailure { showMessage(it.message ?: "Không kiểm tra được selector.") }
    }

    fun setSourcePackEnabled(sourceId: String, enabled: Boolean) {
        viewModelScope.launch {
            container.sourcePlatformManager.setEnabled(sourceId, enabled)
                .onSuccess {
                    refreshSourcePlatformState()
                    showMessage(if (enabled) "Đã bật gói nguồn." else "Đã tắt gói nguồn.")
                }
                .onFailure { showMessage(it.message ?: "Không thay đổi được trạng thái gói nguồn.") }
        }
    }

    fun rollbackSourcePack(sourceId: String) {
        viewModelScope.launch {
            container.sourcePlatformManager.rollback(sourceId)
                .onSuccess {
                    refreshSourcePlatformState()
                    showMessage("Đã rollback nguồn về phiên bản trước.")
                }
                .onFailure { showMessage(it.message ?: "Không có phiên bản để rollback.") }
        }
    }

    fun checkSourcePack(sourceId: String) {
        val pack = state.value.sourcePacks.firstOrNull { it.id == sourceId }
        if (pack?.ecosystem != "VBOOK") {
            checkSource(sourceId)
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.sourcePlatformManager.checkInstalledPack(sourceId) }
                .onSuccess(::showMessage)
                .onFailure { showMessage(it.message ?: "Không kiểm tra được tiện ích vBook.") }
        }
    }

    fun saveSourceConfig(sourceId: String, changes: Map<String, String>) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.sourcePlatformManager.saveConfiguration(sourceId, changes) }
                .onSuccess {
                    refreshSourcePlatformState()
                    showMessage("Đã lưu cấu hình vBook. Thông tin nhạy cảm được mã hóa riêng.")
                }
                .onFailure { showMessage(it.message ?: "Không lưu được cấu hình vBook.") }
        }
    }

    fun resetSourceConfig(sourceId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.sourcePlatformManager.resetConfiguration(sourceId) }
                .onSuccess {
                    refreshSourcePlatformState()
                    showMessage("Đã khôi phục cấu hình vBook mặc định và xóa thông tin bí mật đã lưu.")
                }
                .onFailure { showMessage(it.message ?: "Không khôi phục được cấu hình vBook.") }
        }
    }

    fun updateSourcePack(sourceId: String) {
        val update = state.value.sourceRepositoryPackages.firstOrNull {
            it.sourceId == sourceId && it.status == "UPDATE_AVAILABLE" && it.canInstall
        }
        if (update == null) {
            showMessage("Không có bản cập nhật.")
            return
        }
        prepareRepositorySourceInstall(update.repositoryId, update.sourceId)
    }

    fun exportSourcePack(sourceId: String, destination: Uri) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openOutputStream(destination, "w")?.use { output ->
                        container.sourcePlatformManager.exportInstalledPack(sourceId, output).getOrThrow()
                    } ?: error("Không mở được tệp xuất tiện ích.")
                }
            }.onSuccess {
                showMessage("Đã xuất tiện ích.")
            }.onFailure { error ->
                showMessage(error.message ?: "Không xuất được tiện ích.")
            }
        }
    }

    fun removeSourcePack(sourceId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.sourcePlatformManager.removeInstalledPack(sourceId) }
                .onSuccess {
                    refreshSourcePlatformState()
                    showMessage("Đã xóa tiện ích. Dữ liệu truyện đã tải được giữ lại.")
                }
                .onFailure { error -> showMessage(error.message ?: "Không xóa được tiện ích.") }
        }
    }

    private fun refreshSourcePlatformState() {
        container.sourceRegistry.refreshSourcePacks(container.sourcePlatformManager.activeStorySources())
        mutableState.update { current ->
            current.copy(
                sources = container.sourceRegistry.descriptors(),
                sourcePacks = container.sourcePlatformManager.installedPacks(),
                sourceRepositories = container.sourcePlatformManager.repositories(),
                sourceRepositoryPackages = container.sourcePlatformManager.repositoryPackages(),
                sourceTrustKeys = container.sourcePlatformManager.trustKeys(),
                sourceDiagnosticCount = container.sourcePlatformManager.diagnosticsSnapshot().size,
                sourceDiagnostics = container.sourcePlatformManager.diagnosticSummaries(200),
                sourceTraces = container.sourcePlatformManager.diagnosticTraces(100),
            )
        }
    }

    private fun diagnosticsRuntimeSnapshot(): Map<String, String> {
        val snapshot = state.value
        val playback = snapshot.playback
        return linkedMapOf(
            "destination" to snapshot.destination.toString(),
            "rootTab" to snapshot.rootTab.name,
            "selectedSourceId" to snapshot.selectedSourceId,
            "loading" to snapshot.loading.toString(),
            "aiBusy" to snapshot.aiBusy.toString(),
            "storyId" to playback.storyId,
            "chapterId" to playback.chapterId,
            "chapterIndex" to playback.chapterIndex.toString(),
            "paragraphIndex" to playback.paragraphIndex.toString(),
            "speechChunkIndex" to playback.speechChunkIndex.toString(),
            "unitId" to playback.currentUnitId.orEmpty(),
            "playbackPlaying" to playback.isPlaying.toString(),
            "playbackPreparation" to playback.preparationState.name,
            "ttsRate" to playback.rate.toString(),
            "ttsPitch" to playback.pitch.toString(),
            "ttsVolume" to playback.volume.toString(),
            "sonicEnabled" to snapshot.sonicProcessingEnabled.toString(),
            "downloadJobs" to snapshot.downloads.size.toString(),
            "downloadFailures" to snapshot.downloadFailures.size.toString(),
            "audioExportJobs" to snapshot.audioExports.size.toString(),
            "vietPhraseRules" to snapshot.vietPhraseRules.size.toString(),
            "vietPhraseDictionaries" to snapshot.vietPhraseDictionaryStates.size.toString(),
            "sourceSessions" to snapshot.sourceSessions.size.toString(),
            "sourcePacks" to snapshot.sourcePacks.size.toString(),
        )
    }

    private fun diagnosticsRuntimeSnapshot(): Map<String, String> {
        val snapshot = state.value
        val playback = snapshot.playback
        return linkedMapOf(
            "destination" to snapshot.destination.toString(),
            "rootTab" to snapshot.rootTab.name,
            "selectedSourceId" to snapshot.selectedSourceId,
            "loading" to snapshot.loading.toString(),
            "aiBusy" to snapshot.aiBusy.toString(),
            "storyId" to playback.storyId,
            "chapterId" to playback.chapterId,
            "chapterIndex" to playback.chapterIndex.toString(),
            "paragraphIndex" to playback.paragraphIndex.toString(),
            "speechChunkIndex" to playback.speechChunkIndex.toString(),
            "unitId" to playback.currentUnitId.orEmpty(),
            "playbackPlaying" to playback.isPlaying.toString(),
            "playbackPreparation" to playback.preparationState.name,
            "ttsRate" to playback.rate.toString(),
            "ttsPitch" to playback.pitch.toString(),
            "ttsVolume" to playback.volume.toString(),
            "sonicEnabled" to snapshot.sonicProcessingEnabled.toString(),
            "downloadJobs" to snapshot.downloads.size.toString(),
            "downloadFailures" to snapshot.downloadFailures.size.toString(),
            "audioExportJobs" to snapshot.audioExports.size.toString(),
            "vietPhraseRules" to snapshot.vietPhraseRules.size.toString(),
            "vietPhraseDictionaries" to snapshot.vietPhraseDictionaryStates.size.toString(),
            "sourceSessions" to snapshot.sourceSessions.size.toString(),
            "sourcePacks" to snapshot.sourcePacks.size.toString(),
        )
    }

    fun exportSourceDiagnostics(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val payload = container.sourceDiagnostics.exportBundle(
                events = container.sourcePlatformManager.diagnosticsSnapshot(),
                installed = container.sourcePlatformManager.installedPacks(),
                repositories = container.sourcePlatformManager.repositories(),
                runtimeState = diagnosticsRuntimeSnapshot(),
                backupLogTail = state.value.backupLogText.takeLast(64_000),
            )
            getApplication<Application>().contentResolver.openOutputStream(uri, "w")?.use { output ->
                output.write(payload)
            } ?: error("Không mở được tệp báo cáo chẩn đoán.")
        }.onSuccess {
            showMessage("Đã xuất hộp đen chẩn đoán ZIP với trace và bằng chứng chi tiết.")

            }.onFailure {
                showMessage(it.message ?: "Không xuất được báo cáo chẩn đoán.")
            }
        }
    }

    fun clearSourceDiagnostics() {
        container.sourcePlatformManager.clearDiagnostics()
        container.sourceDiagnostics.clearBlackBox()
        refreshSourcePlatformState()
        showMessage("Đã xóa nhật ký, bằng chứng Advanced và hộp đen crash-safe.")
    }

    fun refreshSourceSessions() {
        val active = container.sourceRegistry.descriptors()
            .filter { container.sourceSessionStore.hasSession(it.id) }
            .mapTo(linkedSetOf()) { it.id }
        mutableState.update { it.copy(sourceSessions = active) }
    }

    fun checkSource(sourceId: String) {
        if (sourceId in mutableState.value.sourceHealthChecking) return
        viewModelScope.launch { runSourceCheck(sourceId) }
    }

    fun checkAllSources() {
        if (sourceCheckAllJob?.isActive == true) return
        val sourceIds = container.sourceRegistry.descriptors()
            .filter { it.health != vn.nghetruyen.app.core.model.SourceHealth.NOT_PORTED }
            .map { it.id }
        sourceCheckAllJob = viewModelScope.launch {
            sourceIds.forEach { sourceId ->
                if (sourceId !in mutableState.value.sourceHealthChecking) runSourceCheck(sourceId)
            }
        }
    }

    private suspend fun runSourceCheck(sourceId: String) {
        mutableState.update { it.copy(sourceHealthChecking = it.sourceHealthChecking + sourceId) }
        try {
            val report = container.sourceHealthChecker.check(sourceId)
            mutableState.update { current ->
                current.copy(sourceHealthReports = current.sourceHealthReports + (sourceId to report))
            }
        } finally {
            mutableState.update { current ->
                current.copy(sourceHealthChecking = current.sourceHealthChecking - sourceId)
            }
            refreshSourcePlatformState()
        }
    }

    fun openSourceLogin(sourceId: String) {
        val descriptor = container.sourceRegistry.get(sourceId)?.descriptor
        val vBookLogin = container.sourcePlatformManager.vBookLoginInfo(sourceId)
        val loginUrl = descriptor?.loginUrl ?: vBookLogin?.loginUrl
        val allowedHosts = descriptor?.allowedHosts?.takeIf { it.isNotEmpty() } ?: vBookLogin?.allowedHosts.orEmpty()
        val resolvedSourceId = descriptor?.id ?: vBookLogin?.sourceId
        if (resolvedSourceId == null || loginUrl.isNullOrBlank() || allowedHosts.isEmpty()) {
            showMessage("Nguồn này không có luồng đăng nhập riêng.")
            return
        }
        val intent = Intent(getApplication(), SourceLoginActivity::class.java)
            .putExtra(SourceLoginActivity.EXTRA_SOURCE_ID, resolvedSourceId)
            .putExtra(SourceLoginActivity.EXTRA_LOGIN_URL, loginUrl)
            .putExtra(SourceLoginActivity.EXTRA_ALLOWED_HOSTS, allowedHosts.toTypedArray())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        getApplication<Application>().startActivity(intent)
    }

    fun openSourceDiagnosticBrowser(sourceId: String) {
        val descriptor = container.sourceRegistry.get(sourceId)?.descriptor
        val initialUrl = descriptor?.loginUrl ?: descriptor?.baseUrl
        if (descriptor == null || initialUrl.isNullOrBlank() || descriptor.allowedHosts.isEmpty()) {
            showMessage("Nguồn này chưa có URL HTTPS và allowlist để chẩn đoán.")
            return
        }
        val intent = Intent(getApplication(), SourceDiagnosticBrowserActivity::class.java)
            .putExtra(SourceDiagnosticBrowserActivity.EXTRA_SOURCE_ID, descriptor.id)
            .putExtra(SourceDiagnosticBrowserActivity.EXTRA_INITIAL_URL, initialUrl)
            .putExtra(SourceDiagnosticBrowserActivity.EXTRA_ALLOWED_HOSTS, descriptor.allowedHosts.toTypedArray())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        getApplication<Application>().startActivity(intent)
    }

    fun clearSourceSession(sourceId: String) {
        val descriptor = container.sourceRegistry.get(sourceId)?.descriptor
        if (descriptor != null) {
            SourceLoginActivity.clearStoredSession(descriptor.id, descriptor.allowedHosts, container.sourceSessionStore)
        } else {
            container.sourceSessionStore.clear(sourceId)
        }
        refreshSourceSessions()
        showMessage("Đã xóa phiên đăng nhập và cookie trình duyệt của nguồn.")
    }

    fun setRootTab(tab: RootTab) {
        mutableState.update { it.copy(destination = Destination.Root, rootTab = tab, message = null) }
        if (tab == RootTab.PERSONAL) refreshSourcePlatformState()
    }

    fun setLibrarySection(section: LibrarySection) {
        mutableState.update { it.copy(librarySection = section) }
    }


    fun setStoryDetailTab(tab: String) {
        if (tab !in setOf("intro", "chapters", "comments", "source")) return
        mutableState.update { it.copy(storyDetailTab = tab) }
    }

    fun consumeStoryAdvancedOptionsRequest() {
        mutableState.update { it.copy(storyAdvancedOptionsRequested = false, storyAdvancedOptionsMode = null) }
    }

    fun openStoryAiOptions() = openStoryAdvancedOptions("ai")

    fun openStoryVoiceCastOptions() = openStoryAdvancedOptions("voice")

    private fun openStoryAdvancedOptions(mode: String) {
        mutableState.update { it.copy(storyAdvancedOptionsRequested = true, storyAdvancedOptionsMode = mode, loading = false) }
    }

    fun backToChapterList() {
        chapterLoadJob?.cancel()
        mutableState.update {
            it.copy(
                destination = Destination.Story,
                storyDetailTab = "chapters",
                loading = false,
                chapterContent = null,
                originalChapterContent = null,
                chapterTextMode = ChapterTextMode.ORIGINAL,
            )
        }
    }

    fun openStoryGenre(genre: String) {
        val detail = state.value.storyDetail ?: return
        val clean = genre.trim()
        if (clean.isBlank() || detail.story.sourceId == "offline") return
        val sourceId = detail.story.sourceId
        val source = container.sourceRegistry.get(sourceId) ?: return
        val matched = source.descriptor.categories.firstOrNull {
            StorySearch.normalize(it) == StorySearch.normalize(clean)
        }
        mutableState.update { current ->
            current.copy(
                destination = Destination.Root,
                rootTab = RootTab.EXPLORE,
                selectedSourceId = sourceId,
                categories = source.descriptor.categories,
                searchAllSources = false,
                query = if (matched == null) clean else "",
                stories = emptyList(),
                explorePage = 1,
                activeCategory = null,
                sourceSuggestions = emptyList(),
                storyAdvancedOptionsRequested = false,
            )
        }
        viewModelScope.launch {
            container.settingsRepository.selectSource(sourceId)
            if (matched != null) browseCategory(matched) else search(clean)
        }
    }

    fun updateQuery(value: String) {
        val normalized = value.take(240)
        mutableState.update { it.copy(query = normalized) }
        suggestionJob?.cancel()
        suggestionJob = null
        val snapshot = state.value
        val source = container.sourceRegistry.get(snapshot.selectedSourceId)
        if (snapshot.searchAllSources || normalized.trim().length < 2 || source?.descriptor?.supportsSuggestions != true) {
            mutableState.update { it.copy(sourceSuggestions = emptyList()) }
            return
        }
        suggestionJob = viewModelScope.launch {
            delay(250)
            when (val result = source.suggestions(normalized.trim())) {
                is AppResult.Success -> if (state.value.query == normalized) {
                    mutableState.update { it.copy(sourceSuggestions = result.value.take(12)) }
                }
                is AppResult.Failure -> if (state.value.query == normalized) {
                    mutableState.update { it.copy(sourceSuggestions = emptyList()) }
                }
            }
        }
    }

    fun selectSearchSuggestion(value: String) {
        suggestionJob?.cancel()
        suggestionJob = null
        mutableState.update { it.copy(query = value, sourceSuggestions = emptyList()) }
        search(value)
    }

    fun browseHome() {
        search("")
    }

    fun selectSource(sourceId: String) {
        searchJob?.cancel()
        searchJob = null
        viewModelScope.launch {
            container.settingsRepository.selectSource(sourceId)
            val descriptor = container.sourceRegistry.get(sourceId)?.descriptor
            mutableState.update {
                it.copy(
                    selectedSourceId = sourceId,
                    categories = descriptor?.categories.orEmpty(),
                    stories = emptyList(),
                    explorePage = 1,
                    exploreMode = ExploreMode.HOME,
                    activeCategory = null,
                    canLoadMoreStories = false,
                    sourceSuggestions = emptyList(),
                    searchAllSources = false,
                    searchedSourceCount = 0,
                    totalSearchSourceCount = 0,
                    message = null,
                )
            }
            search("")
        }
    }

    fun cancelSearch() {
        searchJob?.cancel()
        searchJob = null
        mutableState.update { it.copy(loading = false, message = "Đã hủy tìm kiếm.") }
    }

    fun setSearchAllSources(enabled: Boolean) {
        mutableState.update {
            it.copy(
                searchAllSources = enabled,
                activeCategory = null,
                stories = emptyList(),
                explorePage = 1,
                exploreMode = if (enabled) ExploreMode.SEARCH else ExploreMode.HOME,
                canLoadMoreStories = false,
                sourceSuggestions = emptyList(),
                searchedSourceCount = 0,
                totalSearchSourceCount = 0,
            )
        }
        if (enabled) {
            if (state.value.query.isNotBlank()) search()
        } else {
            search("")
        }
    }

    fun setSearchSortMode(mode: SearchSortMode) {
        val snapshot = state.value
        mutableState.update { current ->
            val sources = container.sourceRegistry.searchableSources()
            val health = sources.associate { it.descriptor.id to it.descriptor.health }
            val preserveWebsiteOrder = mode == SearchSortMode.RELEVANCE &&
                current.exploreMode != ExploreMode.SEARCH &&
                !current.searchAllSources
            current.copy(
                searchSortMode = mode,
                stories = if (preserveWebsiteOrder) current.stories else
                    StorySearch.merge(current.stories, health, current.query, mode),
            )
        }
        if (mode == SearchSortMode.RELEVANCE && snapshot.exploreMode != ExploreMode.SEARCH && !snapshot.searchAllSources) {
            when (snapshot.exploreMode) {
                ExploreMode.HOME -> browseHome()
                ExploreMode.CATEGORY -> snapshot.activeCategory?.let(::browseCategory)
                ExploreMode.SEARCH -> Unit
            }
        }
    }

    fun search(query: String = state.value.query) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val snapshot = state.value
            val cleanQuery = query.trim()
            if (snapshot.searchAllSources && cleanQuery.isBlank()) {
                mutableState.update {
                    it.copy(
                        loading = false,
                        query = query,
                        sourceSuggestions = emptyList(),
                        stories = emptyList(),
                        message = "Hãy nhập tên truyện hoặc tác giả để tìm trên tất cả nguồn.",
                    )
                }
                return@launch
            }
            mutableState.update {
                it.copy(
                    loading = true,
                    message = null,
                    query = query,
                    sourceSuggestions = emptyList(),
                    explorePage = 1,
                    exploreMode = if (cleanQuery.isBlank() && !snapshot.searchAllSources) ExploreMode.HOME else ExploreMode.SEARCH,
                    activeCategory = null,
                    canLoadMoreStories = false,
                    searchedSourceCount = 0,
                    totalSearchSourceCount = if (snapshot.searchAllSources) container.sourceRegistry.searchableSources().size else 1,
                )
            }
            if (snapshot.searchAllSources) {
                searchAcrossSources(cleanQuery, page = 1, append = false)
            } else {
                val source = container.sourceRegistry.get(snapshot.selectedSourceId)
                if (source == null) {
                    mutableState.update { it.copy(loading = false, message = "Không tìm thấy nguồn truyện.") }
                    return@launch
                }
                val result = if (cleanQuery.isBlank()) source.home(page = 1) else source.search(cleanQuery, page = 1)
                when (result) {
                    is AppResult.Success -> mutableState.update {
                        it.copy(
                            loading = false,
                            stories = mergeExploreStories(
                                existing = emptyList(),
                                incoming = result.value,
                                source = source.descriptor,
                                query = cleanQuery,
                                sortMode = snapshot.searchSortMode,
                                mode = if (cleanQuery.isBlank()) ExploreMode.HOME else ExploreMode.SEARCH,
                            ),
                            canLoadMoreStories = result.value.isNotEmpty(),
                            searchedSourceCount = 1,
                            totalSearchSourceCount = 1,
                        )
                    }
                    is AppResult.Failure -> mutableState.update { it.copy(loading = false, message = result.message) }
                }
            }
        }
    }

    private suspend fun searchAcrossSources(query: String, page: Int, append: Boolean) = supervisorScope {
        val sources = container.sourceRegistry.searchableSources()
        val health = sources.associate { it.descriptor.id to it.descriptor.health }
        val deferred = sources.map { source ->
            async {
                val result = source.search(query, page)
                mutableState.update { current ->
                    current.copy(searchedSourceCount = (current.searchedSourceCount + 1).coerceAtMost(sources.size))
                }
                source.descriptor.id to result
            }
        }
        val completed = deferred.map { it.await() }
        val successes = completed.flatMap { (_, result) ->
            when (result) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> emptyList()
            }
        }
        val previous = if (append) state.value.stories else emptyList()
        val merged = StorySearch.merge(previous + successes, health, query, state.value.searchSortMode)
        val failureCount = completed.count { it.second is AppResult.Failure }
        mutableState.update {
            it.copy(
                loading = false,
                stories = merged,
                explorePage = page,
                canLoadMoreStories = successes.isNotEmpty(),
                searchedSourceCount = sources.size,
                totalSearchSourceCount = sources.size,
                message = if (merged.isEmpty() && failureCount == sources.size) "Không nguồn nào trả về kết quả." else null,
            )
        }
    }

    fun browseCategory(category: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val source = container.sourceRegistry.get(state.value.selectedSourceId) ?: return@launch
            mutableState.update {
                it.copy(
                    loading = true,
                    message = null,
                    explorePage = 1,
                    exploreMode = ExploreMode.CATEGORY,
                    activeCategory = category,
                    sourceSuggestions = emptyList(),
                    canLoadMoreStories = false,
                )
            }
            when (val result = source.category(category, page = 1)) {
                is AppResult.Success -> mutableState.update {
                    it.copy(
                        loading = false,
                        stories = mergeExploreStories(
                            existing = emptyList(),
                            incoming = result.value,
                            source = source.descriptor,
                            query = "",
                            sortMode = state.value.searchSortMode,
                            mode = ExploreMode.CATEGORY,
                        ),
                        canLoadMoreStories = result.value.isNotEmpty(),
                    )
                }
                is AppResult.Failure -> mutableState.update { it.copy(loading = false, message = result.message) }
            }
        }
    }

    fun loadMoreStories() {
        val snapshot = state.value
        if (snapshot.loading || !snapshot.canLoadMoreStories) return
        val nextPage = snapshot.explorePage + 1
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, message = null, searchedSourceCount = 0) }
            if (snapshot.searchAllSources) {
                searchAcrossSources(snapshot.query.trim(), nextPage, append = true)
                return@launch
            }
            val source = container.sourceRegistry.get(snapshot.selectedSourceId) ?: return@launch
            val result = when (snapshot.exploreMode) {
                ExploreMode.HOME -> source.home(nextPage)
                ExploreMode.CATEGORY -> snapshot.activeCategory?.let { source.category(it, nextPage) }
                    ?: source.home(nextPage)
                ExploreMode.SEARCH -> source.search(snapshot.query, nextPage)
            }
            when (result) {
                is AppResult.Success -> {
                    val merged = mergeExploreStories(
                        existing = snapshot.stories,
                        incoming = result.value,
                        source = source.descriptor,
                        query = snapshot.query,
                        sortMode = snapshot.searchSortMode,
                        mode = snapshot.exploreMode,
                    )
                    mutableState.update {
                        it.copy(
                            loading = false,
                            stories = merged,
                            explorePage = nextPage,
                            canLoadMoreStories = result.value.isNotEmpty(),
                            searchedSourceCount = 1,
                            totalSearchSourceCount = 1,
                        )
                    }
                }
                is AppResult.Failure -> mutableState.update { it.copy(loading = false, message = result.message) }
            }
        }
    }

    private fun mergeExploreStories(
        existing: List<StorySummary>,
        incoming: List<StorySummary>,
        source: SourceDescriptor,
        query: String,
        sortMode: SearchSortMode,
        mode: ExploreMode,
    ): List<StorySummary> {
        val combined = (existing + incoming).distinctBy { story ->
            story.url.ifBlank { "${story.sourceId}:${story.id}" }
        }
        // A source home/category is already ordered by recency or ranking. Relevance
        // sorting with a blank query used to alphabetize it and silently destroy the
        // website order. Preserve that order unless the user explicitly chooses a sort.
        if (mode != ExploreMode.SEARCH && sortMode == SearchSortMode.RELEVANCE) return combined
        return StorySearch.merge(
            results = combined,
            healthBySource = mapOf(source.id to source.health),
            query = query,
            sortMode = sortMode,
        )
    }

    fun openStory(story: StorySummary) {
        storyLoadJob?.cancel()
        chapterLoadJob?.cancel()
        commentsLoadJob?.cancel()
        storyLoadJob = viewModelScope.launch {
            mutableState.update {
                it.copy(
                    loading = true,
                    message = null,
                    storyComments = emptyList(),
                    storyCommentsAvailable = false,
                    storyCommentsRefreshable = false,
                    storyCommentsLoading = false,
                    storyCommentsLoaded = false,
                    storyCommentsNextPageUrl = null,
                    storyCommentsFromCache = false,
                    storyCommentsMessage = null,
                )
            }
            val source = container.sourceRegistry.get(story.sourceId)
            if (source == null) {
                mutableState.update { it.copy(loading = false, message = "Nguồn truyện không tồn tại.") }
                return@launch
            }
            when (val result = source.story(story.url.ifBlank { story.id })) {
                is AppResult.Success -> {
                    container.libraryRepository.rememberStory(result.value.story)
                    if (container.libraryRepository.getFollowing(result.value.story.id) != null) {
                        container.libraryRepository.markFollowingSeen(result.value.story.id)
                    }
                    val progress = container.libraryRepository.getProgress(result.value.story.id)
                    val commentKey = commentCacheKey(result.value)
                    val embeddedComments = result.value.comments
                    if (embeddedComments.isNotEmpty()) {
                        storyCommentCache.put(commentKey, vn.nghetruyen.app.core.model.StoryCommentPage(embeddedComments))
                    }
                    val cachedComments = if (embeddedComments.isEmpty()) storyCommentCache.get(commentKey) else null
                    val initialComments = if (embeddedComments.isNotEmpty()) embeddedComments else cachedComments?.comments.orEmpty()
                    mutableState.update {
                        it.copy(
                            destination = Destination.Story,
                            loading = false,
                            storyDetail = result.value,
                            storyDetailTab = "intro",
                            storyAdvancedOptionsRequested = false,
                            storyComments = initialComments,
                            storyCommentsAvailable = source.descriptor.supportsComments || initialComments.isNotEmpty(),
                            storyCommentsRefreshable = source.descriptor.supportsComments,
                            storyCommentsLoaded = initialComments.isNotEmpty(),
                            storyCommentsLoading = false,
                            storyCommentsNextPageUrl = cachedComments?.nextPageUrl,
                            storyCommentsFromCache = cachedComments != null,
                            storyCommentsMessage = null,
                            continueAvailable = progress != null,
                        )
                    }
                }
                is AppResult.Failure -> mutableState.update { it.copy(loading = false, message = result.message) }
            }
        }
    }

    fun openLibraryStory(entity: StoryEntity) {
        if (entity.isOffline) {
            openOfflineStory(entity)
        } else {
            openStory(
                StorySummary(
                    id = entity.id,
                    sourceId = entity.sourceId,
                    title = entity.title,
                    author = entity.author,
                    coverUrl = entity.coverUrl,
                    description = entity.description,
                    url = entity.remoteUrl,
                ),
            )
        }
    }

    fun openOfflineStory(entity: StoryEntity) {
        storyLoadJob?.cancel()
        chapterLoadJob?.cancel()
        commentsLoadJob?.cancel()
        storyLoadJob = viewModelScope.launch {
            val chapters = container.libraryRepository.listOfflineChapters(entity.id).map { chapter ->
                ChapterSummary(
                    id = chapter.id,
                    storyId = chapter.storyId,
                    index = chapter.chapterIndex,
                    title = chapter.title,
                    url = chapter.remoteUrl,
                )
            }
            val progress = container.libraryRepository.getProgress(entity.id)
            mutableState.update {
                it.copy(
                    destination = Destination.Story,
                    rootTab = RootTab.LIBRARY,
                    storyDetailTab = "intro",
                    storyAdvancedOptionsRequested = false,
                    storyDetail = StoryDetail(
                        story = StorySummary(
                            id = entity.id,
                            sourceId = "offline",
                            title = entity.title,
                            author = entity.author,
                            description = entity.description,
                            coverUrl = entity.coverUrl,
                            url = entity.remoteUrl,
                        ),
                        status = "Ngoại tuyến",
                        chapters = chapters,
                    ),
                    message = null,
                    storyComments = emptyList(),
                    storyCommentsAvailable = false,
                    storyCommentsRefreshable = false,
                    storyCommentsLoading = false,
                    storyCommentsLoaded = true,
                    storyCommentsNextPageUrl = null,
                    storyCommentsFromCache = false,
                    storyCommentsMessage = null,
                    continueAvailable = progress != null,
                )
            }
        }
    }

    fun loadStoryComments(force: Boolean = false) {
        loadStoryCommentsPage(force = force, append = false)
    }

    fun loadMoreStoryComments() {
        loadStoryCommentsPage(force = false, append = true)
    }

    private fun loadStoryCommentsPage(force: Boolean, append: Boolean) {
        val snapshot = state.value
        val detail = snapshot.storyDetail ?: return
        if (!snapshot.storyCommentsAvailable || !snapshot.storyCommentsRefreshable || snapshot.storyCommentsLoading) return
        if (append && snapshot.storyCommentsNextPageUrl.isNullOrBlank()) return
        if (!append && snapshot.storyCommentsLoaded && !force) return
        val source = container.sourceRegistry.get(detail.story.sourceId) ?: run {
            mutableState.update {
                it.copy(
                    storyCommentsLoading = false,
                    storyCommentsLoaded = true,
                    storyCommentsMessage = "Nguồn bình luận không còn khả dụng.",
                )
            }
            return
        }
        val cacheKey = commentCacheKey(detail)
        if (force) storyCommentCache.invalidate(cacheKey)
        commentsLoadJob?.cancel()
        commentsLoadJob = viewModelScope.launch {
            mutableState.update {
                it.copy(
                    storyCommentsLoading = true,
                    storyCommentsMessage = null,
                    storyCommentsFromCache = false,
                )
            }
            val targetUrl = if (append) {
                snapshot.storyCommentsNextPageUrl.orEmpty()
            } else {
                detail.commentsUrl?.takeIf(String::isNotBlank)
                    ?: detail.story.url.ifBlank { detail.story.id }
            }
            when (val result = source.commentsPage(targetUrl)) {
                is AppResult.Success -> mutableState.update { current ->
                    if (current.storyDetail?.story?.id != detail.story.id) current
                    else {
                        val merged = if (append) {
                            StoryCommentCache.merge(current.storyComments, result.value.comments)
                        } else {
                            StoryCommentCache.merge(emptyList(), result.value.comments)
                        }
                        val page = result.value.copy(comments = merged)
                        storyCommentCache.put(cacheKey, page)
                        current.copy(
                            storyComments = merged,
                            storyCommentsLoading = false,
                            storyCommentsLoaded = true,
                            storyCommentsNextPageUrl = result.value.nextPageUrl,
                            storyCommentsFromCache = false,
                            storyCommentsMessage = if (merged.isEmpty())
                                "Chưa tìm thấy bình luận trong trang này."
                            else null,
                        )
                    }
                }
                is AppResult.Failure -> mutableState.update { current ->
                    if (current.storyDetail?.story?.id != detail.story.id) current
                    else current.copy(
                        storyCommentsLoading = false,
                        storyCommentsLoaded = true,
                        storyCommentsMessage = result.message,
                    )
                }
            }
        }
    }

    private fun commentCacheKey(detail: StoryDetail): StoryCommentCache.Key = StoryCommentCache.Key(
        sourceId = detail.story.sourceId,
        storyUrl = detail.story.url.ifBlank { detail.story.id },
    )

    fun openChapter(chapter: ChapterSummary) = openChapterAt(chapter, null)

    private fun openChapterAt(chapter: ChapterSummary, forcedStartIndex: Int?) {
        chapterLoadJob?.cancel()
        aiTranslationJob?.cancel()
        chapterLoadJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, message = null) }
            val detail = state.value.storyDetail
            val sourceId = detail?.story?.sourceId.orEmpty()
            val cached = container.libraryRepository.loadCachedChapter(chapter.id)
                ?: container.libraryRepository.loadCachedChapterByUrl(chapter.storyId, chapter.url)
            val content: AppResult<ChapterContent> = when {
                cached != null -> AppResult.Success(cached)
                sourceId == "offline" -> AppResult.Failure(
                    "NOT_FOUND",
                    "Không tìm thấy nội dung chương ngoại tuyến.",
                )
                else -> {
                    val source = container.sourceRegistry.get(sourceId)
                    source?.chapter(chapter.url.ifBlank { chapter.id })
                        ?: AppResult.Failure("NO_SOURCE", "Không tìm thấy nguồn chương.")
                }
            }
            when (content) {
                is AppResult.Success -> {
                    val enriched = enrichNavigation(ReaderDocumentNormalizer.normalize(content.value))
                    if (enriched.paragraphs.isEmpty()) {
                        mutableState.update {
                            it.copy(loading = false, message = "Chương không có nội dung có thể đọc.")
                        }
                        return@launch
                    }
                    container.libraryRepository.cacheChapter(enriched)
                    trimReaderCache(
                        state.value.readerCacheLimitMiB,
                        announce = false,
                        protectedChapterIds = setOf(enriched.chapter.id),
                    )
                    val settings = state.value
                    val profile = container.libraryRepository.getStoryTtsProfile(enriched.chapter.storyId)
                    val aiProfile = container.libraryRepository.getStoryAiProfile(enriched.chapter.storyId)
                    val savedProgress = container.libraryRepository.getProgress(enriched.chapter.storyId)
                    val startIndex = ReaderPositionResolver.resolve(
                        chapterId = enriched.chapter.id,
                        paragraphCount = enriched.paragraphs.size,
                        forcedParagraphIndex = forcedStartIndex,
                        savedChapterId = savedProgress?.chapterId,
                        savedParagraphIndex = savedProgress?.paragraphIndex,
                    )
                    val autoTranslate = aiProfile?.autoRunOnOpen == true && aiProfile.mode == "TRANSLATE"
                    val initialVietPhraseContent = if (referenceVietPhraseRuntime.enabled && !autoTranslate) {
                        val rules = container.libraryRepository.listEnabledVietPhrase(enriched.chapter.storyId)
                        if (rules.isEmpty()) null else {
                            val engine = withContext(Dispatchers.Default) { VietPhraseEngine(rules) }
                            val options = VietPhraseOptions(
                                storyId = enriched.chapter.storyId,
                                fallbackHanViet = referenceVietPhraseRuntime.fallbackHanViet,
                                traceLimit = 0,
                            )
                            val translated = withContext(Dispatchers.Default) {
                                enriched.paragraphs.map { engine.translate(it, options) }
                            }
                            enriched.copy(paragraphs = translated)
                        }
                    } else null
                    val initialContent = initialVietPhraseContent ?: enriched
                    val initialTextMode = if (initialVietPhraseContent != null) ChapterTextMode.VIETPHRASE else ChapterTextMode.ORIGINAL
                    PlaybackQueueStore.loadContent(
                        sourceId = sourceId,
                        content = initialContent,
                        startIndex = startIndex,
                        rate = profile?.rate ?: settings.playback.rate,
                        pitch = profile?.pitch ?: settings.playback.pitch,
                        preparationState = if (autoTranslate) PlaybackPreparationState.PREPARING else PlaybackPreparationState.READY,
                        preparationMessage = if (autoTranslate) "Đang chuẩn bị bản dịch AI trước khi phát…" else null,
                    )
                    mutableState.update {
                        it.copy(
                            destination = Destination.Reader,
                            loading = false,
                            aiBusy = autoTranslate,
                            chapterContent = initialContent,
                            originalChapterContent = enriched,
                            chapterTextMode = initialTextMode,
                            continueAvailable = true,
                            message = when {
                                autoTranslate -> "Đang chuẩn bị bản dịch AI trước khi phát…"
                                initialVietPhraseContent != null -> "Đã áp dụng VietPhrase."
                                else -> it.message
                            },
                        )
                    }
                    container.libraryRepository.recordReadingHistory(
                        sourceId = sourceId,
                        storyTitle = detail?.story?.title.orEmpty(),
                        chapter = enriched.chapter,
                        paragraphIndex = startIndex,
                        totalParagraphs = enriched.paragraphs.size,
                    )
                    when {
                        autoTranslate -> when (val translated = resolveAiTranslation(enriched, forceRefresh = false)) {
                            is AppResult.Success -> showChapterVariant(
                                translated.value,
                                ChapterTextMode.AI_TRANSLATION,
                                "Đã chuẩn bị bản dịch AI; playback đã sẵn sàng.",
                            )
                            is AppResult.Failure -> {
                                showChapterVariant(
                                    enriched,
                                    ChapterTextMode.ORIGINAL,
                                    "Dịch AI thất bại: ${translated.message}. Đã tiếp tục bằng bản gốc.",
                                )
                            }
                        }
                        aiProfile?.autoRunOnOpen == true && aiProfile.mode == "IMPROVE" -> improveVietPhraseForCurrentChapter()
                    }
                }
                is AppResult.Failure -> mutableState.update { it.copy(loading = false, message = content.message) }
            }
        }
    }

    fun loadMoreChapters() {
        val detail = state.value.storyDetail ?: return
        val nextPageUrl = detail.nextChapterPageUrl ?: return
        val source = container.sourceRegistry.get(detail.story.sourceId) ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, message = null) }
            when (
                val result = source.chapterPage(
                    storyId = detail.story.id,
                    url = nextPageUrl,
                    startIndex = detail.chapters.size,
                )
            ) {
                is AppResult.Success -> {
                    val merged = (detail.chapters + result.value.chapters).distinctBy { it.url.ifBlank { it.id } }
                    mutableState.update { current ->
                        current.copy(
                            loading = false,
                            storyDetail = detail.copy(
                                chapters = merged,
                                nextChapterPageUrl = result.value.nextPageUrl,
                            ),
                        )
                    }
                }
                is AppResult.Failure -> mutableState.update { it.copy(loading = false, message = result.message) }
            }
        }
    }

    fun loadAllChapters() {
        val detail = state.value.storyDetail ?: return
        if (detail.nextChapterPageUrl == null) {
            showMessage("Mục lục đã được tải đầy đủ.")
            return
        }
        val source = container.sourceRegistry.get(detail.story.sourceId) ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, message = "Đang tải toàn bộ mục lục…") }
            when (val result = StoryDownloadPlanner().collectChapters(source, detail)) {
                is AppResult.Success -> mutableState.update {
                    it.copy(
                        loading = false,
                        message = "Đã tải ${result.value.size} chương vào mục lục.",
                        storyDetail = detail.copy(chapters = result.value, nextChapterPageUrl = null),
                    )
                }
                is AppResult.Failure -> mutableState.update { it.copy(loading = false, message = result.message) }
            }
        }
    }

    fun downloadSelectedChapters(
        chapterNumbers: List<Int>,
        wifiOnly: Boolean = false,
        chargingOnly: Boolean = false,
    ) {
        val selected = chapterNumbers.filter { it > 0 }.distinct().sorted()
        if (selected.isEmpty()) {
            showMessage("Chưa chọn chương để tải.")
            return
        }
        selected.forEach { chapterNumber ->
            downloadChapterRange(chapterNumber, chapterNumber, wifiOnly, chargingOnly)
        }
        showMessage("Đã thêm ${selected.size} chương đã chọn vào hàng đợi tải.")
    }

    fun downloadChapterRange(
        startChapterNumber: Int,
        endChapterNumber: Int,
        wifiOnly: Boolean = false,
        chargingOnly: Boolean = false,
    ) {
        val detail = state.value.storyDetail ?: return
        if (detail.story.sourceId == "offline") {
            showMessage("Truyện nhập từ tệp đã có sẵn ngoại tuyến.")
            return
        }
        if (startChapterNumber < 1 || endChapterNumber < startChapterNumber) {
            showMessage("Khoảng chương không hợp lệ. Hãy nhập số bắt đầu từ 1 và số kết thúc không nhỏ hơn số bắt đầu.")
            return
        }
        val request = DownloadRequest.create(
            sourceId = detail.story.sourceId,
            storyId = detail.story.id,
            selectionMode = DownloadSelectionMode.RANGE,
            startChapterIndex = startChapterNumber - 1,
            endChapterIndex = endChapterNumber - 1,
            wifiOnly = wifiOnly,
            chargingOnly = chargingOnly,
        )
        queueDownload(
            request,
            endChapterNumber - startChapterNumber + 1,
            "Đã thêm chương $startChapterNumber đến $endChapterNumber vào hàng đợi tải.",
        )
    }

    fun downloadUnreadChapters(
        wifiOnly: Boolean = false,
        chargingOnly: Boolean = false,
    ) {
        val detail = state.value.storyDetail ?: return
        if (detail.story.sourceId == "offline") {
            showMessage("Truyện nhập từ tệp đã có sẵn ngoại tuyến.")
            return
        }
        viewModelScope.launch {
            val progress = container.libraryRepository.getProgress(detail.story.id)
            val current = progress?.chapterId?.let { container.libraryRepository.getChapter(it) }
            val firstUnread = ((current?.chapterIndex ?: -1) + 1).coerceAtLeast(0)
            val request = DownloadRequest.create(
                sourceId = detail.story.sourceId,
                storyId = detail.story.id,
                selectionMode = DownloadSelectionMode.UNREAD,
                startChapterIndex = firstUnread,
                wifiOnly = wifiOnly,
                chargingOnly = chargingOnly,
            )
            queueDownload(
                request,
                (detail.chapters.size - firstUnread).coerceAtLeast(1),
                "Đã thêm các chương chưa đọc vào hàng đợi tải.",
            )
        }
    }

    private fun queueDownload(request: DownloadRequest, estimatedTotal: Int, successMessage: String) {
        viewModelScope.launch {
            val storage = state.value.storageUsage
            val estimate = DownloadStorageGuard.estimate(
                availableBytes = DownloadStorageGuard.availableBytes(getApplication()),
                chapterCount = estimatedTotal.coerceAtLeast(1),
                knownDownloadedBytes = storage.downloadedBytes,
                knownDownloadedChapters = storage.downloadedChapters,
            )
            if (!estimate.hasEnoughSpace) {
                showMessage(
                    "Không đủ dung lượng an toàn. Cần thêm khoảng ${formatBytesForMessage(estimate.shortfallBytes)} trước khi tải.",
                )
                return@launch
            }
            container.libraryRepository.updateDownloadJob(
                id = request.jobId,
                storyId = request.storyId,
                sourceId = request.sourceId,
                state = DownloadState.QUEUED,
                completedChapters = 0,
                totalChapters = estimatedTotal.coerceAtLeast(0),
                selectionMode = request.selectionMode,
                startChapterIndex = request.startChapterIndex,
                endChapterIndex = request.endChapterIndex,
                wifiOnly = request.wifiOnly,
                chargingOnly = request.chargingOnly,
                currentChapterIndex = -1,
                currentChapterTitle = "",
                retryCount = 0,
            )
            container.libraryRepository.clearDownloadFailures(request.jobId)
            container.downloadScheduler.resume(request)
            showMessage(successMessage)
        }
    }

    fun readFirst() {
        val detail = state.value.storyDetail ?: return
        viewModelScope.launch {
            val progress = container.libraryRepository.getProgress(detail.story.id)
            val continued = progress?.let { saved ->
                val entity = container.libraryRepository.getChapter(saved.chapterId)
                entity?.let {
                    ChapterSummary(
                        id = it.id,
                        storyId = it.storyId,
                        index = it.chapterIndex,
                        title = it.title,
                        url = it.remoteUrl,
                    )
                } ?: detail.chapters.firstOrNull { it.id == saved.chapterId }
            }
            val target = continued ?: detail.chapters.firstOrNull()
            if (target == null) showMessage("Truyện chưa có chương.") else openChapter(target)
        }
    }

    fun togglePlayback() {
        if (state.value.chapterContent == null) return
        if (state.value.readerMode != ReaderMode.TTS) {
            showMessage("Chế độ Văn bản không phát TTS. Hãy chọn CHẾ ĐỘ ĐỌC: TTS.")
            return
        }
        ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_TOGGLE)
    }

    fun setSleepTimer(minutes: Int?) {
        if (state.value.chapterContent == null) return
        chapterSleepRemaining = null
        chapterSleepLastChapterId = state.value.playback.chapterId
        ReaderPlaybackService.setSleepTimer(getApplication(), minutes)
        mutableState.update {
            it.copy(sleepTimerStatus = if (minutes == null) "Đang tắt" else "Còn khoảng $minutes phút")
        }
        showMessage(if (minutes == null) "Đã hủy hẹn giờ ngủ." else "Sẽ dừng đọc sau $minutes phút.")
    }

    fun setSleepTimerByChapters(chapterCount: Int) {
        if (state.value.chapterContent == null) return
        val count = chapterCount.coerceAtLeast(1)
        ReaderPlaybackService.setSleepTimer(getApplication(), null)
        chapterSleepRemaining = count
        chapterSleepLastChapterId = state.value.playback.chapterId
        mutableState.update {
            it.copy(sleepTimerStatus = if (count == 1) "Hết chương hiện tại" else "Còn $count chương")
        }
        showMessage(if (count == 1) "Sẽ dừng khi hết chương hiện tại." else "Sẽ dừng sau $count chương.")
    }

    fun setDiagnosticsMode(mode: String) {
        val normalized = container.sourceDiagnostics.setMode(mode)
        mutableState.update { it.copy(diagnosticsMode = normalized) }
        showMessage(when (normalized) {
            "advanced" -> "Đã bật gỡ lỗi nâng cao: ghi trace, HTML/DOM, runtime, network và hộp đen chống mất log khi crash."
            "basic" -> "Đã bật gỡ lỗi cơ bản."
            else -> "Đã tắt ghi nhật ký chẩn đoán."
        })
    }

    fun moveParagraph(delta: Int) {
        PlaybackQueueStore.moveBy(delta)
        ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
    }

    fun nextChapter() {
        val content = state.value.chapterContent ?: return
        val next = ReaderChapterNavigation.next(
            current = content.chapter,
            chapters = state.value.storyDetail?.chapters.orEmpty(),
            fallbackUrl = content.nextChapterUrl,
        )
        if (next == null) showMessage("Đây là chương cuối.") else openChapter(next)
    }

    fun previousChapter() {
        val content = state.value.chapterContent ?: return
        val previous = ReaderChapterNavigation.previous(
            current = content.chapter,
            chapters = state.value.storyDetail?.chapters.orEmpty(),
            fallbackUrl = content.previousChapterUrl,
        )
        if (previous == null) showMessage("Đây là chương đầu.") else openChapter(previous)
    }

    fun saveVoiceProfileForCurrentStory() {
        val storyId = state.value.storyDetail?.story?.id
            ?: state.value.chapterContent?.chapter?.storyId
            ?: return
        viewModelScope.launch {
            val settings = container.settingsRepository.snapshot()
            container.libraryRepository.saveStoryTtsProfile(
                storyId = storyId,
                rate = settings.ttsRate,
                pitch = settings.ttsPitch,
                volume = settings.ttsVolume,
                enginePackage = settings.ttsEnginePackage,
                voiceName = settings.ttsVoiceName,
                languageTag = settings.ttsLanguageTag,
            )
            PlaybackQueueStore.updateVoice(settings.ttsRate, settings.ttsPitch, settings.ttsVolume)
            if (state.value.playback.chapterId.isNotBlank()) {
                ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
            }
            showMessage("Đã lưu cấu hình giọng riêng cho truyện này.")
        }
    }

    fun clearVoiceProfileForCurrentStory() {
        val storyId = state.value.storyDetail?.story?.id
            ?: state.value.chapterContent?.chapter?.storyId
            ?: return
        viewModelScope.launch {
            container.libraryRepository.deleteStoryTtsProfile(storyId)
            val settings = container.settingsRepository.snapshot()
            PlaybackQueueStore.updateVoice(settings.ttsRate, settings.ttsPitch, settings.ttsVolume)
            if (state.value.playback.chapterId.isNotBlank()) {
                ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
            }
            showMessage("Đã trở về cấu hình giọng chung.")
        }
    }

    fun loadRoleEditorVoices(enginePackage: String?) {
        viewModelScope.launch {
            mutableState.update { it.copy(roleEditorVoiceLoading = true, roleEditorEnginePackage = enginePackage) }
            when (val result = container.ttsVoiceCatalog.load(enginePackage)) {
                is AppResult.Success -> mutableState.update {
                    it.copy(
                        roleEditorVoiceLoading = false,
                        roleEditorEnginePackage = enginePackage,
                        roleEditorVoices = result.value,
                    )
                }
                is AppResult.Failure -> mutableState.update {
                    it.copy(
                        roleEditorVoiceLoading = false,
                        roleEditorEnginePackage = enginePackage,
                        roleEditorVoices = emptyList(),
                        message = result.message,
                    )
                }
            }
        }
    }

    fun saveGlobalVoiceRole(draft: VoiceRoleDraft) {
        viewModelScope.launch {
            container.libraryRepository.saveVoiceRole(
                storyId = GLOBAL_VOICE_PROFILE_STORY_ID,
                roleName = draft.roleName,
                aliasesCsv = draft.aliases,
                voiceName = draft.voiceName,
                languageTag = draft.languageTag,
                rate = draft.rate,
                pitch = draft.pitch,
                volume = draft.volume,
                isNarrator = draft.isNarrator,
                enginePackage = draft.enginePackage,
                expression = draft.expression.name,
                expressionStrength = draft.expressionStrength,
                sonicSpeed = draft.sonicSpeed,
                sonicPitch = draft.sonicPitch,
                enabled = draft.enabled,
                description = draft.description,
            ).onSuccess { savedId ->
                ReferenceVoiceRoleExtras.save(
                    getApplication(), savedId,
                    ReferenceVoiceRoleExtra(draft.processingMethod, draft.sonicAccurate),
                )
                draft.originalRoleId?.takeIf { it != savedId }?.let { oldId ->
                    container.libraryRepository.deleteVoiceRole(oldId)
                    ReferenceVoiceRoleExtras.remove(getApplication(), oldId)
                }
                ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
                showMessage("Đã lưu hồ sơ giọng chung ${draft.roleName}.")
            }.onFailure { showMessage(it.message ?: "Không lưu được hồ sơ giọng chung.") }
        }
    }

    fun setGlobalVoiceRoleEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            container.libraryRepository.setVoiceRoleEnabled(id, enabled)
            ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
        }
    }

    fun deleteGlobalVoiceRole(id: String) {
        viewModelScope.launch {
            val role = state.value.voiceRoles.firstOrNull { it.id == id && it.storyId == GLOBAL_VOICE_PROFILE_STORY_ID } ?: return@launch
            if (role.isNarrator) {
                showMessage("Người kể chuyện là hồ sơ bắt buộc và không thể xóa.")
                return@launch
            }
            container.libraryRepository.deleteVoiceRole(id)
            ReferenceVoiceRoleExtras.remove(getApplication(), id)
            ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
            showMessage("Đã xóa hồ sơ giọng chung ${role.roleName}.")
        }
    }

    fun restoreGlobalVoiceProfiles() {
        viewModelScope.launch {
            val roles = container.libraryRepository.restoreGlobalVoiceProfiles()
            ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
            showMessage("Đã khôi phục 7 hồ sơ mẫu; hiện có ${roles.size} hồ sơ giọng chung.")
        }
    }

    fun saveVoiceRoleForCurrentStory(draft: VoiceRoleDraft) {
        val storyId = state.value.storyDetail?.story?.id
            ?: state.value.chapterContent?.chapter?.storyId
            ?: return
        viewModelScope.launch {
            container.libraryRepository.saveVoiceRole(
                storyId = storyId,
                roleName = if (draft.isNarrator) "Người kể chuyện" else draft.roleName,
                aliasesCsv = draft.aliases,
                voiceName = draft.voiceName,
                languageTag = draft.languageTag,
                rate = draft.rate,
                pitch = draft.pitch,
                volume = draft.volume,
                isNarrator = draft.isNarrator,
                enginePackage = draft.enginePackage,
                expression = draft.expression.name,
                expressionStrength = draft.expressionStrength,
                sonicSpeed = draft.sonicSpeed,
                sonicPitch = draft.sonicPitch,
                enabled = draft.enabled,
                description = draft.description,
            ).onSuccess { savedId ->
                ReferenceVoiceRoleExtras.save(
                    getApplication(), savedId,
                    ReferenceVoiceRoleExtra(draft.processingMethod, draft.sonicAccurate),
                )
                draft.originalRoleId
                    ?.takeIf { it != savedId }
                    ?.let { oldId ->
                        container.libraryRepository.deleteVoiceRole(oldId)
                        ReferenceVoiceRoleExtras.remove(getApplication(), oldId)
                    }
                ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
                showMessage("Đã lưu hồ sơ giọng cho ${if (draft.isNarrator) "Người kể chuyện" else draft.roleName}.")
            }.onFailure { showMessage(it.message ?: "Không lưu được vai giọng.") }
        }
    }

    fun previewVoiceRole(draft: VoiceRoleDraft) {
        if (state.value.playback.isPlaying) {
            showMessage("Hãy tạm dừng truyện trước khi nghe thử vai.")
            return
        }
        ReaderPlaybackService.previewRole(
            context = getApplication(),
            text = if (draft.isNarrator) {
                "Người kể chuyện bắt đầu chương mới, với nhịp đọc rõ ràng và ổn định."
            } else {
                "${draft.roleName.ifBlank { "Nhân vật" }} nói: Tôi đã sẵn sàng kể câu chuyện này."
            },
            draft = draft,
        )
    }

    fun setVoiceRoleEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            container.libraryRepository.setVoiceRoleEnabled(id, enabled)
            ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
        }
    }

    fun deleteVoiceRole(id: String) {
        viewModelScope.launch {
            container.libraryRepository.deleteVoiceRole(id)
            ReferenceVoiceRoleExtras.remove(getApplication(), id)
            ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
        }
    }

    fun audioExportSuggestedName(request: AudioExportRequest): String {
        val normalized = request.normalized()
        val story = state.value.storyDetail?.story?.title.orEmpty().ifBlank { "nghe-truyen" }
        val chapter = state.value.chapterContent?.chapter?.title.orEmpty()
        val range = if (normalized.scope == AudioExportScope.CHAPTER_RANGE) {
            "-${normalized.startChapterNumber}-${normalized.endChapterNumber}"
        } else ""
        val raw = if (normalized.scope == AudioExportScope.CURRENT_CHAPTER && chapter.isNotBlank()) {
            "$story-$chapter"
        } else "$story$range"
        val ascii = Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(80)
            .ifBlank { "nghe-truyen" }
        return "$ascii.${normalized.format.extension}"
    }

    fun exportAudio(destination: Uri, request: AudioExportRequest) {
        val normalized = request.normalized()
        val detail = state.value.storyDetail
        val content = state.value.chapterContent
        val story = detail?.story ?: content?.chapter?.storyId?.let { id ->
            state.value.readingStories.firstOrNull { it.id == id }?.let { entity ->
                StorySummary(
                    id = entity.id,
                    sourceId = entity.sourceId,
                    title = entity.title,
                    author = entity.author,
                    description = entity.description,
                    coverUrl = entity.coverUrl,
                    url = entity.remoteUrl,
                )
            }
        }
        if (story == null) {
            showMessage("Không xác định được truyện cần xuất.")
            return
        }
        val chapterId = if (normalized.scope == AudioExportScope.CURRENT_CHAPTER) content?.chapter?.id else null
        if (normalized.scope == AudioExportScope.CURRENT_CHAPTER && chapterId.isNullOrBlank()) {
            showMessage("Hãy mở một chương trước khi xuất ${normalized.format.name}.")
            return
        }
        val startIndex = when (normalized.scope) {
            AudioExportScope.CURRENT_CHAPTER -> content?.chapter?.index ?: 0
            AudioExportScope.CHAPTER_RANGE -> normalized.startChapterNumber - 1
            AudioExportScope.CACHED_STORY -> 0
        }
        val endIndex = when (normalized.scope) {
            AudioExportScope.CURRENT_CHAPTER -> startIndex
            AudioExportScope.CHAPTER_RANGE -> normalized.endChapterNumber - 1
            AudioExportScope.CACHED_STORY -> Int.MAX_VALUE
        }
        viewModelScope.launch {
            val exportable = if (chapterId != null) {
                container.libraryRepository.getChapter(chapterId)?.content?.isNotBlank() == true
            } else {
                container.libraryRepository.listExportableChapters(story.id, startIndex, endIndex).isNotEmpty()
            }
            if (!exportable) {
                showMessage("Khoảng đã chọn chưa có nội dung lưu ngoại tuyến. Hãy mở hoặc tải truyện trước.")
                return@launch
            }
            val jobId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            container.libraryRepository.createAudioExportJob(
                AudioExportJobEntity(
                    id = jobId,
                    storyId = story.id,
                    storyTitle = story.title,
                    chapterId = chapterId,
                    destinationUri = destination.toString(),
                    outputFormat = normalized.format.name,
                    mimeType = normalized.format.mimeType,
                    scope = normalized.scope.name,
                    startChapterIndex = startIndex,
                    endChapterIndex = endIndex,
                    includeSceneMusic = normalized.includeSceneMusic,
                    packaging = normalized.packaging.name,
                    chapterMarkers = normalized.chapterMarkers,
                    author = story.author,
                    stage = "QUEUED",
                    state = DownloadState.QUEUED.name,
                    completedSegments = 0,
                    totalSegments = 0,
                    errorMessage = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            container.audioExportScheduler.enqueue(jobId)
            val target = if (normalized.packaging == AudioExportPackaging.ONE_FILE_PER_CHAPTER) "thư mục từng chương" else "một tệp"
            showMessage("Đã bắt đầu xuất ${normalized.format.name} thành $target.")
        }
    }

    fun cancelAudioExport(jobId: String) {
        container.audioExportScheduler.cancel(jobId)
        viewModelScope.launch {
            val current = container.libraryRepository.getAudioExportJob(jobId)
            container.libraryRepository.updateAudioExportProgress(
                jobId,
                current?.completedSegments ?: 0,
                current?.totalSegments ?: 0,
                DownloadState.CANCELLED,
                "Đã hủy theo yêu cầu; có thể tiếp tục từ checkpoint.",
            )
        }
        showMessage("Đã yêu cầu hủy xuất âm thanh.")
    }


    fun resumeAudioExport(jobId: String) {
        viewModelScope.launch {
            val current = container.libraryRepository.getAudioExportJob(jobId)
            if (current == null || current.state == DownloadState.COMPLETED.name) {
                showMessage("Tác vụ xuất không còn có thể tiếp tục.")
                return@launch
            }
            container.libraryRepository.updateAudioExportJob(
                current.copy(
                    state = DownloadState.QUEUED.name,
                    stage = "QUEUED",
                    errorMessage = null,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            container.audioExportScheduler.enqueue(jobId)
            showMessage("Đã tiếp tục xuất ${current.outputFormat} từ checkpoint.")
        }
    }

    fun openAudioExport(job: AudioExportJobEntity) {
        if (job.state != DownloadState.COMPLETED.name) {
            showMessage("Tệp âm thanh chưa xuất xong.")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(job.destinationUri), job.mimeType)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { getApplication<Application>().startActivity(intent) }
            .onFailure { showMessage("Không tìm thấy ứng dụng có thể mở tệp âm thanh này.") }
    }

    fun runPerformanceDiagnostics() {
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { PerformanceDiagnostics.run(getApplication()) }
                .onSuccess { snapshot ->
                    mutableState.update { it.copy(performanceReport = snapshot.toJson()) }
                    showMessage(
                        "Benchmark ${snapshot.chapterCount} chương: dựng chỉ mục ${"%.1f".format(snapshot.chapterIndexBuildMillis)} ms, " +
                            "tìm kiếm p95 ${"%.1f".format(snapshot.chapterSearchP95Millis)} ms.",
                    )
                }
                .onFailure { showMessage(it.message ?: "Không chạy được benchmark thiết bị.") }
        }
    }

    fun importBook(uri: Uri) {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, message = "Đang nhập tệp...") }
            when (val result = container.bookImporter.import(uri)) {
                is AppResult.Success -> {
                    val story = container.libraryRepository.importBook(result.value)
                    val entity = container.libraryRepository.getStory(story.id)
                    mutableState.update { it.copy(loading = false, message = "Đã nhập ${story.title}.") }
                    if (entity != null) openOfflineStory(entity)
                }
                is AppResult.Failure -> mutableState.update { it.copy(loading = false, message = result.message) }
            }
        }
    }

    fun downloadCurrentStory(
        wifiOnly: Boolean = false,
        chargingOnly: Boolean = false,
    ) {
        val detail = state.value.storyDetail ?: return
        if (detail.story.sourceId == "offline") {
            showMessage("Truyện nhập từ tệp đã có sẵn ngoại tuyến.")
            return
        }
        val request = DownloadRequest.create(
            sourceId = detail.story.sourceId,
            storyId = detail.story.id,
            selectionMode = DownloadSelectionMode.ALL,
            wifiOnly = wifiOnly,
            chargingOnly = chargingOnly,
        )
        queueDownload(
            request,
            detail.chapters.size.coerceAtLeast(1),
            "Đã thêm toàn bộ truyện vào hàng đợi tải.",
        )
    }

    fun pauseDownload(jobId: String) {
        viewModelScope.launch {
            val job = container.libraryRepository.getDownloadJob(jobId) ?: return@launch
            container.libraryRepository.updateDownloadJob(
                id = job.id,
                storyId = job.storyId,
                sourceId = job.sourceId,
                state = DownloadState.PAUSED,
                completedChapters = job.completedChapters,
                totalChapters = job.totalChapters,
                errorMessage = "Đã tạm dừng.",
            )
            container.downloadScheduler.cancel(DownloadRequest.from(job))
            showMessage("Đã tạm dừng tải truyện.")
        }
    }

    fun resumeDownload(jobId: String) {
        viewModelScope.launch {
            val job = container.libraryRepository.getDownloadJob(jobId) ?: return@launch
            container.libraryRepository.updateDownloadJob(
                id = job.id,
                storyId = job.storyId,
                sourceId = job.sourceId,
                state = DownloadState.QUEUED,
                completedChapters = job.completedChapters,
                totalChapters = job.totalChapters,
                errorMessage = null,
            )
            container.libraryRepository.clearDownloadFailures(job.id)
            container.downloadScheduler.resume(DownloadRequest.from(job))
            showMessage("Đã tiếp tục tải truyện.")
        }
    }

    fun retryDownload(jobId: String) = resumeDownload(jobId)

    fun prioritizeDownload(jobId: String) {
        viewModelScope.launch {
            val job = container.libraryRepository.getDownloadJob(jobId) ?: return@launch
            container.libraryRepository.updateDownloadJob(
                id = job.id,
                storyId = job.storyId,
                sourceId = job.sourceId,
                state = DownloadState.QUEUED,
                completedChapters = job.completedChapters,
                totalChapters = job.totalChapters,
                errorMessage = null,
            )
            container.downloadScheduler.prioritize(DownloadRequest.from(job))
            showMessage("Đã đưa tác vụ tải lên ưu tiên cao.")
        }
    }

    fun retryFailedChapter(failureId: String) {
        val failure = state.value.downloadFailures.firstOrNull { it.id == failureId } ?: return
        viewModelScope.launch {
            container.libraryRepository.clearDownloadFailure(failure.jobId, failure.chapterIndex)
            val request = DownloadRequest.create(
                sourceId = failure.sourceId,
                storyId = failure.storyId,
                selectionMode = DownloadSelectionMode.SINGLE,
                startChapterIndex = failure.chapterIndex,
                endChapterIndex = failure.chapterIndex,
            )
            queueDownload(
                request,
                1,
                "Đã thêm lại ${failure.chapterTitle.ifBlank { "chương ${failure.chapterIndex + 1}" }} vào hàng đợi.",
            )
        }
    }

    fun cancelDownload(storyId: String) {
        container.downloadScheduler.cancelStory(storyId)
        viewModelScope.launch {
            state.value.downloads.filter { it.storyId == storyId && it.state in setOf(
                DownloadState.QUEUED.name,
                DownloadState.RUNNING.name,
                DownloadState.PAUSED.name,
            ) }.forEach { job ->
                container.libraryRepository.updateDownloadJob(
                    id = job.id,
                    storyId = job.storyId,
                    sourceId = job.sourceId,
                    state = DownloadState.CANCELLED,
                    completedChapters = job.completedChapters,
                    totalChapters = job.totalChapters,
                    errorMessage = "Đã hủy theo yêu cầu.",
                )
            }
        }
        showMessage("Đã yêu cầu hủy tải truyện.")
    }

    fun bookmarkCurrent() {
        val content = state.value.chapterContent ?: return
        val paragraphIndex = state.value.playback.paragraphIndex
        val snippet = content.paragraphs.getOrNull(paragraphIndex).orEmpty().take(60)
        viewModelScope.launch {
            container.libraryRepository.addBookmark(
                storyId = content.chapter.storyId,
                chapterId = content.chapter.id,
                paragraphIndex = paragraphIndex,
                label = "${content.chapter.title}: $snippet",
            )
            showMessage("Đã đánh dấu đoạn ${paragraphIndex + 1}.")
        }
    }


    fun saveCurrentNote(text: String) {
        val content = state.value.chapterContent ?: return
        val paragraphIndex = state.value.playback.paragraphIndex
        viewModelScope.launch {
            runCatching {
                container.libraryRepository.saveNote(
                    storyId = content.chapter.storyId,
                    chapterId = content.chapter.id,
                    paragraphIndex = paragraphIndex,
                    text = text,
                )
            }.onSuccess {
                showMessage("Đã lưu ghi chú cho đoạn ${paragraphIndex + 1}.")
            }.onFailure {
                showMessage(it.message ?: "Không lưu được ghi chú.")
            }
        }
    }

    fun deleteNote(noteId: String) {
        viewModelScope.launch {
            container.libraryRepository.deleteNote(noteId)
            showMessage("Đã xóa ghi chú.")
        }
    }

    fun openNote(note: ChapterNoteEntity) {
        viewModelScope.launch {
            val story = container.libraryRepository.getStory(note.storyId)
            val chapter = container.libraryRepository.getChapter(note.chapterId)
            if (story == null || chapter == null) {
                showMessage("Ghi chú không còn liên kết tới nội dung đã lưu.")
                return@launch
            }
            val cachedChapters = container.libraryRepository.listOfflineChapters(story.id).map {
                ChapterSummary(it.id, it.storyId, it.chapterIndex, it.title, it.remoteUrl)
            }
            mutableState.update {
                it.copy(
                    destination = Destination.Story,
                    rootTab = RootTab.LIBRARY,
                    storyDetailTab = "intro",
                    storyAdvancedOptionsRequested = false,
                    storyDetail = StoryDetail(
                        story = StorySummary(
                            id = story.id,
                            sourceId = if (story.isOffline) "offline" else story.sourceId,
                            title = story.title,
                            author = story.author,
                            coverUrl = story.coverUrl,
                            description = story.description,
                            url = story.remoteUrl,
                        ),
                        status = if (story.isOffline) "Ngoại tuyến" else "Bản đã lưu gần đây",
                        chapters = cachedChapters,
                    ),
                    continueAvailable = true,
                )
            }
            openChapterAt(
                ChapterSummary(chapter.id, chapter.storyId, chapter.chapterIndex, chapter.title, chapter.remoteUrl),
                note.paragraphIndex,
            )
        }
    }

    fun toggleStoryBookmark() {
        val detail = state.value.storyDetail ?: return
        val marker = "Truyện: ${detail.story.title}"
        val existing = state.value.bookmarks.firstOrNull { it.storyId == detail.story.id && it.label == marker }
        viewModelScope.launch {
            if (existing != null) {
                container.libraryRepository.deleteBookmark(existing.id)
                showMessage("Đã bỏ đánh dấu truyện ${detail.story.title}.")
            } else {
                val first = detail.chapters.firstOrNull()
                if (first == null) {
                    showMessage("Truyện chưa có chương để tạo đánh dấu.")
                    return@launch
                }
                container.libraryRepository.addBookmark(
                    storyId = detail.story.id,
                    chapterId = first.id,
                    paragraphIndex = 0,
                    label = marker,
                )
                showMessage("Đã đánh dấu truyện ${detail.story.title}.")
            }
        }
    }

    fun toggleFollowing() {
        val detail = state.value.storyDetail ?: return
        if (detail.story.sourceId == "offline") {
            showMessage("Truyện nhập từ tệp không có nguồn trực tuyến để kiểm tra chương mới.")
            return
        }
        val alreadyFollowing = state.value.following.any { it.storyId == detail.story.id }
        viewModelScope.launch {
            if (alreadyFollowing) {
                container.libraryRepository.unfollow(detail.story.id)
                showMessage("Đã bỏ theo dõi ${detail.story.title}.")
            } else {
                val source = container.sourceRegistry.get(detail.story.sourceId)
                val latestSummary = when (val result = source?.latestChapter(detail.story.url.ifBlank { detail.story.id })) {
                    is AppResult.Success -> result.value
                    else -> detail.chapters.lastOrNull()
                }
                container.libraryRepository.follow(
                    story = detail.story,
                    latestKnownChapter = latestSummary?.title.orEmpty(),
                    latestKnownChapterIndex = latestSummary?.index ?: -1,
                )
                showMessage("Đã theo dõi ${detail.story.title}.")
            }
        }
    }


    fun openBookmark(bookmark: BookmarkEntity) {
        viewModelScope.launch {
            val story = container.libraryRepository.getStory(bookmark.storyId)
            val chapter = container.libraryRepository.getChapter(bookmark.chapterId)
            if (story == null || chapter == null) {
                showMessage("Đánh dấu không còn liên kết tới nội dung đã lưu.")
                return@launch
            }
            val summary = StorySummary(
                id = story.id,
                sourceId = if (story.isOffline) "offline" else story.sourceId,
                title = story.title,
                author = story.author,
                coverUrl = story.coverUrl,
                description = story.description,
                url = story.remoteUrl,
            )
            val cachedChapters = container.libraryRepository.listOfflineChapters(story.id).map {
                ChapterSummary(it.id, it.storyId, it.chapterIndex, it.title, it.remoteUrl)
            }
            val remoteDetail = if (!story.isOffline) {
                when (val result = container.sourceRegistry.get(story.sourceId)?.story(story.remoteUrl)) {
                    is AppResult.Success -> result.value
                    else -> null
                }
            } else null
            mutableState.update {
                it.copy(
                    destination = Destination.Story,
                    rootTab = RootTab.LIBRARY,
                    storyDetail = remoteDetail ?: StoryDetail(
                        story = summary,
                        status = if (story.isOffline) "Ngoại tuyến" else "Bản đã lưu gần đây",
                        chapters = cachedChapters,
                    ),
                    continueAvailable = true,
                )
            }
            openChapterAt(
                ChapterSummary(chapter.id, chapter.storyId, chapter.chapterIndex, chapter.title, chapter.remoteUrl),
                bookmark.paragraphIndex,
            )
        }
    }

    fun openReadingHistory(item: ReadingHistoryEntity) {
        viewModelScope.launch {
            val story = container.libraryRepository.getStory(item.storyId)
            val chapter = container.libraryRepository.getChapter(item.chapterId)
            if (story == null || chapter == null) {
                showMessage("Mục lịch sử không còn nội dung đã lưu để mở.")
                return@launch
            }
            val summary = StorySummary(
                id = story.id,
                sourceId = if (story.isOffline) "offline" else story.sourceId,
                title = story.title,
                author = story.author,
                coverUrl = story.coverUrl,
                description = story.description,
                url = story.remoteUrl,
            )
            val cachedChapters = container.libraryRepository.listOfflineChapters(story.id).map {
                ChapterSummary(it.id, it.storyId, it.chapterIndex, it.title, it.remoteUrl)
            }
            val remoteDetail = if (!story.isOffline) {
                when (val result = container.sourceRegistry.get(story.sourceId)?.story(story.remoteUrl)) {
                    is AppResult.Success -> result.value
                    else -> null
                }
            } else null
            mutableState.update {
                it.copy(
                    destination = Destination.Story,
                    rootTab = RootTab.LIBRARY,
                    storyDetail = remoteDetail ?: StoryDetail(
                        story = summary,
                        status = if (story.isOffline) "Ngoại tuyến" else "Bản đã lưu gần đây",
                        chapters = cachedChapters,
                    ),
                    continueAvailable = true,
                )
            }
            openChapterAt(
                ChapterSummary(chapter.id, chapter.storyId, chapter.chapterIndex, chapter.title, chapter.remoteUrl),
                item.paragraphIndex,
            )
        }
    }

    fun clearReadingHistory() {
        viewModelScope.launch {
            container.libraryRepository.clearReadingHistory()
            showMessage("Đã xóa lịch sử đọc.")
        }
    }

    fun removeFromReading(storyId: String) {
        viewModelScope.launch {
            container.libraryRepository.removeFromReading(storyId)
            showMessage("Đã xóa truyện khỏi danh sách Đang đọc. Truyện đã tải, dấu trang và lịch sử vẫn được giữ.")
        }
    }

    fun unfollowStory(storyId: String) {
        viewModelScope.launch {
            container.libraryRepository.unfollow(storyId)
            showMessage("Đã bỏ theo dõi truyện.")
        }
    }

    fun deleteBookmark(bookmarkId: String) {
        viewModelScope.launch {
            container.libraryRepository.deleteBookmark(bookmarkId)
            showMessage("Đã xóa đánh dấu.")
        }
    }

    fun openFollowedStory(item: FollowedStoryEntity) {
        viewModelScope.launch { container.libraryRepository.markFollowingSeen(item.storyId) }
        openStory(
            StorySummary(
                id = item.storyId,
                sourceId = item.sourceId,
                title = item.title,
                url = item.remoteUrl,
            ),
        )
    }

    fun openFollowedStoryById(storyId: String) {
        viewModelScope.launch {
            val item = container.libraryRepository.getFollowing(storyId)
            if (item == null) {
                mutableState.update {
                    it.copy(
                        destination = Destination.Root,
                        rootTab = RootTab.LIBRARY,
                        librarySection = LibrarySection.FOLLOWING,
                        message = "Không còn tìm thấy truyện theo dõi này.",
                    )
                }
                return@launch
            }
            openFollowedStory(item)
        }
    }

    fun runSourceUiAction(sourceId: String, actionId: String, surface: SourceUiSurface) {
        val source = container.sourceRegistry.get(sourceId) ?: run {
            showMessage("Không tìm thấy nguồn cho action này.")
            return
        }
        val snapshot = state.value
        val currentUrl = when (surface) {
            SourceUiSurface.EXPLORE -> source.descriptor.baseUrl
            SourceUiSurface.STORY -> snapshot.storyDetail?.story?.url
            SourceUiSurface.READER -> snapshot.chapterContent?.chapter?.url
        }
        val storyId = snapshot.storyDetail?.story?.id ?: snapshot.chapterContent?.chapter?.storyId
        val chapterId = snapshot.chapterContent?.chapter?.id
        viewModelScope.launch {
            when (val result = source.runUiAction(actionId, surface, currentUrl, storyId, chapterId)) {
                is AppResult.Failure -> showMessage(result.message)
                is AppResult.Success -> {
                    result.value.openUrl?.takeIf(String::isNotBlank)?.let(::openExternalUrl)
                    result.value.message.takeIf(String::isNotBlank)?.let(::showMessage)
                    if (result.value.refresh) {
                        when (surface) {
                            SourceUiSurface.EXPLORE -> when (state.value.exploreMode) {
                                ExploreMode.HOME -> browseHome()
                                ExploreMode.CATEGORY -> state.value.activeCategory?.let(::browseCategory) ?: browseHome()
                                ExploreMode.SEARCH -> search()
                            }
                            SourceUiSurface.STORY -> state.value.storyDetail?.story?.let(::openStory)
                            SourceUiSurface.READER -> state.value.chapterContent?.chapter?.let(::openChapter)
                        }
                    }
                }
            }
        }
    }

    fun openExternalUrl(url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank()) {
            showMessage("Địa chỉ nguồn không hợp lệ hoặc không dùng HTTPS.")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { getApplication<Application>().startActivity(intent) }
            .onFailure { showMessage("Không tìm thấy trình duyệt để mở địa chỉ này.") }
    }


    fun setBackupComponents(components: Set<BackupComponent>) {
        val normalized = components.ifEmpty { BackupComponent.entries.toSet() }
        mutableState.update { it.copy(backupComponents = normalized) }
    }

    fun refreshBackupLog(): Boolean {
        val path = container.backupHistoryStore.logPath()
        val text = container.backupHistoryStore.logText()
        mutableState.update { it.copy(backupLogPath = path, backupLogText = text) }
        if (text.isBlank()) showMessage("Chưa có nhật ký sao lưu hoặc khôi phục.")
        return text.isNotBlank()
    }

    fun clearBackupLog() {
        container.backupHistoryStore.clear()
        mutableState.update {
            it.copy(
                backupHistory = emptyList(),
                backupLogPath = container.backupHistoryStore.logPath(),
                backupLogText = "",
                message = "Đã xóa nhật ký sao lưu và khôi phục.",
            )
        }
    }

    fun setBackupComponentEnabled(component: BackupComponent, enabled: Boolean) {
        mutableState.update { current ->
            val next = if (enabled) current.backupComponents + component else current.backupComponents - component
            current.copy(
                backupComponents = next.ifEmpty { setOf(component) },
                message = if (!enabled && current.backupComponents.size == 1) {
                    "Cần chọn ít nhất một nhóm dữ liệu."
                } else current.message,
            )
        }
    }

    fun setVietPhraseMasterEnabled(enabled: Boolean) {
        ReferenceVietPhraseRuntime.setEnabled(getApplication(), enabled)
        mutableState.update { it.copy(vietPhraseEnabled = enabled) }
        showMessage(if (enabled) "Đã bật VietPhrase." else "Đã tắt VietPhrase.")
    }

    fun setVietPhraseFallbackHanViet(enabled: Boolean) {
        ReferenceVietPhraseRuntime.setFallbackHanViet(getApplication(), enabled)
        mutableState.update { it.copy(vietPhraseFallbackHanViet = enabled) }
    }

    fun prepareVietPhraseImport(kind: VietPhraseDictionaryKind?) {
        ReferenceVietPhraseRuntime.prepareImport(kind)
    }

    fun deleteVietPhraseDictionary(kind: VietPhraseDictionaryKind) {
        viewModelScope.launch {
            container.libraryRepository.deleteVietPhraseDictionary(kind)
            showMessage("Đã xóa bộ dữ liệu.")
        }
    }

    fun clearAllVietPhraseDictionaries() {
        viewModelScope.launch {
            container.libraryRepository.clearAllVietPhraseDictionaries()
            showMessage("Đã xóa toàn bộ dữ liệu VietPhrase.")
        }
    }

    fun checkVietPhraseOnlineUpdates() {
        if (state.value.vietPhraseOnlineBusy) return
        viewModelScope.launch {
            mutableState.update { it.copy(vietPhraseOnlineBusy = true, vietPhraseOnlineStatus = "Đang dò nguồn VietPhrase tin cậy…") }
            when (val result = container.vietPhraseOnlineUpdater.checkForUpdates()) {
                is AppResult.Success -> {
                    val status = buildString {
                        append("Tìm thấy ${result.value.candidateKinds} nhóm, ${result.value.totalCandidates} tài nguyên")
                        if (result.value.missingKinds.isNotEmpty()) append("; thiếu ${result.value.missingKinds.joinToString { it.fileName }}")
                        append(if (result.value.updateAvailable) ". Có dữ liệu mới hoặc chưa cài." else ". Chưa phát hiện thay đổi mới.")
                    }
                    mutableState.update { it.copy(vietPhraseOnlineBusy = false, vietPhraseOnlineStatus = status, message = status) }
                }
                is AppResult.Failure -> mutableState.update {
                    it.copy(vietPhraseOnlineBusy = false, vietPhraseOnlineStatus = result.message, message = result.message)
                }
            }
        }
    }

    fun installRecommendedVietPhrase() {
        if (state.value.vietPhraseOnlineBusy) return
        viewModelScope.launch {
            mutableState.update { it.copy(vietPhraseOnlineBusy = true, vietPhraseOnlineStatus = "Đang tải, kiểm tra và nhập VietPhrase…") }
            when (val result = container.vietPhraseOnlineUpdater.installRecommended()) {
                is AppResult.Success -> {
                    val status = "Đã cập nhật ${result.value.importedKinds} bộ với ${result.value.importedRules} mục; snapshot rollback đã được tạo."
                    mutableState.update { it.copy(vietPhraseOnlineBusy = false, vietPhraseOnlineStatus = status, message = status) }
                }
                is AppResult.Failure -> mutableState.update {
                    it.copy(vietPhraseOnlineBusy = false, vietPhraseOnlineStatus = result.message, message = result.message)
                }
            }
        }
    }

    fun exportBackup(destination: Uri) {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, message = "Đang tạo bản sao lưu…") }
            val selected = state.value.backupComponents
            when (val result = container.backupTransferManager.exportTo(destination, selected)) {
                is AppResult.Success -> {
                    val message = "Đã sao lưu ${result.value.components.size} nhóm dữ liệu, ${result.value.stories} truyện và ${result.value.chapters} chương."
                    container.backupHistoryStore.record("BACKUP", true, message, components = result.value.components.map { it.name })
                    mutableState.update { it.copy(loading = false, message = message, backupHistory = container.backupHistoryStore.entries(), backupLogPath = container.backupHistoryStore.logPath(), backupLogText = container.backupHistoryStore.logText()) }
                }
                is AppResult.Failure -> {
                    container.backupHistoryStore.record("BACKUP", false, result.message, result.code, selected.map { it.name })
                    mutableState.update { it.copy(loading = false, message = result.message, backupHistory = container.backupHistoryStore.entries(), backupLogPath = container.backupHistoryStore.logPath(), backupLogText = container.backupHistoryStore.logText()) }
                }
            }
        }
    }

    fun restoreBackup(source: Uri) {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, message = "Đang kiểm tra và khôi phục…") }
            val selected = state.value.backupComponents
            when (val result = container.backupTransferManager.restoreFrom(source, selected)) {
                is AppResult.Success -> {
                    val message = "Đã khôi phục ${result.value.components.size} nhóm dữ liệu, ${result.value.stories} truyện và ${result.value.chapters} chương."
                    container.backupHistoryStore.record("RESTORE", true, message, components = result.value.components.map { it.name })
                    mutableState.update { it.copy(loading = false, message = message, backupHistory = container.backupHistoryStore.entries(), backupLogPath = container.backupHistoryStore.logPath(), backupLogText = container.backupHistoryStore.logText()) }
                }
                is AppResult.Failure -> {
                    container.backupHistoryStore.record("RESTORE", false, result.message, result.code, selected.map { it.name })
                    mutableState.update { it.copy(loading = false, message = result.message, backupHistory = container.backupHistoryStore.entries(), backupLogPath = container.backupHistoryStore.logPath(), backupLogText = container.backupHistoryStore.logText()) }
                }
            }
        }
    }

    fun applyVietPhraseToCurrentChapter() {
        val original = state.value.originalChapterContent ?: state.value.chapterContent ?: return
        viewModelScope.launch {
            val rules = container.libraryRepository.listEnabledVietPhrase(original.chapter.storyId)
            if (rules.isEmpty()) {
                showMessage("Chưa có quy tắc VietPhrase đang bật.")
                return@launch
            }
            mutableState.update { it.copy(aiBusy = true, message = "Đang áp dụng VietPhrase cục bộ…") }
            val engine = withContext(Dispatchers.Default) { VietPhraseEngine(rules) }
            val options = VietPhraseOptions(storyId = original.chapter.storyId, fallbackHanViet = state.value.vietPhraseFallbackHanViet, traceLimit = 0)
            val transformed = withContext(Dispatchers.Default) { original.paragraphs.map { engine.translate(it, options) } }
            val content = original.copy(paragraphs = transformed)
            container.libraryRepository.saveChapterTransform(
                ChapterTransformEntity(
                    id = transformId(original.chapter.id, ChapterAiWorkflow.KIND_VIETPHRASE),
                    storyId = original.chapter.storyId,
                    chapterId = original.chapter.id,
                    kind = ChapterAiWorkflow.KIND_VIETPHRASE,
                    provider = "LOCAL",
                    model = "VIETPHRASE",
                    sourceSha256 = ChapterAiWorkflow.sha256(original.paragraphs),
                    transformedText = ChapterAiWorkflow.serializeParagraphs(transformed),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            showChapterVariant(content, ChapterTextMode.VIETPHRASE, "Đã áp dụng VietPhrase trên thiết bị.")
        }
    }

    fun showOriginalChapter() {
        val original = state.value.originalChapterContent ?: return
        if (state.value.playback.preparationState == PlaybackPreparationState.PREPARING) {
            chapterLoadJob?.cancel()
            aiTranslationJob?.cancel()
        }
        showChapterVariant(original, ChapterTextMode.ORIGINAL, "Đã trở về bản gốc.")
    }

    fun saveStoryAiProfileForCurrentStory(
        mode: String,
        overrideProvider: Boolean,
        provider: AiProvider,
        endpoint: String,
        model: String,
        temperature: Float,
        useCustomPrompts: Boolean,
        translationPrompt: String,
        improvePrompt: String,
        autoRunOnOpen: Boolean,
        useCustomVoiceCastPrompt: Boolean,
        voiceCastPrompt: String,
        voiceCastNote: String,
        voiceCastDialogueOnly: Boolean,
        voiceCastStableNarrator: Boolean,
        expressiveAdjustment: Boolean,
        expressionPrompt: String,
        expressionSpeedLimitPct: Int,
        expressionPitchLimitPct: Int,
        expressionVolumeLimitPct: Int,
    ) {
        val storyId = state.value.storyDetail?.story?.id
            ?: state.value.originalChapterContent?.chapter?.storyId
            ?: return
        viewModelScope.launch {
            container.libraryRepository.saveStoryAiProfile(
                StoryAiProfileEntity(
                    storyId = storyId,
                    mode = mode.uppercase(Locale.ROOT),
                    overrideProvider = overrideProvider,
                    provider = provider.name,
                    endpoint = endpoint,
                    model = model,
                    temperature = temperature.coerceIn(0f, 1f),
                    useCustomPrompts = useCustomPrompts,
                    translationPrompt = translationPrompt,
                    improvePrompt = improvePrompt,
                    autoRunOnOpen = autoRunOnOpen,
                    useCustomVoiceCastPrompt = useCustomVoiceCastPrompt,
                    voiceCastPrompt = voiceCastPrompt,
                    voiceCastNote = voiceCastNote,
                    voiceCastDialogueOnly = voiceCastDialogueOnly,
                    voiceCastStableNarrator = voiceCastStableNarrator,
                    expressiveAdjustment = expressiveAdjustment,
                    expressionPrompt = expressionPrompt,
                    expressionSpeedLimitPct = expressionSpeedLimitPct,
                    expressionPitchLimitPct = expressionPitchLimitPct,
                    expressionVolumeLimitPct = expressionVolumeLimitPct,
                    updatedAt = System.currentTimeMillis(),
                ),
            ).onSuccess { showMessage("Đã lưu cấu hình AI riêng cho truyện.") }
                .onFailure { showMessage(it.message ?: "Không lưu được cấu hình AI theo truyện.") }
        }
    }

    fun clearStoryAiProfileForCurrentStory() {
        val storyId = state.value.storyDetail?.story?.id
            ?: state.value.originalChapterContent?.chapter?.storyId
            ?: return
        viewModelScope.launch {
            container.libraryRepository.deleteStoryAiProfile(storyId)
            showMessage("Đã chuyển truyện về cấu hình AI chung.")
        }
    }

    fun improveVietPhraseForCurrentChapter() {
        val original = state.value.originalChapterContent ?: state.value.chapterContent ?: return
        if (state.value.aiBusy) return
        viewModelScope.launch {
            val rules = container.libraryRepository.listEnabledVietPhrase(original.chapter.storyId)
            if (rules.isEmpty()) {
                showMessage("Chưa có quy tắc VietPhrase đang bật để tạo bản đối chiếu.")
                return@launch
            }
            mutableState.update { it.copy(aiBusy = true, message = "Đang tạo bản VietPhrase và nhờ AI tìm mục cần sửa…") }
            val engine = withContext(Dispatchers.Default) { VietPhraseEngine(rules) }
            val options = VietPhraseOptions(storyId = original.chapter.storyId, fallbackHanViet = state.value.vietPhraseFallbackHanViet, traceLimit = 0)
            val vietPhraseParagraphs = withContext(Dispatchers.Default) {
                original.paragraphs.map { engine.translate(it, options) }
            }
            when (val result = container.aiServices.improveVietPhrase(
                VietPhraseImprovementRequest(
                    storyId = original.chapter.storyId,
                    chapterId = original.chapter.id,
                    chapterTitle = original.chapter.title,
                    sourceText = original.paragraphs.joinToString("\n\n"),
                    vietPhraseText = vietPhraseParagraphs.joinToString("\n\n"),
                ),
            )) {
                is AppResult.Failure -> mutableState.update { it.copy(aiBusy = false, message = result.message) }
                is AppResult.Success -> {
                    var savedCount = 0
                    var rejectedCount = 0
                    result.value.forEach { suggestion ->
                        val context = vietPhraseParagraphs.firstOrNull { suggestion.original in it }
                            ?.take(1_000)
                            .orEmpty()
                        val saved = container.libraryRepository.saveVietPhraseSuggestion(
                            source = suggestion.original,
                            target = suggestion.replacement,
                            reason = listOf(suggestion.type, suggestion.reason).filter(String::isNotBlank).joinToString(" • "),
                            contextText = context,
                            storyId = original.chapter.storyId,
                        )
                        if (saved.isSuccess) savedCount += 1 else rejectedCount += 1
                    }
                    mutableState.update {
                        it.copy(
                            aiBusy = false,
                            message = when {
                                result.value.isEmpty() -> "AI không tìm thấy mục VietPhrase cần sửa."
                                savedCount == 0 -> "Không lưu được đề xuất AIReplace hợp lệ."
                                rejectedCount > 0 -> "Đã đưa $savedCount đề xuất vào hàng chờ; bỏ qua $rejectedCount mục trùng hoặc không hợp lệ."
                                else -> "Đã đưa $savedCount đề xuất vào hàng chờ duyệt AIReplace."
                            },
                        )
                    }
                }
            }
        }
    }

    fun aiTranslate() {
        val original = state.value.originalChapterContent ?: state.value.chapterContent ?: return
        if (state.value.aiBusy) return
        val forceRefresh = state.value.chapterTextMode == ChapterTextMode.AI_TRANSLATION
        aiTranslationJob?.cancel()
        aiTranslationJob = viewModelScope.launch {
            mutableState.update { it.copy(aiBusy = true, message = "Đang chuẩn bị bản dịch AI trước khi phát…") }
            PlaybackQueueStore.setPreparation(PlaybackPreparationState.PREPARING, "Đang chuẩn bị bản dịch AI trước khi phát…")
            when (val result = resolveAiTranslation(original, forceRefresh)) {
                is AppResult.Failure -> {
                    showChapterVariant(
                        original,
                        ChapterTextMode.ORIGINAL,
                        "Dịch AI thất bại: ${result.message}. Đã tiếp tục bằng bản gốc.",
                    )
                }
                is AppResult.Success -> showChapterVariant(
                    result.value,
                    ChapterTextMode.AI_TRANSLATION,
                    "Đã dịch và khóa bản dịch cho playback.",
                )
            }
        }
    }

    private suspend fun resolveAiTranslation(
        original: ChapterContent,
        forceRefresh: Boolean,
    ): AppResult<ChapterContent> {
        val settings = container.settingsRepository.snapshot().aiOnline
        val profile = container.libraryRepository.getStoryAiProfile(original.chapter.storyId)
        val provider = if (profile?.overrideProvider == true) profile.provider else settings.provider.name
        val endpoint = if (profile?.overrideProvider == true && profile.endpoint.isNotBlank()) profile.endpoint else settings.endpoint
        val model = if (profile?.overrideProvider == true && profile.model.isNotBlank()) profile.model else settings.model
        val instruction = if (profile?.useCustomPrompts == true && profile.translationPrompt.isNotBlank()) {
            profile.translationPrompt
        } else settings.translationInstruction
        val requestFingerprint = ChapterAiWorkflow.translationFingerprint(
            original.paragraphs,
            "$provider|$endpoint",
            model,
            instruction,
        )
        val cached = container.libraryRepository.getChapterTransform(original.chapter.id, ChapterAiWorkflow.KIND_AI_TRANSLATION)
        if (!forceRefresh && cached != null && cached.sourceSha256 == requestFingerprint && cached.transformedText.isNotBlank()) {
            val cachedParagraphs = ChapterAiWorkflow.deserializeParagraphs(cached.transformedText)
            if (cachedParagraphs.size == original.paragraphs.size) {
                return AppResult.Success(original.copy(paragraphs = cachedParagraphs))
            }
        }
        return when (val result = container.aiServices.translate(
            TranslationRequest(
                storyId = original.chapter.storyId,
                chapterId = original.chapter.id,
                sourceText = ChapterAiWorkflow.markedParagraphs(original.paragraphs),
                instruction = instruction,
            ),
        )) {
            is AppResult.Failure -> result
            is AppResult.Success -> {
                val translated = runCatching { ChapterAiWorkflow.parseMarkedParagraphs(result.value, original.paragraphs.size) }
                    .getOrElse { return AppResult.Failure("AI_BAD_PARAGRAPHS", it.message ?: "Bản dịch AI không giữ đúng cấu trúc đoạn.", it) }
                container.libraryRepository.saveChapterTransform(
                    ChapterTransformEntity(
                        id = transformId(original.chapter.id, ChapterAiWorkflow.KIND_AI_TRANSLATION),
                        storyId = original.chapter.storyId,
                        chapterId = original.chapter.id,
                        kind = ChapterAiWorkflow.KIND_AI_TRANSLATION,
                        provider = provider,
                        model = model,
                        sourceSha256 = requestFingerprint,
                        transformedText = ChapterAiWorkflow.serializeParagraphs(translated),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                AppResult.Success(original.copy(paragraphs = translated))
            }
        }
    }

    fun voiceCast() = planNarrationForCurrentChapter(includeVoice = true, includeMusic = false)

    fun planSceneMusic() = planNarrationForCurrentChapter(includeVoice = false, includeMusic = true)

    fun planNarration() = planNarrationForCurrentChapter(includeVoice = true, includeMusic = true)

    private fun planNarrationForCurrentChapter(includeVoice: Boolean, includeMusic: Boolean) {
        val original = state.value.originalChapterContent ?: state.value.chapterContent ?: return
        if (state.value.aiBusy) return
        viewModelScope.launch {
            if (includeMusic && container.libraryRepository.listEnabledSceneMusicTracks().isEmpty()) {
                showMessage("Hãy thêm ít nhất một tệp nhạc cảnh đang bật.")
                return@launch
            }
            mutableState.update {
                it.copy(
                    aiBusy = true,
                    message = when {
                        includeVoice && includeMusic -> "AI đang phối hợp phân vai, biểu cảm và nhạc cảnh trong một lượt…"
                        includeVoice -> "AI đang nhận diện người kể chuyện và nhân vật…"
                        else -> "AI đang lập nhạc cảnh với ngữ cảnh liên chương…"
                    },
                )
            }
            val result = container.narrationPlanCoordinator.ensurePlans(
                content = original,
                voice = includeVoice,
                music = includeMusic,
                force = true,
            )
            ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
            mutableState.update {
                it.copy(
                    aiBusy = false,
                    message = buildString {
                        when {
                            result.voicePlanCreated && result.musicPlanCreated -> append("Đã lưu kế hoạch giọng và nhạc cảnh thống nhất.")
                            result.voicePlanCreated -> append("Đã lưu kế hoạch phân vai và biểu cảm.")
                            result.musicPlanCreated -> append("Đã lưu kế hoạch nhạc cảnh có ngữ cảnh liên chương.")
                            else -> append("Không có kế hoạch mới được tạo.")
                        }
                        if (result.usedUnifiedRequest) append(" AI đã phân tích chung trong một yêu cầu.")
                        if (result.warnings.isNotEmpty()) append(" ${result.warnings.joinToString(" • ").take(360)}")
                    },
                )
            }
        }
    }

    private fun showChapterVariant(content: ChapterContent, mode: ChapterTextMode, message: String) {
        val playback = PlaybackQueueStore.state.value
        PlaybackQueueStore.loadContent(
            sourceId = playback.sourceId,
            content = content,
            startIndex = playback.paragraphIndex,
            rate = playback.rate,
            pitch = playback.pitch,
            volume = playback.volume,
            keepPlaying = false,
        )
        mutableState.update { it.copy(chapterContent = content, chapterTextMode = mode, aiBusy = false, message = message) }
        ReaderPlaybackService.command(getApplication(), ReaderPlaybackService.ACTION_REFRESH)
    }

    fun saveAiApiKey(value: String) {
        val provider = state.value.aiOnline.provider
        runCatching { container.aiCredentialStore.saveApiKey(provider, value) }
            .onSuccess { refreshAiCredentialState(); showMessage("Đã mã hóa API key ${providerLabel(provider)} bằng Android Keystore.") }
            .onFailure { showMessage(it.message ?: "Không lưu được API key.") }
    }

    fun clearAiApiKey() {
        val provider = state.value.aiOnline.provider
        container.aiCredentialStore.clearApiKey(provider)
        refreshAiCredentialState()
        showMessage("Đã xóa API key ${providerLabel(provider)} khỏi thiết bị.")
    }

    fun refreshAiCredentialState() {
        val provider = mutableState.value.aiOnline.provider
        mutableState.update {
            it.copy(
                aiHasApiKey = container.aiCredentialStore.hasApiKey(provider),
                aiHasGeminiApiKey = container.aiCredentialStore.hasApiKey(AiProvider.GEMINI),
                aiHasOpenAiApiKey = container.aiCredentialStore.hasApiKey(AiProvider.OPENAI_COMPATIBLE),
            )
        }
    }

    fun setAiProvider(value: AiProvider) {
        viewModelScope.launch {
            container.settingsRepository.setAiProvider(value)
            mutableState.update {
                it.copy(
                    aiHasApiKey = container.aiCredentialStore.hasApiKey(value),
                    aiAvailableModels = emptyList(),
                    aiModelDiscoveryBusy = false,
                )
            }
        }
    }

    fun refreshAiModels(provider: AiProvider, endpoint: String, apiKeyOverride: String) {
        if (state.value.aiModelDiscoveryBusy) return
        viewModelScope.launch {
            mutableState.update { it.copy(aiModelDiscoveryBusy = true, aiAvailableModels = emptyList(), aiModelDiscoveryStatus = "Đang tải danh sách model…", message = "Đang tải danh sách model…") }
            when (val result = container.aiServices.listModels(provider, endpoint, apiKeyOverride.takeIf(String::isNotBlank))) {
                is AppResult.Failure -> mutableState.update {
                    it.copy(aiModelDiscoveryBusy = false, aiAvailableModels = emptyList(), aiModelDiscoveryStatus = result.message, message = result.message)
                }
                is AppResult.Success -> mutableState.update {
                    it.copy(
                        aiModelDiscoveryBusy = false,
                        aiAvailableModels = result.value,
                        aiModelDiscoveryStatus = "Đã tải ${result.value.size} model.",
                        message = "Đã tải ${result.value.size} model.",
                    )
                }
            }
        }
    }

    fun saveReferenceAiSettings(value: AiOnlineSettings, geminiApiKey: String?, openAiApiKey: String?) {
        viewModelScope.launch {
            runCatching {
                container.settingsRepository.saveReferenceAiSettings(value)
                fun saveKey(provider: AiProvider, candidate: String?) {
                    if (candidate == null) return
                    if (candidate.isBlank()) container.aiCredentialStore.clearApiKey(provider)
                    else container.aiCredentialStore.saveApiKey(provider, candidate)
                }
                saveKey(AiProvider.GEMINI, geminiApiKey)
                saveKey(AiProvider.OPENAI_COMPATIBLE, openAiApiKey)
            }.onSuccess {
                refreshAiCredentialState()
                mutableState.update { it.copy(aiAvailableModels = emptyList(), aiModelDiscoveryBusy = false) }
                showMessage("Đã lưu thiết lập AI.")
            }.onFailure { showMessage(it.message ?: "Không lưu được thiết lập AI.") }
        }
    }

    fun refreshGeminiModels() {
        if (state.value.aiModelDiscoveryBusy) return
        viewModelScope.launch {
            mutableState.update { it.copy(aiModelDiscoveryBusy = true, message = "Đang tải danh sách model Gemini…") }
            when (val result = container.aiServices.listGeminiModels()) {
                is AppResult.Failure -> mutableState.update {
                    it.copy(aiModelDiscoveryBusy = false, aiAvailableModels = emptyList(), message = result.message)
                }
                is AppResult.Success -> mutableState.update {
                    it.copy(
                        aiModelDiscoveryBusy = false,
                        aiAvailableModels = result.value,
                        message = "Đã tải ${result.value.size} model Gemini hỗ trợ generateContent.",
                    )
                }
            }
        }
    }

    fun setAiOnlineEnabled(value: Boolean) { viewModelScope.launch { container.settingsRepository.setAiOnlineEnabled(value) } }
    fun setAiConsent(value: Boolean) { viewModelScope.launch { container.settingsRepository.setAiConsent(value) } }
    fun setAiEndpoint(value: String) { viewModelScope.launch { container.settingsRepository.setAiEndpoint(value) } }
    fun setAiModel(value: String) { viewModelScope.launch { container.settingsRepository.setAiModel(value) } }
    fun setAiTemperature(value: Float) { viewModelScope.launch { container.settingsRepository.setAiTemperature(value) } }
    fun setAiTranslationInstruction(value: String) { viewModelScope.launch { container.settingsRepository.setAiTranslationInstruction(value) } }
    fun setAiDailyRequestLimit(value: Int) { viewModelScope.launch { container.settingsRepository.setAiDailyRequestLimit(value) } }
    fun setAiDailyInputCharsLimit(value: Int) { viewModelScope.launch { container.settingsRepository.setAiDailyInputCharsLimit(value) } }
    fun setAiMaxRetries(value: Int) { viewModelScope.launch { container.settingsRepository.setAiMaxRetries(value) } }
    fun setAiRetryBaseDelayMillis(value: Int) { viewModelScope.launch { container.settingsRepository.setAiRetryBaseDelayMillis(value) } }

    fun importVietPhrase(uri: Uri) {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, pendingVietPhraseImport = null, message = "Đang kiểm tra dữ liệu VietPhrase…") }
            when (val result = container.vietPhraseTransferManager.previewFrom(uri)) {
                is AppResult.Success -> mutableState.update {
                    it.copy(
                        loading = false,
                        pendingVietPhraseImport = result.value,
                        message = "Đã kiểm tra ${result.value.incomingCount} mục. Hãy xác nhận trước khi áp dụng.",
                    )
                }
                is AppResult.Failure -> mutableState.update { it.copy(loading = false, message = result.message) }
            }
        }
    }

    fun confirmVietPhraseImport() {
        val preview = state.value.pendingVietPhraseImport ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, message = "Đang lưu snapshot và áp dụng VietPhrase…") }
            when (val result = container.vietPhraseTransferManager.commit(preview)) {
                is AppResult.Success -> mutableState.update {
                    it.copy(
                        loading = false,
                        pendingVietPhraseImport = null,
                        message = "Đã nhập ${preview.plan.after.size} quy tắc; snapshot rollback ${result.value.id} đã được tạo.",
                    )
                }
                is AppResult.Failure -> mutableState.update { it.copy(loading = false, message = result.message) }
            }
        }
    }

    fun cancelVietPhraseImport() {
        mutableState.update { it.copy(pendingVietPhraseImport = null, message = "Đã hủy bản xem trước VietPhrase; dữ liệu chưa thay đổi.") }
    }

    fun rollbackVietPhrase(snapshotId: String) {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, message = "Đang kiểm tra và rollback VietPhrase…") }
            container.libraryRepository.rollbackVietPhraseSnapshot(snapshotId)
                .onSuccess { count -> mutableState.update { it.copy(loading = false, message = "Đã rollback $count quy tắc VietPhrase.") } }
                .onFailure { error -> mutableState.update { it.copy(loading = false, message = error.message ?: "Rollback VietPhrase thất bại.") } }
        }
    }

    fun acceptVietPhraseSuggestion(id: String, target: String) {
        viewModelScope.launch {
            container.libraryRepository.acceptVietPhraseSuggestion(id, target)
                .onSuccess { showMessage("Đã chấp nhận suggestion vào AIReplace.") }
                .onFailure { showMessage(it.message ?: "Không chấp nhận được suggestion.") }
        }
    }

    fun rejectVietPhraseSuggestion(id: String) {
        viewModelScope.launch {
            container.libraryRepository.rejectVietPhraseSuggestion(id)
            showMessage("Đã từ chối suggestion VietPhrase.")
        }
    }

    fun exportVietPhrase(uri: Uri) {
        viewModelScope.launch {
            when (val result = container.vietPhraseTransferManager.exportTo(uri)) {
                is AppResult.Success -> showMessage("Đã xuất ${result.value} quy tắc VietPhrase.")
                is AppResult.Failure -> showMessage(result.message)
            }
        }
    }

    fun addVietPhrase(
        source: String,
        target: String,
        priority: Int,
        kind: VietPhraseDictionaryKind,
        scope: VietPhraseScope,
        storyId: String?,
        ignoreCase: Boolean,
    ) {
        viewModelScope.launch {
            container.libraryRepository.saveVietPhrase(
                source = source,
                target = target,
                priority = priority,
                kind = kind,
                scope = scope,
                storyId = storyId,
                ignoreCase = ignoreCase,
            )
                .onSuccess { showMessage("Đã thêm quy tắc ${kind.name}.") }
                .onFailure { showMessage(it.message ?: "Không thêm được VietPhrase.") }
        }
    }
    fun updateVietPhrase(
        id: Long,
        source: String,
        target: String,
        priority: Int,
        kind: VietPhraseDictionaryKind,
        scope: VietPhraseScope,
        storyId: String?,
        ignoreCase: Boolean,
    ) {
        viewModelScope.launch {
            container.libraryRepository.updateVietPhrase(
                id = id,
                source = source,
                target = target,
                priority = priority,
                kind = kind,
                scope = scope,
                storyId = storyId,
                ignoreCase = ignoreCase,
            )
                .onSuccess { showMessage("Đã cập nhật quy tắc ${kind.name}.") }
                .onFailure { showMessage(it.message ?: "Không cập nhật được VietPhrase.") }
        }
    }
    fun setVietPhraseEnabled(id: Long, enabled: Boolean) { viewModelScope.launch { container.libraryRepository.setVietPhraseEnabled(id, enabled) } }
    fun setVietPhraseDictionaryEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { container.libraryRepository.setVietPhraseDictionaryEnabled(id, enabled) }
    }
    fun deleteVietPhrase(id: Long) { viewModelScope.launch { container.libraryRepository.deleteVietPhrase(id) } }

    fun addSceneMusicTrack(uri: Uri) {
        viewModelScope.launch {
            val title = uri.lastPathSegment?.substringAfterLast('/') ?: "Nhạc cảnh"
            container.libraryRepository.saveSceneMusicTrack(title, uri.toString(), "")
                .onSuccess { trackId ->
                    SceneMusicAnalysisWorker.enqueue(getApplication(), trackId)
                    showMessage("Đã thêm tệp; ứng dụng đang đo loudness cục bộ.")
                }
                .onFailure { showMessage(it.message ?: "Không thêm được nhạc cảnh.") }
        }
    }
    fun updateSceneMusicTrack(id: String, title: String, tagsCsv: String) {
        viewModelScope.launch {
            container.libraryRepository.updateSceneMusicTrackMetadata(id, title, tagsCsv)
                .onSuccess { showMessage("Đã cập nhật tên và tag nhạc cảnh.") }
                .onFailure { showMessage(it.message ?: "Không cập nhật được nhạc cảnh.") }
        }
    }
    fun setSceneMusicTrackEnabled(id: String, enabled: Boolean) { viewModelScope.launch { container.libraryRepository.setSceneMusicTrackEnabled(id, enabled) } }
    fun deleteSceneMusicTrack(id: String) { viewModelScope.launch { container.libraryRepository.deleteSceneMusicTrack(id) } }

    private fun providerLabel(provider: AiProvider): String = when (provider) {
        AiProvider.GEMINI -> "Gemini"
        AiProvider.OPENAI_COMPATIBLE -> "OpenAI-compatible"
    }

    private fun transformId(chapterId: String, kind: String) = UUID.nameUUIDFromBytes("$chapterId\u0000$kind".toByteArray()).toString()
    private fun assignmentId(chapterId: String, paragraphIndex: Int) = UUID.nameUUIDFromBytes("$chapterId\u0000voice\u0000$paragraphIndex".toByteArray()).toString()
    private fun sceneCueId(chapterId: String, paragraphIndex: Int) = UUID.nameUUIDFromBytes("$chapterId\u0000scene\u0000$paragraphIndex".toByteArray()).toString()

    fun back() {
        when (state.value.destination) {
            Destination.Reader -> chapterLoadJob?.cancel()
            Destination.Story -> {
                storyLoadJob?.cancel()
                chapterLoadJob?.cancel()
                commentsLoadJob?.cancel()
            }
            Destination.Root -> Unit
        }
        mutableState.update { current ->
            when (current.destination) {
                Destination.Reader -> current.copy(
                    destination = Destination.Story,
                    loading = false,
                    chapterContent = null,
                    originalChapterContent = null,
                    chapterTextMode = ChapterTextMode.ORIGINAL,
                )
                Destination.Story -> current.copy(
                    destination = Destination.Root,
                    loading = false,
                    storyDetail = null,
                    storyComments = emptyList(),
                    storyCommentsAvailable = false,
                    storyCommentsRefreshable = false,
                    storyCommentsLoading = false,
                    storyCommentsLoaded = false,
                    storyCommentsNextPageUrl = null,
                    storyCommentsFromCache = false,
                    storyCommentsMessage = null,
                )
                Destination.Root -> current.copy(loading = false)
            }
        }
    }

    fun clearMessage() {
        mutableState.update { it.copy(message = null) }
    }

    fun notificationPermissionDenied() {
        showMessage("Cần quyền thông báo để điều khiển TTS nền và báo chương mới.")
    }


    private fun enrichNavigation(content: ChapterContent): ChapterContent {
        val chapters = mutableState.value.storyDetail?.chapters.orEmpty()
        val position = chapters.indexOfFirst {
            it.id == content.chapter.id ||
                (it.url.isNotBlank() && it.url == content.chapter.url)
        }
        if (position < 0) return content
        val resolved = chapters[position]
        return content.copy(
            chapter = content.chapter.copy(
                index = resolved.index,
                title = content.chapter.title.ifBlank { resolved.title },
                url = content.chapter.url.ifBlank { resolved.url },
            ),
            previousChapterUrl = content.previousChapterUrl
                ?: chapters.getOrNull(position - 1)?.url?.takeIf(String::isNotBlank),
            nextChapterUrl = content.nextChapterUrl
                ?: chapters.getOrNull(position + 1)?.url?.takeIf(String::isNotBlank),
        )
    }

    private fun formatBytesForMessage(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.1f KiB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun showMessage(message: String) {
        mutableState.update { it.copy(message = message) }
    }
}
