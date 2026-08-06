package vn.nghetruyen.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.app.core.model.VoiceExpression
import vn.nghetruyen.app.data.local.VoiceRoleEntity

class VoiceExpressionProcessorTest {
    @Test
    fun detectsVietnameseExpressionWithoutOnlineAi() {
        val result = VoiceExpressionProcessor.resolve("Cô ấy nức nở, tuyệt vọng nhìn về phía xa.", null)
        assertEquals(VoiceExpression.SAD, result.expression)
        assertTrue(result.rateMultiplier < 1f)
        assertTrue(result.pitchMultiplier < 1f)
    }

    @Test
    fun configuredRoleExpressionWinsAndStrengthIsBounded() {
        val role = VoiceRoleEntity(
            id = "r", storyId = "s", roleName = "Phản diện", aliasesCsv = "",
            enginePackage = "engine.example", voiceName = null, languageTag = "vi-VN",
            rate = 1f, pitch = 1f, volume = 1f,
            expression = VoiceExpression.ANGRY.name, expressionStrength = 0.8f,
            sonicSpeed = 1f, sonicPitch = 1f, isNarrator = false, enabled = true, updatedAt = 0,
        )
        val result = VoiceExpressionProcessor.resolve("Một câu bình thường.", role)
        assertEquals(VoiceExpression.ANGRY, result.expression)
        assertTrue(result.rateMultiplier > 1f)
        assertTrue(result.sonicSpeedMultiplier > 1f)
    }
}
