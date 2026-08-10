package vn.nghetruyen.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vn.nghetruyen.app.audio.AudioExportRequest
import vn.nghetruyen.app.ui.screens.ExploreScreen
import vn.nghetruyen.app.ui.screens.LibraryScreen
import vn.nghetruyen.app.ui.screens.PersonalScreen
import vn.nghetruyen.app.ui.screens.ReaderScreen
import vn.nghetruyen.app.ui.screens.StoryDetailScreen
import vn.nghetruyen.app.ui.components.ReferenceDivider
import vn.nghetruyen.app.ui.components.ReferenceScreenBackground
import vn.nghetruyen.app.sources.SourceUiSurface
import vn.nghetruyen.app.ui.components.ReferenceTabButton

@Composable
fun NgheTruyenApp(
    viewModel: AppViewModel,
    onImportFile: () -> Unit,
    onExportBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onImportVietPhrase: () -> Unit,
    onExportVietPhrase: () -> Unit,
    onExportAudio: (AudioExportRequest) -> Unit,
    onSelectBackgroundMusic: () -> Unit,
    onSelectSceneMusic: () -> Unit,
    onInstallSourcePack: () -> Unit,
    onExportSourcePack: (String, String) -> Unit,
    onImportSourceTrustRotation: () -> Unit,
    onExportSourceDiagnostics: () -> Unit,
    onTogglePlayback: () -> Unit,
    onFollowingUpdatesChange: (Boolean) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler(enabled = state.destination != Destination.Root) {
        viewModel.back()
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (state.destination == Destination.Root) {
                PrimaryBottomBar(selected = state.rootTab, onSelect = viewModel::setRootTab)
            }
        },
        containerColor = ReferenceScreenBackground,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (state.destination) {
                Destination.Root -> when (state.rootTab) {
                    RootTab.EXPLORE -> ExploreScreen(
                        state = state,
                        onQueryChange = viewModel::updateQuery,
                        onSearch = { viewModel.search() },
                        onSearchAllSourcesChange = viewModel::setSearchAllSources,
                        onSortModeChange = viewModel::setSearchSortMode,
                        onCancelSearch = viewModel::cancelSearch,
                        onSourceSelected = viewModel::selectSource,
                        onHomeSelected = viewModel::browseHome,
                        onCategorySelected = viewModel::browseCategory,
                        onSuggestionSelected = viewModel::selectSearchSuggestion,
                        onLoadMore = viewModel::loadMoreStories,
                        onStoryClick = viewModel::openStory,
                        onOpenSourceLogin = viewModel::openSourceLogin,
                        onCheckSource = viewModel::checkSource,
                        onSourceUiAction = { sourceId, actionId -> viewModel.runSourceUiAction(sourceId, actionId, SourceUiSurface.EXPLORE) },
                    )
                    RootTab.LIBRARY -> LibraryScreen(
                        state = state,
                        onSectionSelected = viewModel::setLibrarySection,
                        onImportFile = onImportFile,
                        onStoryClick = viewModel::openLibraryStory,
                        onPauseDownload = viewModel::pauseDownload,
                        onResumeDownload = viewModel::resumeDownload,
                        onRetryDownload = viewModel::retryDownload,
                        onPrioritizeDownload = viewModel::prioritizeDownload,
                        onRetryFailedChapter = viewModel::retryFailedChapter,
                        onCancelDownload = viewModel::cancelDownload,
                        onRemoveOffline = viewModel::removeOfflineStory,
                        onCheckFollowing = viewModel::checkFollowingNow,
                        onBookmarkClick = viewModel::openBookmark,
                        onDeleteBookmark = viewModel::deleteBookmark,
                        onNoteClick = viewModel::openNote,
                        onDeleteNote = viewModel::deleteNote,
                        onHistoryClick = viewModel::openReadingHistory,
                        onClearReadingHistory = viewModel::clearReadingHistory,
                        onFollowingClick = viewModel::openFollowedStory,
                    )
                    RootTab.PERSONAL -> PersonalScreen(
                        state = state,
                        onRateChange = viewModel::setTtsRate,
                        onPitchChange = viewModel::setTtsPitch,
                        onVolumeChange = viewModel::setTtsVolume,
                        onAutoNextChange = viewModel::setAutoPlayNextChapter,
                        onEngineSelected = viewModel::selectTtsEngine,
                        onVoiceSelected = viewModel::selectTtsVoice,
                        onRefreshVoices = viewModel::refreshTtsVoices,
                        onPreviewVoice = viewModel::previewTtsVoice,
                        onOpenTtsSettings = viewModel::openTtsSettings,
                        onInterruptionModeChange = viewModel::setAudioInterruptionMode,
                        onDiagnosticsModeChange = viewModel::setDiagnosticsMode,
                        onHeadsetMultiClickChange = viewModel::setHeadsetMultiClickEnabled,
                        onHeadsetSingleActionChange = viewModel::setHeadsetSingleClickAction,
                        onHeadsetDoubleActionChange = viewModel::setHeadsetDoubleClickAction,
                        onHeadsetTripleActionChange = viewModel::setHeadsetTripleClickAction,
                        onHeadsetLongActionChange = viewModel::setHeadsetLongPressAction,
                        onPauseOnHeadsetDisconnectChange = viewModel::setPauseOnHeadsetDisconnect,
                        onRestorePlaybackChange = viewModel::setRestorePlaybackAfterProcessDeath,
                        onAutoVoiceCastChange = viewModel::setAutoVoiceCastEnabled,
                        onSaveGlobalVoiceRole = viewModel::saveGlobalVoiceRole,
                        onGlobalVoiceRoleEnabledChange = viewModel::setGlobalVoiceRoleEnabled,
                        onDeleteGlobalVoiceRole = viewModel::deleteGlobalVoiceRole,
                        onRestoreGlobalVoiceProfiles = viewModel::restoreGlobalVoiceProfiles,
                        onPreviewGlobalVoiceRole = viewModel::previewVoiceRole,
                        onAutoSceneMusicChange = viewModel::setAutoSceneMusicEnabled,
                        onPrefetchNarrationPlansChange = viewModel::setPrefetchNarrationPlansEnabled,
                        onNarrationPrefetchWindowChange = viewModel::setNarrationPrefetchWindowChapters,
                        onSceneMusicCrossfadeChange = viewModel::setSceneMusicCrossfadeMillis,
                        onSceneMusicContinueChange = viewModel::setSceneMusicContinueAcrossChapters,
                        onSceneMusicPlaybackModeChange = viewModel::setSceneMusicPlaybackMode,
                        onSceneMusicTargetLufsChange = viewModel::setSceneMusicTargetLufs,
                        onSceneMusicAvoidRepeatWindowChange = viewModel::setSceneMusicAvoidRepeatWindow,
                        onSonicProcessingEnabledChange = viewModel::setSonicProcessingEnabled,
                        onSonicDefaultSpeedChange = viewModel::setSonicDefaultSpeed,
                        onSonicDefaultPitchChange = viewModel::setSonicDefaultPitch,
                        onTtsCacheEnabledChange = viewModel::setTtsCacheEnabled,
                        onTtsCacheLimitChange = viewModel::setTtsCacheLimitMiB,
                        onNormalizeTtsVolumeChange = viewModel::setNormalizeTtsVolumeEnabled,
                        onTtsTargetLufsChange = viewModel::setTtsTargetLufs,
                        onSelectBackgroundMusic = onSelectBackgroundMusic,
                        onClearBackgroundMusic = { viewModel.setBackgroundMusic(null) },
                        onBackgroundMusicEnabledChange = viewModel::setBackgroundMusicEnabled,
                        onBackgroundMusicVolumeChange = viewModel::setBackgroundMusicVolume,
                        onBackgroundMusicDuckChange = viewModel::setBackgroundMusicDuckFactor,
                        onAddPronunciation = viewModel::addPronunciation,
                        onUpdatePronunciation = viewModel::updatePronunciation,
                        onPronunciationEnabledChange = viewModel::setPronunciationEnabled,
                        onDeletePronunciation = viewModel::deletePronunciation,
                        onAddVietPhrase = viewModel::addVietPhrase,
                        onUpdateVietPhrase = viewModel::updateVietPhrase,
                        onImportVietPhrase = onImportVietPhrase,
                        onExportVietPhrase = onExportVietPhrase,
                        onCheckVietPhraseOnline = viewModel::checkVietPhraseOnlineUpdates,
                        onInstallRecommendedVietPhrase = viewModel::installRecommendedVietPhrase,
                        onVietPhraseEnabledChange = viewModel::setVietPhraseEnabled,
                        onVietPhraseDictionaryEnabledChange = viewModel::setVietPhraseDictionaryEnabled,
                        onDeleteVietPhrase = viewModel::deleteVietPhrase,
                        onConfirmVietPhraseImport = viewModel::confirmVietPhraseImport,
                        onCancelVietPhraseImport = viewModel::cancelVietPhraseImport,
                        onRollbackVietPhrase = viewModel::rollbackVietPhrase,
                        onAcceptVietPhraseSuggestion = viewModel::acceptVietPhraseSuggestion,
                        onRejectVietPhraseSuggestion = viewModel::rejectVietPhraseSuggestion,
                        onPrepareVietPhraseImport = viewModel::prepareVietPhraseImport,
                        onDeleteVietPhraseDictionary = viewModel::deleteVietPhraseDictionary,
                        onClearAllVietPhrase = viewModel::clearAllVietPhraseDictionaries,
                        onVietPhraseMasterEnabledChange = viewModel::setVietPhraseMasterEnabled,
                        onVietPhraseFallbackChange = viewModel::setVietPhraseFallbackHanViet,
                        onRefreshAiModels = viewModel::refreshAiModels,
                        onSaveAiSettings = viewModel::saveReferenceAiSettings,
                        onSelectSceneMusic = onSelectSceneMusic,
                        onUpdateSceneMusic = viewModel::updateSceneMusicTrack,
                        onSceneMusicEnabledChange = viewModel::setSceneMusicTrackEnabled,
                        onDeleteSceneMusic = viewModel::deleteSceneMusicTrack,
                        onFollowingUpdatesChange = onFollowingUpdatesChange,
                        onCheckFollowingNow = viewModel::checkFollowingNow,
                        onCacheLimitChange = viewModel::setReaderCacheLimitMiB,
                        onTrimReaderCache = viewModel::trimReaderCacheNow,
                        onClearReaderCache = viewModel::clearReaderCache,
                        onCancelAudioExport = viewModel::cancelAudioExport,
                        onResumeAudioExport = viewModel::resumeAudioExport,
                        onOpenAudioExport = viewModel::openAudioExport,
                        onRunPerformanceDiagnostics = viewModel::runPerformanceDiagnostics,
                        onBackupComponentChange = viewModel::setBackupComponentEnabled,
                        onBackupComponentsChange = viewModel::setBackupComponents,
                        onRefreshBackupLog = viewModel::refreshBackupLog,
                        onClearBackupLog = viewModel::clearBackupLog,
                        onExportBackup = onExportBackup,
                        onRestoreBackup = onRestoreBackup,
                        onClearDownloadedStories = viewModel::clearAllDownloadedStories,
                        onFactoryResetApplication = viewModel::factoryResetApplication,
                        onInstallSourcePack = onInstallSourcePack,
                        onImportSourceTrustRotation = onImportSourceTrustRotation,
                        onRefreshSourceRepository = viewModel::refreshSourceRepository,
                        onRemoveSourceRepository = viewModel::removeSourceRepository,
                        onPrepareRepositorySourceInstall = viewModel::prepareRepositorySourceInstall,
                        onConfirmSourcePackInstall = viewModel::confirmSourcePackInstall,
                        onCancelSourcePackInstall = viewModel::cancelSourcePackInstall,
                        onSourcePackEnabledChange = viewModel::setSourcePackEnabled,
                        onRollbackSourcePack = viewModel::rollbackSourcePack,
                        onUpdateSourcePack = viewModel::updateSourcePack,
                        onExportSourcePack = onExportSourcePack,
                        onRemoveSourcePack = viewModel::removeSourcePack,
                        onCheckSourcePack = viewModel::checkSourcePack,
                        onSaveSourceConfig = viewModel::saveSourceConfig,
                        onResetSourceConfig = viewModel::resetSourceConfig,
                        onEnrollSourceTrustKey = viewModel::enrollSourceTrustKey,
                        onRevokeSourceTrustKey = viewModel::revokeSourceTrustKey,
                        onInspectSourceSelector = viewModel::inspectSourceSelector,
                        onExportSourceDiagnostics = onExportSourceDiagnostics,
                        onClearSourceDiagnostics = viewModel::clearSourceDiagnostics,
                        onCheckSource = viewModel::checkSource,
                        onCheckAllSources = viewModel::checkAllSources,
                        onOpenSourceLogin = viewModel::openSourceLogin,
                        onOpenSourceDiagnosticBrowser = viewModel::openSourceDiagnosticBrowser,
                        onClearSourceSession = viewModel::clearSourceSession,
                    )
                }
                Destination.Story -> StoryDetailScreen(
                    state = state,
                    onBack = viewModel::back,
                    onReadFirst = viewModel::readFirst,
                    onDownload = viewModel::downloadCurrentStory,
                    onDownloadUnread = viewModel::downloadUnreadChapters,
                    onDownloadRange = viewModel::downloadChapterRange,
                    onDownloadSelected = viewModel::downloadSelectedChapters,
                    onToggleFollowing = viewModel::toggleFollowing,
                    onToggleStoryBookmark = viewModel::toggleStoryBookmark,
                    onGenreSelected = viewModel::openStoryGenre,
                    onTabSelected = viewModel::setStoryDetailTab,
                    onChapterSortDescendingChange = viewModel::setChapterSortDescending,
                    onConsumeAdvancedOptionsRequest = viewModel::consumeStoryAdvancedOptionsRequest,
                    onExportAudio = onExportAudio,
                    onSaveVoiceProfile = viewModel::saveVoiceProfileForCurrentStory,
                    onClearVoiceProfile = viewModel::clearVoiceProfileForCurrentStory,
                    onSaveVoiceRole = viewModel::saveVoiceRoleForCurrentStory,
                    onPreviewVoiceRole = viewModel::previewVoiceRole,
                    onLoadRoleVoices = viewModel::loadRoleEditorVoices,
                    onVoiceRoleEnabledChange = viewModel::setVoiceRoleEnabled,
                    onDeleteVoiceRole = viewModel::deleteVoiceRole,
                    onSaveAiProfile = viewModel::saveStoryAiProfileForCurrentStory,
                    onClearAiProfile = viewModel::clearStoryAiProfileForCurrentStory,
                    onChapterClick = viewModel::openChapter,
                    onLoadMoreChapters = viewModel::loadMoreChapters,
                    onLoadAllChapters = viewModel::loadAllChapters,
                    onLoadComments = viewModel::loadStoryComments,
                    onLoadMoreComments = viewModel::loadMoreStoryComments,
                    onOpenOriginal = viewModel::openExternalUrl,
                    onCheckSource = viewModel::checkSource,
                    onOpenSourceLogin = viewModel::openSourceLogin,
                    onSourceUiAction = { sourceId, actionId -> viewModel.runSourceUiAction(sourceId, actionId, SourceUiSurface.STORY) },
                )
                Destination.Reader -> ReaderScreen(
                    state = state,
                    onBack = viewModel::back,
                    onBackToChapters = viewModel::backToChapterList,
                    onPreviousChapter = viewModel::previousChapter,
                    onNextChapter = viewModel::nextChapter,
                    onRewind = { viewModel.moveParagraph(-1) },
                    onForward = { viewModel.moveParagraph(1) },
                    onTogglePlayback = onTogglePlayback,
                    onReaderModeChange = viewModel::setReaderMode,
                    onSaveReadingPosition = viewModel::saveReadingPositionNow,
                    onSleepTimer = viewModel::setSleepTimer,
                    onSleepTimerByChapters = viewModel::setSleepTimerByChapters,
                    onBookmark = viewModel::bookmarkCurrent,
                    onExportAudio = onExportAudio,
                    onSaveVoiceProfile = viewModel::saveVoiceProfileForCurrentStory,
                    onClearVoiceProfile = viewModel::clearVoiceProfileForCurrentStory,
                    onThemeChange = viewModel::setReaderTheme,
                    onLayoutModeChange = viewModel::setReaderLayoutMode,
                    onFontSizeChange = viewModel::setReaderFontSizeSp,
                    onLineHeightChange = viewModel::setReaderLineHeightPercent,
                    onHorizontalPaddingChange = viewModel::setReaderHorizontalPaddingDp,
                    onParagraphSpacingChange = viewModel::setReaderParagraphSpacingDp,
                    onKeepScreenOnChange = viewModel::setReaderKeepScreenOn,
                    onVolumeKeysNavigateChange = viewModel::setReaderVolumeKeysNavigate,
                    onParagraphSelected = viewModel::moveToParagraph,
                    onSaveNote = viewModel::saveCurrentNote,
                    onDeleteNote = viewModel::deleteNote,
                    onApplyVietPhrase = viewModel::applyVietPhraseToCurrentChapter,
                    onImproveVietPhrase = viewModel::improveVietPhraseForCurrentChapter,
                    onAiTranslate = viewModel::aiTranslate,
                    onShowOriginal = viewModel::showOriginalChapter,
                    onVoiceCast = viewModel::voiceCast,
                    onPlanSceneMusic = viewModel::planSceneMusic,
                    onPlanNarration = viewModel::planNarration,
                    onOpenStoryAiOptions = viewModel::openStoryAiOptions,
                    onOpenStoryVoiceCastOptions = viewModel::openStoryVoiceCastOptions,
                    onEngineSelected = viewModel::selectTtsEngine,
                    onVoiceSelected = viewModel::selectTtsVoice,
                    onRefreshVoices = viewModel::refreshTtsVoices,
                    onPreviewVoice = viewModel::previewTtsVoice,
                    onRateChange = viewModel::setTtsRate,
                    onPitchChange = viewModel::setTtsPitch,
                    onVolumeChange = viewModel::setTtsVolume,
                    onSonicProcessingEnabledChange = viewModel::setSonicProcessingEnabled,
                    onOpenTtsSettings = viewModel::openTtsSettings,
                    onSelectBackgroundMusic = onSelectBackgroundMusic,
                    onClearBackgroundMusic = { viewModel.setBackgroundMusic(null) },
                    onBackgroundMusicEnabledChange = viewModel::setBackgroundMusicEnabled,
                    onBackgroundMusicVolumeChange = viewModel::setBackgroundMusicVolume,
                    onBackgroundMusicDuckChange = viewModel::setBackgroundMusicDuckFactor,
                    onAutoSceneMusicChange = viewModel::setAutoSceneMusicEnabled,
                    onSceneMusicPlaybackModeChange = viewModel::setSceneMusicPlaybackMode,
                    onSceneMusicTargetLufsChange = viewModel::setSceneMusicTargetLufs,
                    onSelectSceneMusic = onSelectSceneMusic,
                    onOpenSourceLogin = viewModel::openSourceLogin,
                    onCheckSource = viewModel::checkSource,
                    onSourceUiAction = { sourceId, actionId -> viewModel.runSourceUiAction(sourceId, actionId, SourceUiSurface.READER) },
                    onMessage = viewModel::readerActionMessage,
                )
            }
        }
    }
}

@Composable
private fun PrimaryBottomBar(
    selected: RootTab,
    onSelect: (RootTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ReferenceDivider)
            .padding(2.dp),
    ) {
        listOf(
            RootTab.EXPLORE to "KHÁM PHÁ",
            RootTab.LIBRARY to "TỦ TRUYỆN",
            RootTab.PERSONAL to "CÁ NHÂN",
        ).forEach { (tab, label) ->
            ReferenceTabButton(
                text = label,
                selected = selected == tab,
                onClick = { onSelect(tab) },
                accessibilityLabel = "Tab ${label.lowercase()}",
                modifier = Modifier
                    .weight(1f)
                    .padding(1.dp),
            )
        }
    }
}
