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

    @Volatile
    private var canonicalVoicePlanActive = false

    @Volatile
    private var canonicalScenePlanActive = false

    /**
     * XPK receives the chapter body as newline-delimited text. Keep embedded line boundaries and only
     * trim the individual non-empty lines; do not collapse their internal whitespace.
     */
    fun canonicalLines(paragraphs: List<String>): List<String> = paragraphs
        .asSequence()
        .flatMap { value ->
            value.replace("\r\n", "\n")
                .replace('\r', '\n')
                .split('\n')
                .asSequence()
        }
        .map(String::trim)
        .filter(String::isNotBlank)
        .toList()

    fun buildSpeechTimeline(title: String, paragraphs: List<String>): List<PlaybackSpeechChunk> {
        val body = canonicalLines(paragraphs).joinToString("\n")
        return XpkVoiceCastSplitter.buildUnits(title, body).mapNotNull { unit ->
            val text = unit.text.trim()
            if (text.isBlank()) return@mapNotNull null
            PlaybackSpeechChunk(
                paragraphIndex = paragraphIndex(unit.id).coerceAtLeast(0),
                text = text,
                unitId = unit.id,
                unitKind = unit.unitKind,
                fixedVoiceId = unit.fixedVoice,
                dialogueGroupId = unit.dialogueGroupId,
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
                chunk.dialogueGroupId.orEmpty(),
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

    /** Reset per-chapter runtime state before loading a new playback timeline. */
    fun resetCanonicalPlans() {
        canonicalVoicePlanActive = false
        canonicalScenePlanActive = false
    }

    /**
     * The XPK apply stage owns prosody whenever a canonical voice plan is active. This guard is
     * intentionally scoped to the exact speech text currently being played so voice-preview UI keeps
     * its local expression behavior.
     */
    fun shouldBypassLocalExpression(text: String): Boolean =
        canonicalVoicePlanActive && PlaybackQueueStore.state.value.currentSpeechText == text

    fun isCanonicalScenePlanActive(): Boolean = canonicalScenePlanActive

    fun parseVoiceAssignments(
        transformedText: String,
        validUnitIds: Collection<String>,
    ): Map<String, VoiceAssignment> {
        canonicalVoicePlanActive = false
        val root = JSONObject(transformedText)
        canonicalVoicePlanActive = requireCurrentTimeline(root)
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

        // XPK applyAssignments() makes the first valid character voice in a dialogue group authoritative
        // for every subsequent fragment in that same long dialogue turn. Keep each fragment's prosody.
        val chunks = PlaybackQueueStore.state.value.speechChunks
        val groupVoice = linkedMapOf<String, String>()
        chunks.forEach { chunk ->
            val group = chunk.dialogueGroupId?.trim().orEmpty()
            if (group.isBlank()) return@forEach
            val assignment = result[chunk.unitId] ?: return@forEach
            val voice = assignment.voiceId.trim()
            if (voice.isNotBlank() && voice != XpkVoiceCastSplitter.NARRATOR_ID && group !in groupVoice) {
                groupVoice[group] = voice
            }
        }
        chunks.forEach { chunk ->
            val group = chunk.dialogueGroupId?.trim().orEmpty()
            val voice = groupVoice[group] ?: return@forEach
            val assignment = result[chunk.unitId] ?: return@forEach
            if (assignment.voiceId != voice) result[chunk.unitId] = assignment.copy(voiceId = voice)
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
        canonicalScenePlanActive = false
        if (validUnitIds.isEmpty() || validTrackIds.isEmpty()) return emptyMap()
        val root = JSONObject(transformedText)
        canonicalScenePlanActive = requireCurrentTimeline(root)
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

    /** Returns true only for milestone-5 canonical transforms that carry a verified timeline digest. */
    private fun requireCurrentTimeline(root: JSONObject): Boolean {
        val expected = root.optString("timeline_fingerprint").trim()
        if (expected.isBlank()) return false
        val current = PlaybackQueueStore.state.value.speechChunks
        require(current.isNotEmpty()) { "Không có XPK timeline để xác minh transform" }
        require(timelineFingerprint(current) == expected) {
            "Transform AI thuộc timeline khác với nội dung đang phát"
        }
        return true
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
