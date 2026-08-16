package vn.nghetruyen.app.ui.reference

import android.content.Context
import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.app.core.model.VoiceRoleDraft
import vn.nghetruyen.app.data.local.VoiceRoleEntity
import java.util.UUID

 
object ReferenceVoiceRolePersistence {
    suspend fun save(context: Context, storyId: String, draft: VoiceRoleDraft): String {
        val app = context.applicationContext as NgheTruyenApplication
        val id = draft.originalRoleId ?: UUID.randomUUID().toString()
        val narrator = draft.isNarrator
        val method = if (draft.processingMethod == "sonic") "sonic" else "system"
        
        
        
        val activeVolume = if (method == "sonic") {
            draft.sonicVolume.coerceIn(0f, 2f)
        } else {
            draft.volume.coerceIn(0f, 1f)
        }
        app.container.database.voiceRoleDao().upsert(
            VoiceRoleEntity(
                id = id,
                storyId = storyId,
                roleName = if (narrator) "Người kể chuyện" else draft.roleName.trim().take(80),
                aliasesCsv = draft.aliases.trim().take(500),
                description = draft.description.trim().take(1_000),
                enginePackage = draft.enginePackage,
                voiceName = draft.voiceName,
                languageTag = draft.languageTag.ifBlank { "vi-VN" }.take(32),
                rate = draft.rate.coerceIn(0.25f, 3f),
                pitch = draft.pitch.coerceIn(0.5f, 2f),
                volume = activeVolume,
                isNarrator = narrator,
                expression = draft.expression.name,
                expressionStrength = draft.expressionStrength.coerceIn(0f, 1f),
                sonicSpeed = draft.sonicSpeed.coerceIn(0.25f, 3f),
                sonicPitch = draft.sonicPitch.coerceIn(0.5f, 2f),
                enabled = if (narrator) true else draft.enabled,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        return id
    }

    suspend fun delete(context: Context, role: VoiceRoleEntity) {
        val app = context.applicationContext as NgheTruyenApplication
        app.container.database.voiceRoleDao().delete(role.id)
        ReferenceVoiceRoleExtras.remove(context, role.id)
    }
}
