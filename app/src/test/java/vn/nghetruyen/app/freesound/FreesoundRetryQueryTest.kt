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
    fun secondAttemptKeepsTwoBroadCoreTerms() {
        assertEquals(
            "flute music",
            FreesoundAutoAudioResolver.searchQueryForRetry("lighthearted comedic chinese flute music", 2),
        )
        assertEquals(
            "thud wood",
            FreesoundAutoAudioResolver.searchQueryForRetry("heavy landing thud on wood", 2),
        )
        assertEquals(
            "drop wood",
            FreesoundAutoAudioResolver.searchQueryForRetry("single gold coin drop on wood", 2),
        )
    }

    @Test
    fun thirdAttemptKeepsOneBroadestCoreTerm() {
        assertEquals(
            "music",
            FreesoundAutoAudioResolver.searchQueryForRetry("lighthearted comedic chinese flute music", 3),
        )
        assertEquals(
            "crash",
            FreesoundAutoAudioResolver.searchQueryForRetry("wall breaking debris crash", 3),
        )
        assertEquals(
            "wood",
            FreesoundAutoAudioResolver.searchQueryForRetry("heavy landing thud on wood", 3),
        )
    }
}
