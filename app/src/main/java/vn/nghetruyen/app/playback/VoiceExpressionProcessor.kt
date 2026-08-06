package vn.nghetruyen.app.playback

import vn.nghetruyen.app.core.model.VoiceExpression
import vn.nghetruyen.app.data.local.VoiceRoleEntity
import java.text.Normalizer

/** Maps a role and paragraph into deterministic TTS/Sonic adjustments. */
data class ExpressiveSpeech(
    val text: String,
    val expression: VoiceExpression,
    val rateMultiplier: Float,
    val pitchMultiplier: Float,
    val volumeMultiplier: Float,
    val sonicSpeedMultiplier: Float,
    val sonicPitchMultiplier: Float,
)

object VoiceExpressionProcessor {
    fun resolve(text: String, role: VoiceRoleEntity?): ExpressiveSpeech {
        val strength = role?.expressionStrength?.coerceIn(0f, 1f) ?: 0.35f
        val configured = runCatching {
            VoiceExpression.valueOf(role?.expression.orEmpty().ifBlank { VoiceExpression.NEUTRAL.name })
        }.getOrDefault(VoiceExpression.NEUTRAL)
        val expression = if (configured == VoiceExpression.NEUTRAL) detect(text) else configured
        val target = profile(expression)
        return ExpressiveSpeech(
            text = normalizePauses(text, expression, strength),
            expression = expression,
            rateMultiplier = blend(1f, target.rate, strength),
            pitchMultiplier = blend(1f, target.pitch, strength),
            volumeMultiplier = blend(1f, target.volume, strength),
            sonicSpeedMultiplier = blend(1f, target.sonicSpeed, strength),
            sonicPitchMultiplier = blend(1f, target.sonicPitch, strength),
        )
    }

    private data class Profile(
        val rate: Float,
        val pitch: Float,
        val volume: Float,
        val sonicSpeed: Float,
        val sonicPitch: Float,
    )

    private fun profile(expression: VoiceExpression): Profile = when (expression) {
        VoiceExpression.NEUTRAL -> Profile(1f, 1f, 1f, 1f, 1f)
        VoiceExpression.CALM -> Profile(0.92f, 0.98f, 0.92f, 0.94f, 0.99f)
        VoiceExpression.WARM -> Profile(0.96f, 1.03f, 0.96f, 0.97f, 1.01f)
        VoiceExpression.SAD -> Profile(0.84f, 0.90f, 0.82f, 0.88f, 0.94f)
        VoiceExpression.TENSE -> Profile(1.06f, 1.05f, 0.98f, 1.05f, 1.03f)
        VoiceExpression.ANGRY -> Profile(1.12f, 1.08f, 1.0f, 1.08f, 1.04f)
        VoiceExpression.EXCITED -> Profile(1.16f, 1.14f, 1.0f, 1.12f, 1.08f)
        VoiceExpression.WHISPER -> Profile(0.88f, 0.92f, 0.55f, 0.90f, 0.96f)
    }

    private fun detect(text: String): VoiceExpression {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
        return when {
            normalized.containsAny("thì thầm", "thi tham", "khẽ nói", "khe noi") -> VoiceExpression.WHISPER
            normalized.containsAny("gào", "gao", "quát", "quat", "tức giận", "tuc gian", "phẫn nộ", "phan no") -> VoiceExpression.ANGRY
            normalized.containsAny("nức nở", "nuc no", "buồn", "buon", "đau đớn", "dau don", "tuyệt vọng", "tuyet vong") -> VoiceExpression.SAD
            normalized.containsAny("hoảng", "hoang", "căng thẳng", "cang thang", "run rẩy", "run ray", "nguy hiểm", "nguy hiem") -> VoiceExpression.TENSE
            text.count { it == '!' } >= 2 || normalized.containsAny("reo lên", "reo len", "vui sướng", "vui suong", "phấn khích", "phan khich") -> VoiceExpression.EXCITED
            normalized.containsAny("dịu dàng", "diu dang", "ấm áp", "am ap", "mỉm cười", "mim cuoi") -> VoiceExpression.WARM
            normalized.containsAny("bình thản", "binh than", "điềm tĩnh", "diem tinh") -> VoiceExpression.CALM
            else -> VoiceExpression.NEUTRAL
        }
    }

    private fun normalizePauses(text: String, expression: VoiceExpression, strength: Float): String {
        val clean = text.trim()
        if (clean.isBlank()) return clean
        return when (expression) {
            VoiceExpression.SAD, VoiceExpression.CALM, VoiceExpression.WHISPER -> {
                if (strength >= 0.45f) clean.replace(Regex("[;:]\\s*"), ", ") else clean
            }
            VoiceExpression.TENSE, VoiceExpression.ANGRY, VoiceExpression.EXCITED -> clean
                .replace(Regex("\\.{3,}"), "… ")
                .replace(Regex("!{2,}"), "! ")
            else -> clean
        }
    }

    private fun String.containsAny(vararg values: String): Boolean = values.any(::contains)
    private fun blend(base: Float, target: Float, strength: Float): Float = base + (target - base) * strength
}
