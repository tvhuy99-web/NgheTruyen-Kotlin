package vn.nghetruyen.app.ui.reference

import android.content.Context

data class ReferenceVoiceRoleExtra(
    val processingMethod: String = "system",
    val sonicAccurate: Boolean = false,
)

object ReferenceVoiceRoleExtras {
    private const val PREFS = "reference_voice_role_extras"

    fun load(context: Context, roleId: String?): ReferenceVoiceRoleExtra {
        if (roleId.isNullOrBlank()) return ReferenceVoiceRoleExtra()
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ReferenceVoiceRoleExtra(
            processingMethod = prefs.getString("$roleId:method", "system").let { if (it == "sonic") "sonic" else "system" },
            sonicAccurate = prefs.getBoolean("$roleId:accurate", false),
        )
    }

    fun save(context: Context, roleId: String, value: ReferenceVoiceRoleExtra) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("$roleId:method", if (value.processingMethod == "sonic") "sonic" else "system")
            .putBoolean("$roleId:accurate", value.sonicAccurate)
            .apply()
    }

    fun remove(context: Context, roleId: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove("$roleId:method").remove("$roleId:accurate").apply()
    }
}
