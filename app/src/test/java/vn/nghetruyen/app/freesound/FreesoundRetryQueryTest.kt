package vn.nghetruyen.app.freesound

import org.junit.Assert.assertEquals
import org.junit.Test

class FreesoundRetryQueryTest {
    @Test
    fun firstAttemptKeepsAiQueryExactly() {
        assertEquals(
            "wall breaking debris crash",
            FreesoundAutoAudioResolver.searchQueryForRetry("wall breaking debris crash", 1),
        )
    }

    @Test
    fun secondAttemptKeepsTheFirstTwoImportantTerms() {
        assertEquals("sword clash", FreesoundAutoAudioResolver.searchQueryForRetry("sword clash close metal", 2))
        assertEquals("forest wind", FreesoundAutoAudioResolver.searchQueryForRetry("forest wind distant trees", 2))
        assertEquals("tense guqin", FreesoundAutoAudioResolver.searchQueryForRetry("tense guqin dark music", 2))
    }

    @Test
    fun thirdAttemptKeepsTheFirstImportantTerm() {
        assertEquals("sword", FreesoundAutoAudioResolver.searchQueryForRetry("sword clash close metal", 3))
        assertEquals("forest", FreesoundAutoAudioResolver.searchQueryForRetry("forest wind distant trees", 3))
        assertEquals("tense", FreesoundAutoAudioResolver.searchQueryForRetry("tense guqin dark music", 3))
    }
}
