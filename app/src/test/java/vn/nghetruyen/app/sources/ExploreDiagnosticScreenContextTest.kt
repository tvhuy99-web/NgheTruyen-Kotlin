package vn.nghetruyen.app.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreDiagnosticScreenContextTest {
    @Test
    fun homeSwitchesPersistedSourceBeforeActionStarts() {
        assertTrue(
            ExploreDiagnosticScreenContext.shouldActivate(
                currentScreen = "explore:truyenfull:HOME:",
                sourceId = "sangtacviet",
                action = "HOME",
            ),
        )
        assertEquals(
            "explore:sangtacviet:HOME:",
            ExploreDiagnosticScreenContext.target("sangtacviet", "HOME"),
        )
    }

    @Test
    fun sameHomeDoesNotResetCurrentSession() {
        assertFalse(
            ExploreDiagnosticScreenContext.shouldActivate(
                currentScreen = "explore:sangtacviet:HOME:",
                sourceId = "sangtacviet",
                action = "HOME",
            ),
        )
    }

    @Test
    fun genreUsesDedicatedExploreSession() {
        assertTrue(
            ExploreDiagnosticScreenContext.shouldActivate(
                currentScreen = "explore:fiveinone:HOME:",
                sourceId = "fiveinone",
                action = "GENRE",
            ),
        )
        assertEquals(
            "explore:fiveinone:GENRE:",
            ExploreDiagnosticScreenContext.target("fiveinone", "GENRE"),
        )
        assertFalse(
            ExploreDiagnosticScreenContext.shouldActivate(
                currentScreen = "explore:fiveinone:GENRE:",
                sourceId = "fiveinone",
                action = "GENRE",
            ),
        )
    }

    @Test
    fun singleSourceSearchPromotesHomeToSearch() {
        assertTrue(
            ExploreDiagnosticScreenContext.shouldActivate(
                currentScreen = "explore:sangtacviet:HOME:",
                sourceId = "sangtacviet",
                action = "SEARCH",
            ),
        )
    }

    @Test
    fun allSourceSearchDoesNotFollowAnotherSource() {
        assertFalse(
            ExploreDiagnosticScreenContext.shouldActivate(
                currentScreen = "explore:sangtacviet:HOME:",
                sourceId = "truyenfull",
                action = "SEARCH",
            ),
        )
        assertFalse(
            ExploreDiagnosticScreenContext.shouldActivate(
                currentScreen = "explore:sangtacviet:SEARCH:",
                sourceId = "truyenfull",
                action = "SEARCH",
            ),
        )
    }

    @Test
    fun personalHealthCheckDoesNotBecomeExploreSession() {
        assertFalse(
            ExploreDiagnosticScreenContext.shouldActivate(
                currentScreen = "personal:sources",
                sourceId = "sangtacviet",
                action = "HOME",
            ),
        )
    }

    @Test
    fun storyGenreCanEnterCategorySession() {
        assertTrue(
            ExploreDiagnosticScreenContext.shouldActivate(
                currentScreen = "story:story-1",
                sourceId = "sangtacviet",
                action = "CATEGORY",
                category = "Tiên hiệp",
            ),
        )
        assertEquals(
            "explore:sangtacviet:CATEGORY:Tiên hiệp",
            ExploreDiagnosticScreenContext.target(
                sourceId = "sangtacviet",
                action = "CATEGORY",
                category = "Tiên hiệp",
            ),
        )
    }
}
