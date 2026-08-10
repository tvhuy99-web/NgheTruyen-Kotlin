package vn.nghetruyen.app.playback

import org.json.JSONObject
import vn.nghetruyen.app.ai.XpkSceneMusicParity
import vn.nghetruyen.app.ai.XpkVoiceCastSplitter
import java.security.MessageDigest

/**
 * Runtime bridge between the canonical XPK UNIT/DIALOGUE timeline and Android playback.
 *
 * Reader paragraphs remain a UI/progress concern. TTS, AI voice assignments and scene-music
 * boundaries use the stable XPK unit ids created from the same title/body pair used by AI planning.
 */
object XpkPlaybackRuntime {
    data class VoiceAssignment(
        val unitId: String,
        val voiceId: String,
        val speedAdjustPct: Float,
        val pitchAdjustPct: Float,
        val volumeAdjustPct: Float,
    )

    fun buildSpeechTimeline(title: String, paragraphs: List<String>): List<PlaybackSpeechChunk> {
        val body = paragraphs.joinToString("\n")
        return XpkVoiceCastSplitter.buildUnits(title, body).mapNotNull { unit ->
            val text = unit.text.trim()
            if (text.isBlank()) return@mapNotNull null
            PlaybackSpeechChunk(
                paragraphIndex = paragraphIndex(unit.id).coerceAtLeast(0),
                text = text,
                unitId = unit.id,
                unitKind = unit.unitKind,
                fixedVoiceId = unit.fixedVoice,
            )
        }
    }

    /** Stable digest of every AI/runtime-relevant field in timeline order. */
    fun timelineFingerprint(chunks: List<PlaybackSpeechChunk>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        chunks.forEach { chunk ->
            listOf(
                chunk.unitId,
                chunk.unitKind,
                chunk.fixedVoiceId.orEmpty(),
                chunk.text,
            ).forEach { value ->
                digest.update(value.toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
            }
            digest.update(1.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun timelineFingerprint(title: String, paragraphs: List<String>): String =
        timelineFingerprint(buildSpeechTimeline(title, paragraphs))

    fun parseVoiceAssignments(
        transformedText: String,
        validUnitIds: Collection<String>,
    ): Map<String, VoiceAssignment> {
        val root = JSONObject(transformedText)
        val source = root.optJSONArray("assignments") ?: return emptyMap()
        val validIds = validUnitIds.toHashSet()
        val result = linkedMapOf<String, VoiceAssignment>()
        for (index in 0 until source.length()) {
            val row = source.optJSONObject(index) ?: continue
            val id = row.optString("id").trim()
            val voice = row.optString("voice").trim()
            if (id !in validIds || voice.isBlank() || result.containsKey(id)) continue
            result[id] = VoiceAssignment(
                unitId = id,
                voiceId = voice,
                speedAdjustPct = finiteFloat(row.opt("speed_adjust_pct")),
                pitchAdjustPct = finiteFloat(row.opt("pitch_adjust_pct")),
                volumeAdjustPct = finiteFloat(row.opt("volume_adjust_pct")),
            )
        }
        return result
    }

    /**
     * Expands validated inclusive scene intervals to an exact unit-id -> track-id map.
     * Invalid, gapped or overlapping persisted scene data is rejected as a whole.
     */
    fun parseSceneTimeline(
        transformedText: String,
        validUnitIds: List<String>,
        validTrackIds: Collection<String>,
    ): Map<String, String> {
        if (validUnitIds.isEmpty() || validTrackIds.isEmpty()) return emptyMap()
        val root = JSONObject(transformedText)
        val source = root.optJSONArray("music_scenes") ?: return emptyMap()
        val rows = buildList {
            for (index in 0 until source.length()) {
                val row = source.optJSONObject(index) ?: error("music_scene thứ ${index + 1} không phải object")
                add(
                    XpkSceneMusicParity.RawScene(
                        startId = row.optString("start_id").trim(),
                        endId = row.optString("end_id").trim(),
                        trackId = row.optString("track_id").trim(),
                    ),
                )
            }
        }
        val scenes = XpkSceneMusicParity.validateScenes(rows, validUnitIds, validTrackIds.toList())
        val order = validUnitIds.withIndex().associate { it.value to it.index }
        val result = LinkedHashMap<String, String>(validUnitIds.size)
        scenes.forEach { scene ->
            val start = order.getValue(scene.startUnitId)
            val end = order.getValue(scene.endUnitId)
            for (position in start..end) result[validUnitIds[position]] = scene.trackId
        }
        require(result.size == validUnitIds.size) { "Scene runtime chưa phủ kín timeline" }
        return result
    }

    fun paragraphIndex(unitId: String): Int {
        if (unitId == "TITLE-U01") return 0
        val paragraph = Regex("^P(\\d{4})-U\\d{2}$")
            .matchEntire(unitId)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: return -1
        return (paragraph - 1).coerceAtLeast(0)
    }

    private fun finiteFloat(value: Any?): Float {
        val parsed = when (value) {
            is Number -> value.toFloat()
            null -> 0f
            else -> value.toString().trim().replace(',', '.').toFloatOrNull() ?: 0f
        }
        return if (parsed.isFinite()) parsed else 0f
    }
}
