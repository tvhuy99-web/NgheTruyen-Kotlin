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
    fun secondAttemptPrefersAcousticTermsOverMoodModifiers() {
        assertEquals("sword clash", FreesoundAutoAudioResolver.searchQueryForRetry("sword clash close metal", 2))
        assertEquals("forest wind", FreesoundAutoAudioResolver.searchQueryForRetry("forest wind distant trees", 2))
        assertEquals("guqin music", FreesoundAutoAudioResolver.searchQueryForRetry("tense guqin dark music", 2))
    }

    @Test
    fun twoTermQueryCanRepeatWhileFailedSoundBlacklistChangesTheCandidate() {
        assertEquals("mysterious guzheng", FreesoundAutoAudioResolver.searchQueryForRetry("mysterious guzheng", 2))
        assertEquals("mysterious", FreesoundAutoAudioResolver.searchQueryForRetry("mysterious guzheng", 3))
        assertEquals("sword clash", FreesoundAutoAudioResolver.searchQueryForRetry("sword clash", 2))
    }

    @Test
    fun thirdAttemptKeepsTheFirstAcousticTerm() {
        assertEquals("sword", FreesoundAutoAudioResolver.searchQueryForRetry("sword clash close metal", 3))
        assertEquals("forest", FreesoundAutoAudioResolver.searchQueryForRetry("forest wind distant trees", 3))
        assertEquals("guqin", FreesoundAutoAudioResolver.searchQueryForRetry("tense guqin dark music", 3))
    }
}
