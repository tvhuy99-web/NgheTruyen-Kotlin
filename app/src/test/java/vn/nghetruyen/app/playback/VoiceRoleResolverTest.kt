package vn.nghetruyen.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import vn.nghetruyen.app.data.local.VoiceRoleEntity

class VoiceRoleResolverTest {
    private val narrator = VoiceRoleEntity(
        id = "narrator", storyId = "story", roleName = "Người kể chuyện", aliasesCsv = "",
        voiceName = null, languageTag = "vi-VN", rate = 1f, pitch = 1f, volume = 1f,
        isNarrator = true, enabled = true, updatedAt = 0,
    )
    private val linh = VoiceRoleEntity(
        id = "linh", storyId = "story", roleName = "Ái Linh", aliasesCsv = "Linh; Tiểu Linh",
        voiceName = "voice-linh", languageTag = "vi-VN", rate = 1.1f, pitch = 1.05f, volume = 0.9f,
        isNarrator = false, enabled = true, updatedAt = 0,
    )

    @Test
    fun routesAccentedPrefixAndRemovesSpeakerLabel() {
        val result = VoiceRoleResolver.resolve("Ái Linh: Xin chào", listOf(narrator, linh))
        assertEquals("linh", result.role?.id)
        assertEquals("Xin chào", result.spokenText)
    }

    @Test
    fun routesAliasInBrackets() {
        val result = VoiceRoleResolver.resolve("[Tiểu Linh] Chúng ta đi thôi", listOf(narrator, linh))
        assertEquals("linh", result.role?.id)
        assertEquals("Chúng ta đi thôi", result.spokenText)
    }

    @Test
    fun fallsBackToNarratorAndIgnoresDisabledRoles() {
        assertEquals("narrator", VoiceRoleResolver.resolve("Một đoạn kể", listOf(narrator, linh)).role?.id)
        val disabled = linh.copy(enabled = false)
        assertEquals("narrator", VoiceRoleResolver.resolve("Linh: Chào", listOf(narrator, disabled)).role?.id)
        assertNull(VoiceRoleResolver.resolve("Không vai", emptyList()).role)
    }
}
