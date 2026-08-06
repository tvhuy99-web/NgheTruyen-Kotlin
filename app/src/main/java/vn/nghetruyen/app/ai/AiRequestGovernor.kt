package vn.nghetruyen.app.ai

import androidx.room.withTransaction
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.data.local.AiUsageDailyEntity
import vn.nghetruyen.app.data.local.AppDatabase
import vn.nghetruyen.app.data.settings.SettingsRepository
import java.time.LocalDate
import java.time.ZoneId

/**
 * Device-local AI quota and accounting. It never stores prompts or response text.
 */
class AiRequestGovernor(
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    data class Permit(
        val dayEpoch: Int,
        val maxRetries: Int,
        val retryBaseDelayMillis: Int,
    )

    suspend fun reserve(inputChars: Int): AppResult<Permit> {
        val settings = settingsRepository.snapshot().aiOnline
        val day = LocalDate.now(zoneId).toEpochDay().toInt()
        val cleanInput = inputChars.coerceAtLeast(0)
        return database.withTransaction {
            val dao = database.aiUsageDailyDao()
            val current = dao.get(day) ?: AiUsageDailyEntity(
                dayEpoch = day,
                requestCount = 0,
                inputChars = 0,
                outputChars = 0,
                retryCount = 0,
                lastErrorCode = null,
                updatedAt = System.currentTimeMillis(),
            )
            if (current.requestCount >= settings.dailyRequestLimit) {
                return@withTransaction AppResult.Failure(
                    "AI_DAILY_REQUEST_QUOTA",
                    "Đã dùng hết ${settings.dailyRequestLimit} lượt AI trong ngày trên thiết bị này.",
                )
            }
            if (current.inputChars + cleanInput > settings.dailyInputCharsLimit.toLong()) {
                return@withTransaction AppResult.Failure(
                    "AI_DAILY_TEXT_QUOTA",
                    "Yêu cầu này vượt giới hạn ${settings.dailyInputCharsLimit} ký tự AI trong ngày.",
                )
            }
            dao.upsert(
                current.copy(
                    requestCount = current.requestCount + 1,
                    inputChars = current.inputChars + cleanInput,
                    lastErrorCode = null,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            dao.pruneBefore(day - 45)
            AppResult.Success(Permit(day, settings.maxRetries, settings.retryBaseDelayMillis))
        }
    }

    suspend fun finish(permit: Permit, outputChars: Int, retryCount: Int, errorCode: String?) {
        database.withTransaction {
            val dao = database.aiUsageDailyDao()
            val current = dao.get(permit.dayEpoch) ?: return@withTransaction
            dao.upsert(
                current.copy(
                    outputChars = current.outputChars + outputChars.coerceAtLeast(0),
                    retryCount = current.retryCount + retryCount.coerceAtLeast(0),
                    lastErrorCode = errorCode?.take(80),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }
}
