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

    fun speechTimeoutMillis(textLength: Int, rate: Float, usesSonic: Boolean): Long {
        val safeRate = rate.coerceIn(0.5f, 2f)
        // Vietnamese speech is usually well below this rate. Deliberately generous.
        val estimatedSeconds = ceil(textLength.coerceAtLeast(1) / (12.0 * safeRate)).toLong()
        val processingHeadroom = if (usesSonic) 20_000L else 8_000L
        return (estimatedSeconds * 1_000L + processingHeadroom)
            .coerceIn(MIN_SPEECH_TIMEOUT_MILLIS, MAX_SPEECH_TIMEOUT_MILLIS)
    }
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
