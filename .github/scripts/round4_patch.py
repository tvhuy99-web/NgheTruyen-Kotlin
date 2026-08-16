from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


policy = Path("app/src/main/java/vn/nghetruyen/app/ui/RoomObserverDemand.kt")
if policy.exists():
    raise SystemExit(f"{policy}: file already exists")
policy.write_text(
    '''package vn.nghetruyen.app.ui

internal enum class RoomObserverGroup {
    READING,
    HISTORY,
    OFFLINE,
    BOOKMARKS,
    NOTES,
    FOLLOWING,
    DOWNLOADS,
    STORAGE,
    PRONUNCIATIONS,
    VIETPHRASE,
    AI_PROFILES,
    SCENE_MUSIC,
    TTS_PROFILES,
    VOICE_ROLES,
    AUDIO_EXPORTS,
}

internal fun roomObserverGroupsForUi(
    destination: Destination,
    rootTab: RootTab,
    librarySection: LibrarySection,
): Set<RoomObserverGroup> = when (destination) {
    Destination.Root -> when (rootTab) {
        RootTab.EXPLORE, RootTab.PERSONAL -> emptySet()
        RootTab.LIBRARY -> when (librarySection) {
            LibrarySection.READING -> setOf(
                RoomObserverGroup.READING,
                RoomObserverGroup.HISTORY,
                RoomObserverGroup.DOWNLOADS,
            )
            LibrarySection.DOWNLOADED -> setOf(
                RoomObserverGroup.OFFLINE,
                RoomObserverGroup.STORAGE,
            )
            LibrarySection.BOOKMARKS -> setOf(RoomObserverGroup.BOOKMARKS)
            LibrarySection.NOTES -> setOf(RoomObserverGroup.NOTES)
            LibrarySection.FOLLOWING -> setOf(RoomObserverGroup.FOLLOWING)
        }
    }
    Destination.Story -> setOf(
        RoomObserverGroup.BOOKMARKS,
        RoomObserverGroup.FOLLOWING,
        RoomObserverGroup.AI_PROFILES,
        RoomObserverGroup.TTS_PROFILES,
        RoomObserverGroup.VOICE_ROLES,
    )
    Destination.Reader -> setOf(
        RoomObserverGroup.NOTES,
        RoomObserverGroup.AI_PROFILES,
        RoomObserverGroup.SCENE_MUSIC,
        RoomObserverGroup.TTS_PROFILES,
        RoomObserverGroup.VOICE_ROLES,
    )
}

internal fun roomObserverGroupsForPersonalPage(page: String): Set<RoomObserverGroup> = when (page) {
    "settings_pronunciation" -> setOf(RoomObserverGroup.PRONUNCIATIONS)
    "settings_vietphrase" -> setOf(RoomObserverGroup.VIETPHRASE)
    "settings_automation" -> setOf(RoomObserverGroup.VOICE_ROLES)
    "settings_music" -> setOf(RoomObserverGroup.SCENE_MUSIC)
    "settings_following" -> setOf(RoomObserverGroup.FOLLOWING)
    "settings_storage" -> setOf(RoomObserverGroup.OFFLINE, RoomObserverGroup.STORAGE)
    "settings_export" -> setOf(RoomObserverGroup.AUDIO_EXPORTS)
    else -> emptySet()
}
''',
    encoding="utf-8",
)


test = Path("app/src/test/java/vn/nghetruyen/app/ui/RoomObserverDemandTest.kt")
if test.exists():
    raise SystemExit(f"{test}: file already exists")
