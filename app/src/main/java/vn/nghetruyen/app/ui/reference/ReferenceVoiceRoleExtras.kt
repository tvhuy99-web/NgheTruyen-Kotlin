package vn.nghetruyen.app.ui.reference

import android.content.Context

data class ReferenceVoiceRoleExtra(
    val processingMethod: String = "system",
    val sonicAccurate: Boolean = false,
    val systemVolume: Float? = null,
    val sonicVolume: Float? = null,
)

object ReferenceVoiceRoleExtras {
    private const val PREFS = "reference_voice_role_extras"
    private const val STAGED_VOLUME_TTL_MILLIS = 5_000L

    private data class StagedVolumes(
        val processingMethod: String,
        val sonicAccurate: Boolean,
        val systemVolume: Float,
        val sonicVolume: Float,
        val createdAt: Long,
    )

    @Volatile
    private var stagedVolumes: StagedVolumes? = null

    fun stageVolumesForNextSave(value: ReferenceVoiceRoleExtra) {
        val systemVolume = value.systemVolume ?: return
        val sonicVolume = value.sonicVolume ?: return
        stagedVolumes = StagedVolumes(
            processingMethod = if (value.processingMethod == "sonic") "sonic" else "system",
            sonicAccurate = value.sonicAccurate,
            systemVolume = systemVolume.coerceIn(0f, 1f),
            sonicVolume = sonicVolume.coerceIn(0f, 2f),
            createdAt = System.currentTimeMillis(),
        )
    }

    fun load(context: Context, roleId: String?): ReferenceVoiceRoleExtra {
        if (roleId.isNullOrBlank()) return ReferenceVoiceRoleExtra()
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val systemKey = "$roleId:system_volume"
        val sonicKey = "$roleId:sonic_volume"
        return ReferenceVoiceRoleExtra(
            processingMethod = prefs.getString("$roleId:method", "system").let { if (it == "sonic") "sonic" else "system" },
            sonicAccurate = prefs.getBoolean("$roleId:accurate", false),
            systemVolume = if (prefs.contains(systemKey)) prefs.getFloat(systemKey, 1f).coerceIn(0f, 1f) else null,
            sonicVolume = if (prefs.contains(sonicKey)) prefs.getFloat(sonicKey, 1f).coerceIn(0f, 2f) else null,
        )
    }

    fun save(context: Context, roleId: String, value: ReferenceVoiceRoleExtra) {
        val normalizedMethod = if (value.processingMethod == "sonic") "sonic" else "system"
        val now = System.currentTimeMillis()
        val staged = stagedVolumes?.takeIf {
            now - it.createdAt <= STAGED_VOLUME_TTL_MILLIS &&
                it.processingMethod == normalizedMethod &&
                it.sonicAccurate == value.sonicAccurate
        }
        stagedVolumes = null
        val editor = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("$roleId:method", normalizedMethod)
            .putBoolean("$roleId:accurate", value.sonicAccurate)
        (value.systemVolume ?: staged?.systemVolume)?.let {
            editor.putFloat("$roleId:system_volume", it.coerceIn(0f, 1f))
        }
        (value.sonicVolume ?: staged?.sonicVolume)?.let {
            editor.putFloat("$roleId:sonic_volume", it.coerceIn(0f, 2f))
        }
        editor.apply()
    }

    fun remove(context: Context, roleId: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove("$roleId:method")
            .remove("$roleId:accurate")
            .remove("$roleId:system_volume")
            .remove("$roleId:sonic_volume")
            .apply()
    }
}
