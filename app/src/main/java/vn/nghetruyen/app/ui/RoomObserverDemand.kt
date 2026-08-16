package vn.nghetruyen.app.ui

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
        RootTab.EXPLORE -> emptySet()
        RootTab.PERSONAL -> setOf(RoomObserverGroup.VOICE_ROLES)
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
