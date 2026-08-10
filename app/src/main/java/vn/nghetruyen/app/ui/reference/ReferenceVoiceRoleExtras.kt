package vn.nghetruyen.app.ui.reference

import android.content.Context

data class ReferenceVoiceRoleExtra(
    val processingMethod: String = "system",
    val sonicAccurate: Boolean = false,
    val systemRate: Float? = null,
    val systemPitch: Float? = null,
    val systemVolume: Float? = null,
    val sonicSpeed: Float? = null,
    val sonicPitch: Float? = null,
    val sonicVolume: Float? = null,
)

object ReferenceVoiceRoleExtras {
    private const val PREFS = "reference_voice_role_extras"
    private const val STAGED_VALUES_TTL_MILLIS = 5_000L

    private data class StagedValues(
        val processingMethod: String,
        val sonicAccurate: Boolean,
        val systemRate: Float,
        val systemPitch: Float,
        val systemVolume: Float,
        val sonicSpeed: Float,
        val sonicPitch: Float,
        val sonicVolume: Float,
        val createdAt: Long,
    )

    @Volatile
    private var stagedValues: StagedValues? = null

    fun stageProcessorValuesForNextSave(value: ReferenceVoiceRoleExtra) {
        stagedValues = StagedValues(
            processingMethod = if (value.processingMethod == "sonic") "sonic" else "system",
            sonicAccurate = value.sonicAccurate,
            systemRate = (value.systemRate ?: 1f).coerceIn(0.25f, 3f),
            systemPitch = (value.systemPitch ?: 1f).coerceIn(0.5f, 2f),
            systemVolume = (value.systemVolume ?: 1f).coerceIn(0f, 1f),
            sonicSpeed = (value.sonicSpeed ?: 1f).coerceIn(0.25f, 3f),
            sonicPitch = (value.sonicPitch ?: 1f).coerceIn(0.5f, 2f),
            sonicVolume = (value.sonicVolume ?: 1f).coerceIn(0f, 2f),
            createdAt = System.currentTimeMillis(),
        )
    }

    fun load(context: Context, roleId: String?): ReferenceVoiceRoleExtra {
        if (roleId.isNullOrBlank()) return ReferenceVoiceRoleExtra()
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun optionalFloat(key: String, default: Float, minimum: Float, maximum: Float): Float? =
            if (prefs.contains(key)) prefs.getFloat(key, default).coerceIn(minimum, maximum) else null
        return ReferenceVoiceRoleExtra(
            processingMethod = prefs.getString("$roleId:method", "system").let { if (it == "sonic") "sonic" else "system" },
            sonicAccurate = prefs.getBoolean("$roleId:accurate", false),
            systemRate = optionalFloat("$roleId:system_rate", 1f, 0.25f, 3f),
            systemPitch = optionalFloat("$roleId:system_pitch", 1f, 0.5f, 2f),
            systemVolume = optionalFloat("$roleId:system_volume", 1f, 0f, 1f),
            sonicSpeed = optionalFloat("$roleId:sonic_speed", 1f, 0.25f, 3f),
            sonicPitch = optionalFloat("$roleId:sonic_pitch", 1f, 0.5f, 2f),
            sonicVolume = optionalFloat("$roleId:sonic_volume", 1f, 0f, 2f),
        )
    }

    fun save(context: Context, roleId: String, value: ReferenceVoiceRoleExtra) {
        val normalizedMethod = if (value.processingMethod == "sonic") "sonic" else "system"
        val now = System.currentTimeMillis()
        val staged = stagedValues?.takeIf {
            now - it.createdAt <= STAGED_VALUES_TTL_MILLIS &&
                it.processingMethod == normalizedMethod &&
                it.sonicAccurate == value.sonicAccurate
        }
        stagedValues = null
        val editor = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("$roleId:method", normalizedMethod)
            .putBoolean("$roleId:accurate", value.sonicAccurate)
        (value.systemRate ?: staged?.systemRate)?.let { editor.putFloat("$roleId:system_rate", it.coerceIn(0.25f, 3f)) }
        (value.systemPitch ?: staged?.systemPitch)?.let { editor.putFloat("$roleId:system_pitch", it.coerceIn(0.5f, 2f)) }
        (value.systemVolume ?: staged?.systemVolume)?.let { editor.putFloat("$roleId:system_volume", it.coerceIn(0f, 1f)) }
        (value.sonicSpeed ?: staged?.sonicSpeed)?.let { editor.putFloat("$roleId:sonic_speed", it.coerceIn(0.25f, 3f)) }
        (value.sonicPitch ?: staged?.sonicPitch)?.let { editor.putFloat("$roleId:sonic_pitch", it.coerceIn(0.5f, 2f)) }
        (value.sonicVolume ?: staged?.sonicVolume)?.let { editor.putFloat("$roleId:sonic_volume", it.coerceIn(0f, 2f)) }
        editor.apply()
    }

    fun remove(context: Context, roleId: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove("$roleId:method")
            .remove("$roleId:accurate")
            .remove("$roleId:system_rate")
            .remove("$roleId:system_pitch")
            .remove("$roleId:system_volume")
            .remove("$roleId:sonic_speed")
            .remove("$roleId:sonic_pitch")
            .remove("$roleId:sonic_volume")
            .apply()
    }
}