test.parent.mkdir(parents=True, exist_ok=True)
test.write_text(
    '''package vn.nghetruyen.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomObserverDemandTest {
    @Test
    fun exploreStartsNoRoomObservers() {
        assertTrue(
            roomObserverGroupsForUi(
                Destination.Root,
                RootTab.EXPLORE,
                LibrarySection.READING,
            ).isEmpty(),
        )
    }

    @Test
    fun libraryStartsOnlyCurrentSectionData() {
        assertEquals(
            setOf(RoomObserverGroup.READING, RoomObserverGroup.HISTORY, RoomObserverGroup.DOWNLOADS),
            roomObserverGroupsForUi(Destination.Root, RootTab.LIBRARY, LibrarySection.READING),
        )
        assertEquals(
            setOf(RoomObserverGroup.OFFLINE, RoomObserverGroup.STORAGE),
            roomObserverGroupsForUi(Destination.Root, RootTab.LIBRARY, LibrarySection.DOWNLOADED),
        )
        assertEquals(
            setOf(RoomObserverGroup.BOOKMARKS),
            roomObserverGroupsForUi(Destination.Root, RootTab.LIBRARY, LibrarySection.BOOKMARKS),
        )
        assertEquals(
            setOf(RoomObserverGroup.NOTES),
            roomObserverGroupsForUi(Destination.Root, RootTab.LIBRARY, LibrarySection.NOTES),
        )
        assertEquals(
            setOf(RoomObserverGroup.FOLLOWING),
            roomObserverGroupsForUi(Destination.Root, RootTab.LIBRARY, LibrarySection.FOLLOWING),
        )
    }

    @Test
    fun storyAndReaderDemandOnlyTheirFeatureData() {
        assertEquals(
            setOf(
                RoomObserverGroup.BOOKMARKS,
                RoomObserverGroup.FOLLOWING,
                RoomObserverGroup.AI_PROFILES,
                RoomObserverGroup.TTS_PROFILES,
                RoomObserverGroup.VOICE_ROLES,
            ),
            roomObserverGroupsForUi(Destination.Story, RootTab.EXPLORE, LibrarySection.READING),
        )
        assertEquals(
            setOf(
                RoomObserverGroup.NOTES,
                RoomObserverGroup.AI_PROFILES,
                RoomObserverGroup.SCENE_MUSIC,
                RoomObserverGroup.TTS_PROFILES,
                RoomObserverGroup.VOICE_ROLES,
            ),
            roomObserverGroupsForUi(Destination.Reader, RootTab.EXPLORE, LibrarySection.READING),
        )
    }

    @Test
    fun personalSettingsLoadRoomDataOnDemand() {
        assertTrue(roomObserverGroupsForPersonalPage("home").isEmpty())
        assertEquals(
            setOf(RoomObserverGroup.PRONUNCIATIONS),
            roomObserverGroupsForPersonalPage("settings_pronunciation"),
        )
        assertEquals(
            setOf(RoomObserverGroup.VIETPHRASE),
            roomObserverGroupsForPersonalPage("settings_vietphrase"),
        )
        assertEquals(
            setOf(RoomObserverGroup.OFFLINE, RoomObserverGroup.STORAGE),
            roomObserverGroupsForPersonalPage("settings_storage"),
        )
        assertEquals(
            setOf(RoomObserverGroup.AUDIO_EXPORTS),
            roomObserverGroupsForPersonalPage("settings_export"),
        )
    }
}
''',
    encoding="utf-8",
)


viewmodel = "app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt"
replace_once(viewmodel, "        observeLibrary()\n", "")
replace_once(
    viewmodel,
    '    private var chapterSleepRemaining: Int? = null\n    private var chapterSleepLastChapterId: String = ""\n',
    '    private var chapterSleepRemaining: Int? = null\n    private var chapterSleepLastChapterId: String = ""\n    private val startedRoomObserverGroups = mutableSetOf<RoomObserverGroup>()\n',
)

vm_file = Path(viewmodel)
vm_text = vm_file.read_text(encoding="utf-8")
start_marker = "    private fun observeLibrary() {"
end_marker = "\n    private fun observePlayback() {"
if vm_text.count(start_marker) != 1 or vm_text.count(end_marker) != 1:
    raise SystemExit("AppViewModel.kt: observer block markers are not unique")
