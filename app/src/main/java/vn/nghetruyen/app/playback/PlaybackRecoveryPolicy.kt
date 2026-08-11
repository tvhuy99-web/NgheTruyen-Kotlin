package vn.nghetruyen.app.playback

import kotlin.math.ceil

/**
 * Pure policy used by the playback service to keep TTS recovery bounded.
 * Every speech chunk gets its own budget so a broken engine can never loop forever.
 */
data class SpeechRecoveryState(
    val sonicFallbackUsed: Boolean = false,
    val engineRetryUsed: Boolean = false,
    val defaultEngineFallbackUsed: Boolean = false,
)

enum class SpeechRecoveryAction {
    RETRY_WITHOUT_SONIC,
    RETRY_CURRENT_ENGINE,
    FALLBACK_TO_DEFAULT_ENGINE,
    STOP_SAFELY,
}

object PlaybackRecoveryPolicy {
    fun next(
        state: SpeechRecoveryState,
        wasUsingSonic: Boolean,
        selectedEnginePackage: String?,
    ): SpeechRecoveryAction = when {
        wasUsingSonic && !state.sonicFallbackUsed -> SpeechRecoveryAction.RETRY_WITHOUT_SONIC
        !state.engineRetryUsed -> SpeechRecoveryAction.RETRY_CURRENT_ENGINE
        !selectedEnginePackage.isNullOrBlank() && !state.defaultEngineFallbackUsed ->
            SpeechRecoveryAction.FALLBACK_TO_DEFAULT_ENGINE
        else -> SpeechRecoveryAction.STOP_SAFELY
    }

    fun after(state: SpeechRecoveryState, action: SpeechRecoveryAction): SpeechRecoveryState = when (action) {
        SpeechRecoveryAction.RETRY_WITHOUT_SONIC -> state.copy(sonicFallbackUsed = true)
        SpeechRecoveryAction.RETRY_CURRENT_ENGINE -> state.copy(engineRetryUsed = true)
        SpeechRecoveryAction.FALLBACK_TO_DEFAULT_ENGINE -> state.copy(defaultEngineFallbackUsed = true)
        SpeechRecoveryAction.STOP_SAFELY -> state
    }
}

/** Timeout values are deterministic and bounded for source-level tests. */
object PlaybackWatchdogPolicy {
    const val INIT_TIMEOUT_MILLIS = 12_000L
    const val MIN_SPEECH_TIMEOUT_MILLIS = 15_000L
    const val MAX_SPEECH_TIMEOUT_MILLIS = 240_000L
    const val COMPLETION_POLL_MILLIS = 650L
    const val QUIET_COMPLETION_CONFIRMATIONS = 2

    fun speechTimeoutMillis(textLength: Int, rate: Float, usesSonic: Boolean): Long {
        val safeRate = rate.coerceIn(0.5f, 2f)
        // Vietnamese speech is usually well below this rate. Deliberately generous.
        val estimatedSeconds = ceil(textLength.coerceAtLeast(1) / (12.0 * safeRate)).toLong()
        val processingHeadroom = if (usesSonic) 20_000L else 8_000L
        return (estimatedSeconds * 1_000L + processingHeadroom)
            .coerceIn(MIN_SPEECH_TIMEOUT_MILLIS, MAX_SPEECH_TIMEOUT_MILLIS)
    }
}

enum class SpeechCompletionObservation {
    WAITING,
    COMPLETED,
    TIMED_OUT,
    STALE,
}

/**
 * Recovers a speech completion when an Android TTS engine or MediaPlayer finishes output without
 * delivering its final callback. This mirrors the XPK runtime: once output has started, two
 * consecutive quiet observations count as completion, while a separate hard deadline still
 * routes genuinely stuck output through the bounded recovery policy.
 */
class SpeechCompletionMonitor(
    private val quietConfirmations: Int = PlaybackWatchdogPolicy.QUIET_COMPLETION_CONFIRMATIONS,
) {
    private data class Session(
        val token: String,
        val hardDeadlineMillis: Long,
        var started: Boolean = false,
        var observedOutput: Boolean = false,
        var quietChecks: Int = 0,
    )

    private var session: Session? = null

    init {
        require(quietConfirmations > 0)
    }

    @Synchronized
    fun begin(token: String, nowMillis: Long, timeoutMillis: Long) {
        session = Session(
            token = token,
            hardDeadlineMillis = nowMillis + timeoutMillis.coerceAtLeast(1L),
        )
    }

    @Synchronized
    fun markStarted(token: String): Boolean {
        val current = session ?: return false
        if (current.token != token) return false
        current.started = true
        current.observedOutput = true
        current.quietChecks = 0
        return true
    }

    @Synchronized
    fun observe(token: String, nowMillis: Long, outputActive: Boolean): SpeechCompletionObservation {
        val current = session ?: return SpeechCompletionObservation.STALE
        if (current.token != token) return SpeechCompletionObservation.STALE
        if (outputActive) {
            current.observedOutput = true
            current.quietChecks = 0
            if (nowMillis >= current.hardDeadlineMillis) {
                session = null
                return SpeechCompletionObservation.TIMED_OUT
            }
            return SpeechCompletionObservation.WAITING
        }
        if (current.started || current.observedOutput) {
            current.quietChecks += 1
            if (current.quietChecks >= quietConfirmations) {
                session = null
                return SpeechCompletionObservation.COMPLETED
            }
        }
        if (nowMillis >= current.hardDeadlineMillis) {
            session = null
            return SpeechCompletionObservation.TIMED_OUT
        }
        return SpeechCompletionObservation.WAITING
    }

    @Synchronized
    fun cancel() {
        session = null
    }
}

object NextChapterAdvancePolicy {
    const val PREFETCH_WAIT_MILLIS = 15_000L
    const val LOAD_ATTEMPTS = 3
    const val LOAD_RETRY_DELAY_MILLIS = 800L

    fun shouldAwaitPrefetch(
        chapterId: String,
        prefetchParentId: String,
        prefetchActive: Boolean,
    ): Boolean = chapterId.isNotBlank() && chapterId == prefetchParentId && prefetchActive

    fun hasRemoteSuccessor(
        sourceId: String,
        nextChapterUrl: String?,
        nextChapterPageUrl: String? = null,
    ): Boolean = sourceId != "offline" && (
        !nextChapterUrl.isNullOrBlank() || !nextChapterPageUrl.isNullOrBlank()
        )
}

/** Rejects late callbacks from an engine instance that has already been replaced. */
class TtsGenerationGuard {
    private var generation: Long = 0L

    @Synchronized
    fun next(): Long = ++generation

    @Synchronized
    fun isCurrent(candidate: Long): Boolean = generation == candidate

    @Synchronized
    fun current(): Long = generation
}

/**
 * Ensures a completion callback is consumed once for the exact chapter/chunk that started it.
 */
class PlaybackCompletionGuard {
    private var activeToken: String? = null
    private var consumed = false

    @Synchronized
    fun begin(token: String) {
        activeToken = token
        consumed = false
    }

    @Synchronized
    fun consume(token: String?): Boolean {
        if (token.isNullOrBlank() || token != activeToken || consumed) return false
        consumed = true
        return true
    }

    @Synchronized
    fun cancel() {
        activeToken = null
        consumed = true
    }
}
