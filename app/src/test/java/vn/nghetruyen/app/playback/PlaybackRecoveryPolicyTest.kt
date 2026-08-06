package vn.nghetruyen.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRecoveryPolicyTest {
    @Test fun recoveryIsBoundedAndFallsBackInOrder() {
        var state = SpeechRecoveryState()
        val first = PlaybackRecoveryPolicy.next(state, wasUsingSonic = true, selectedEnginePackage = "engine")
        assertEquals(SpeechRecoveryAction.RETRY_WITHOUT_SONIC, first)
        state = PlaybackRecoveryPolicy.after(state, first)
        val second = PlaybackRecoveryPolicy.next(state, wasUsingSonic = false, selectedEnginePackage = "engine")
        assertEquals(SpeechRecoveryAction.RETRY_CURRENT_ENGINE, second)
        state = PlaybackRecoveryPolicy.after(state, second)
        val third = PlaybackRecoveryPolicy.next(state, wasUsingSonic = false, selectedEnginePackage = "engine")
        assertEquals(SpeechRecoveryAction.FALLBACK_TO_DEFAULT_ENGINE, third)
        state = PlaybackRecoveryPolicy.after(state, third)
        assertEquals(
            SpeechRecoveryAction.STOP_SAFELY,
            PlaybackRecoveryPolicy.next(state, wasUsingSonic = false, selectedEnginePackage = null),
        )
    }

    @Test fun generationAndCompletionGuardsRejectLateOrDuplicateCallbacks() {
        val generation = TtsGenerationGuard()
        val first = generation.next()
        val second = generation.next()
        assertFalse(generation.isCurrent(first))
        assertTrue(generation.isCurrent(second))

        val completion = PlaybackCompletionGuard()
        completion.begin("chunk")
        assertTrue(completion.consume("chunk"))
        assertFalse(completion.consume("chunk"))
        completion.begin("new")
        assertFalse(completion.consume("old"))
        assertTrue(completion.consume("new"))
    }

    @Test fun watchdogIsBounded() {
        assertEquals(
            PlaybackWatchdogPolicy.MIN_SPEECH_TIMEOUT_MILLIS,
            PlaybackWatchdogPolicy.speechTimeoutMillis(1, 2f, false),
        )
        assertEquals(
            PlaybackWatchdogPolicy.MAX_SPEECH_TIMEOUT_MILLIS,
            PlaybackWatchdogPolicy.speechTimeoutMillis(1_000_000, 0.5f, true),
        )
    }
}
