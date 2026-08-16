package vn.nghetruyen.app.ui

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
