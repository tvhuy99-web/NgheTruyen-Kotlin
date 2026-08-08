package vn.nghetruyen.app.ai

import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.data.local.AppDatabase
import vn.nghetruyen.app.data.settings.SettingsRepository

/**
 * Compatibility request policy. The reference tool does not expose or enforce
 * device-local daily AI quotas, character quotas, usage counters, retry knobs or backoff knobs.
 */
class AiRequestGovernor(
    @Suppress("UNUSED_PARAMETER") private val database: AppDatabase,
    @Suppress("UNUSED_PARAMETER") private val settingsRepository: SettingsRepository,
) {
    data class Permit(
        val dayEpoch: Int = 0,
        val maxRetries: Int = 0,
        val retryBaseDelayMillis: Int = 1_500,
    )

    @Suppress("UNUSED_PARAMETER")
    suspend fun reserve(inputChars: Int): AppResult<Permit> = AppResult.Success(Permit())

    @Suppress("UNUSED_PARAMETER")
    suspend fun finish(permit: Permit, outputChars: Int, retryCount: Int, errorCode: String?) = Unit
}
