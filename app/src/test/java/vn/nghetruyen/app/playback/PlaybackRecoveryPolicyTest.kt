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

    @Test fun completionMonitorRecoversACompletionCallbackLostAfterOutputStarted() {
        val monitor = SpeechCompletionMonitor(quietConfirmations = 2)
        monitor.begin("final-unit", nowMillis = 0L, timeoutMillis = 10_000L)
        assertTrue(monitor.markStarted("final-unit"))
        assertEquals(
            SpeechCompletionObservation.WAITING,
            monitor.observe("final-unit", nowMillis = 650L, outputActive = false),
        )
        assertEquals(
            SpeechCompletionObservation.COMPLETED,
            monitor.observe("final-unit", nowMillis = 1_300L, outputActive = false),
        )
        assertEquals(
            SpeechCompletionObservation.STALE,
            monitor.observe("final-unit", nowMillis = 1_950L, outputActive = false),
        )
    }

    @Test fun completionMonitorResetsQuietEvidenceWhileOutputIsStillActive() {
        val monitor = SpeechCompletionMonitor(quietConfirmations = 2)
        monitor.begin("unit", nowMillis = 0L, timeoutMillis = 10_000L)
        assertTrue(monitor.markStarted("unit"))
        assertEquals(
            SpeechCompletionObservation.WAITING,
            monitor.observe("unit", nowMillis = 650L, outputActive = false),
        )
        assertEquals(
            SpeechCompletionObservation.WAITING,
            monitor.observe("unit", nowMillis = 1_300L, outputActive = true),
        )
        assertEquals(
            SpeechCompletionObservation.WAITING,
            monitor.observe("unit", nowMillis = 1_950L, outputActive = false),
        )
        assertEquals(
            SpeechCompletionObservation.COMPLETED,
            monitor.observe("unit", nowMillis = 2_600L, outputActive = false),
        )
    }

    @Test fun completionMonitorTimesOutOutputThatNeverStarts() {
        val monitor = SpeechCompletionMonitor()
        monitor.begin("stuck", nowMillis = 100L, timeoutMillis = 1_000L)
        assertEquals(
            SpeechCompletionObservation.WAITING,
            monitor.observe("stuck", nowMillis = 1_099L, outputActive = false),
        )
        assertEquals(
            SpeechCompletionObservation.TIMED_OUT,
            monitor.observe("stuck", nowMillis = 1_100L, outputActive = false),
        )
    }

    @Test fun completionMonitorHardDeadlineStillBoundsAnEngineThatClaimsToSpeakForever() {
        val monitor = SpeechCompletionMonitor()
        monitor.begin("stuck-speaking", nowMillis = 0L, timeoutMillis = 1_000L)
        assertEquals(
            SpeechCompletionObservation.WAITING,
            monitor.observe("stuck-speaking", nowMillis = 999L, outputActive = true),
        )
        assertEquals(
            SpeechCompletionObservation.TIMED_OUT,
            monitor.observe("stuck-speaking", nowMillis = 1_000L, outputActive = true),
        )
    }

    @Test fun chapterAdvanceWaitsForTheMatchingPrefetchOnly() {
        assertTrue(NextChapterAdvancePolicy.shouldAwaitPrefetch("chapter-1", "chapter-1", true))
        assertFalse(NextChapterAdvancePolicy.shouldAwaitPrefetch("chapter-1", "chapter-2", true))
        assertFalse(NextChapterAdvancePolicy.shouldAwaitPrefetch("chapter-1", "chapter-1", false))
        assertTrue(NextChapterAdvancePolicy.hasRemoteSuccessor("remote", "https://example/chapter-2"))
        assertTrue(NextChapterAdvancePolicy.hasRemoteSuccessor("remote", null, "https://example/page-2"))
        assertFalse(NextChapterAdvancePolicy.hasRemoteSuccessor("offline", "https://example/chapter-2"))
        assertFalse(NextChapterAdvancePolicy.hasRemoteSuccessor("offline", null, "https://example/page-2"))
        assertFalse(NextChapterAdvancePolicy.hasRemoteSuccessor("remote", ""))
    }
}