start = vm_text.index(start_marker)
end = vm_text.index(end_marker, start)
replacement = '''    fun ensureRoomObserversForUi(
        destination: Destination,
        rootTab: RootTab,
        librarySection: LibrarySection,
    ) {
        ensureRoomObserverGroups(roomObserverGroupsForUi(destination, rootTab, librarySection))
    }

    fun ensureRoomObserversForPersonalPage(page: String) {
        ensureRoomObserverGroups(roomObserverGroupsForPersonalPage(page))
    }

    private fun ensureRoomObserverGroups(groups: Set<RoomObserverGroup>) {
        groups.forEach(::ensureRoomObserverGroup)
    }

    private fun ensureRoomObserverGroup(group: RoomObserverGroup) {
        val shouldStart = synchronized(startedRoomObserverGroups) {
            startedRoomObserverGroups.add(group)
        }
        if (!shouldStart) return

        when (group) {
            RoomObserverGroup.READING -> {
                viewModelScope.launch {
                    container.libraryRepository.observeReading()
                        .distinctUntilChanged()
                        .collect { items -> mutableState.update { it.copy(readingStories = items) } }
                }
                viewModelScope.launch {
                    container.libraryRepository.observeReadingProgressWithChapterTitle()
                        .distinctUntilChanged()
                        .collect { items ->
                            val progressByStory = items.associate { item ->
                                item.storyId to ReadingProgressEntity(
                                    storyId = item.storyId,
                                    chapterId = item.chapterId,
                                    paragraphIndex = item.paragraphIndex,
                                    totalParagraphs = item.totalParagraphs,
                                    updatedAt = item.updatedAt,
                                )
                            }
                            mutableState.update {
                                it.copy(
                                    readingProgress = progressByStory,
                                    readingChapterTitles = items.associate { item -> item.storyId to item.chapterTitle },
                                )
                            }
                        }
                }
            }

            RoomObserverGroup.HISTORY -> viewModelScope.launch {
                container.libraryRepository.observeReadingHistory()
                    .distinctUntilChanged()
                    .collect { items -> mutableState.update { it.copy(readingHistory = items) } }
            }

            RoomObserverGroup.OFFLINE -> viewModelScope.launch {
                container.libraryRepository.observeOffline()
                    .distinctUntilChanged()
                    .collect { items -> mutableState.update { it.copy(downloadedStories = items) } }
            }

            RoomObserverGroup.BOOKMARKS -> viewModelScope.launch {
                container.libraryRepository.observeBookmarks()
                    .distinctUntilChanged()
                    .collect { items -> mutableState.update { it.copy(bookmarks = items) } }
            }

            RoomObserverGroup.NOTES -> viewModelScope.launch {
                container.libraryRepository.observeNotes()
                    .distinctUntilChanged()
                    .collect { items -> mutableState.update { it.copy(notes = items) } }
            }

            RoomObserverGroup.FOLLOWING -> viewModelScope.launch {
                container.libraryRepository.observeFollowing()
                    .distinctUntilChanged()
                    .collect { items -> mutableState.update { it.copy(following = items) } }
            }

            RoomObserverGroup.DOWNLOADS -> {
                viewModelScope.launch {
                    container.libraryRepository.observeDownloads()
                        .distinctUntilChanged()
                        .collect { items -> mutableState.update { it.copy(downloads = items) } }
                }
                viewModelScope.launch {
                    container.libraryRepository.observeDownloadFailures()
                        .distinctUntilChanged()
                        .collect { items -> mutableState.update { it.copy(downloadFailures = items) } }
                }
            }

            RoomObserverGroup.STORAGE -> viewModelScope.launch {
                container.libraryRepository.observeChapterStorageSnapshot()
                    .distinctUntilChanged()
                    .collect { rows ->
                        val downloaded = rows.filter { it.downloadedAt != null }
                        val cached = rows.filter { it.downloadedAt == null }
                        val offlineStorage = downloaded
                            .groupBy { it.storyId }
                            .mapValues { (storyId, chapters) ->
                                OfflineStoryStorage(
                                    storyId = storyId,
                                    chapterCount = chapters.size,
                                    bytes = chapters.sumOf { it.bytes },
                                )
                            }
                        mutableState.update {
                            it.copy(
                                offlineStorage = offlineStorage,
                                downloadedChapterIds = downloaded.mapTo(linkedSetOf()) { row -> row.chapterId },
                                storageUsage = StorageUsage(
                                    downloadedChapters = downloaded.size,
                                    downloadedBytes = downloaded.sumOf { it.bytes },
                                    cachedChapters = cached.size,
                                    cachedBytes = cached.sumOf { it.bytes },
                                ),
                            )
                        }
                    }
            }

            RoomObserverGroup.PRONUNCIATIONS -> viewModelScope.launch {
                container.libraryRepository.observePronunciations()
                    .distinctUntilChanged()
                    .collect { rules -> mutableState.update { it.copy(pronunciations = rules) } }
            }

            RoomObserverGroup.VIETPHRASE -> {
                viewModelScope.launch {
                    container.libraryRepository.observeVietPhraseRules()
                        .distinctUntilChanged()
                        .collect { rules -> mutableState.update { it.copy(vietPhraseRules = rules) } }
                }
                viewModelScope.launch {
                    container.libraryRepository.observeVietPhraseSnapshots()
                        .distinctUntilChanged()
                        .collect { snapshots -> mutableState.update { it.copy(vietPhraseSnapshots = snapshots) } }
                }
                viewModelScope.launch {
                    container.libraryRepository.observeVietPhraseDictionaryStates()
                        .distinctUntilChanged()
                        .collect { dictionaries -> mutableState.update { it.copy(vietPhraseDictionaryStates = dictionaries) } }
                }
                viewModelScope.launch {
                    container.libraryRepository.observeVietPhraseSuggestions()
                        .distinctUntilChanged()
                        .collect { suggestions -> mutableState.update { it.copy(vietPhraseSuggestions = suggestions) } }
                }
            }

            RoomObserverGroup.AI_PROFILES -> viewModelScope.launch {
                container.libraryRepository.observeStoryAiProfiles()
                    .distinctUntilChanged()
                    .collect { profiles ->
                        mutableState.update { it.copy(storyAiProfiles = profiles.associateBy(StoryAiProfileEntity::storyId)) }
                    }
            }

            RoomObserverGroup.SCENE_MUSIC -> viewModelScope.launch {
                container.libraryRepository.observeSceneMusicTracks()
                    .distinctUntilChanged()
                    .collect { tracks -> mutableState.update { it.copy(sceneMusicTracks = tracks) } }
            }

            RoomObserverGroup.TTS_PROFILES -> viewModelScope.launch {
                container.libraryRepository.observeStoryTtsProfiles()
                    .distinctUntilChanged()
                    .collect { profiles ->
                        val mapped = profiles.associateBy(StoryTtsProfileEntity::storyId)
                        mutableState.update { it.copy(storyTtsProfiles = mapped) }
                        val active = mapped[PlaybackQueueStore.state.value.storyId]
                        if (active != null) PlaybackQueueStore.updateVoice(active.rate, active.pitch, active.volume)
                    }
            }

            RoomObserverGroup.VOICE_ROLES -> viewModelScope.launch {
                container.libraryRepository.observeVoiceRoles()
                    .distinctUntilChanged()
                    .collect { roles -> mutableState.update { it.copy(voiceRoles = roles) } }
            }

            RoomObserverGroup.AUDIO_EXPORTS -> viewModelScope.launch {
                container.libraryRepository.observeAudioExports()
                    .distinctUntilChanged()
                    .collect { jobs -> mutableState.update { it.copy(audioExports = jobs) } }
            }
        }
    }
'''
vm_file.write_text(vm_text[:start] + replacement + vm_text[end:], encoding="utf-8")

replace_once(
    viewmodel,
    "            PlaybackQueueStore.state.collect { playback ->\n                val previousChapterId = chapterSleepLastChapterId\n",
    "            PlaybackQueueStore.state.collect { playback ->\n                if (playback.storyId.isNotBlank()) ensureRoomObserverGroup(RoomObserverGroup.TTS_PROFILES)\n                val previousChapterId = chapterSleepLastChapterId\n",
)

app = "app/src/main/java/vn/nghetruyen/app/ui/NgheTruyenApp.kt"
replace_once(
    app,
    '''    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearMessage()
    }

    Scaffold(
''',
    '''    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearMessage()
    }
    LaunchedEffect(state.destination, state.rootTab, state.librarySection) {
        viewModel.ensureRoomObserversForUi(state.destination, state.rootTab, state.librarySection)
    }

    Scaffold(
''',
)
replace_once(
    app,
    "                        onDiagnosticScreenChanged = {},\n",
    "                        onDiagnosticScreenChanged = viewModel::ensureRoomObserversForPersonalPage,\n",
)
