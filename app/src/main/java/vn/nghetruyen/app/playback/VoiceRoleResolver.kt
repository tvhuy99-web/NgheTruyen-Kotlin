package vn.nghetruyen.app.playback

import vn.nghetruyen.app.data.local.VoiceRoleEntity
import java.text.Normalizer

/** Deterministic, local speaker routing. No text is sent to an AI service. */
data class ResolvedVoiceRole(
    val role: VoiceRoleEntity?,
    val spokenText: String,
)

object VoiceRoleResolver {
    fun resolve(text: String, roles: List<VoiceRoleEntity>): ResolvedVoiceRole {
        val cleaned = text.trim()
        if (cleaned.isBlank()) return ResolvedVoiceRole(null, cleaned)
        val enabled = roles.filter(VoiceRoleEntity::enabled)
        val narrator = enabled.firstOrNull(VoiceRoleEntity::isNarrator)
        val characters = enabled.filterNot(VoiceRoleEntity::isNarrator)
            .flatMap { role -> aliases(role).map { alias -> alias to role } }
            .sortedByDescending { it.first.length }
        val normalized = normalize(cleaned)
        for ((alias, role) in characters) {
            val markers = listOf("$alias:", "$alias -", "$alias —", "[$alias]")
            val marker = markers.firstOrNull { normalized.startsWith(it) } ?: continue
            val originalPrefixLength = findOriginalPrefixLength(cleaned, marker)
            val remainder = cleaned.drop(originalPrefixLength).trimStart(' ', ':', '-', '—', ']', '[')
            return ResolvedVoiceRole(role, remainder.ifBlank { cleaned })
        }
        return ResolvedVoiceRole(narrator, cleaned)
    }

    private fun aliases(role: VoiceRoleEntity): List<String> = buildList {
        add(role.roleName)
        addAll(role.aliasesCsv.split(',', ';', '\n'))
    }.map(::normalize).filter(String::isNotBlank).distinct()

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun findOriginalPrefixLength(original: String, normalizedMarker: String): Int {
        for (index in 1..original.length) {
            if (normalize(original.take(index)).startsWith(normalizedMarker)) return index
        }
        return 0
    }
}
