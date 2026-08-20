package vn.nghetruyen.app.freesound

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import vn.nghetruyen.app.audio.AudioAssetKind

data class FreesoundPendingQueue(
    val sounds: List<FreesoundSound>,
    val autoResume: Boolean,
)

/**
 * Small file-backed checkpoint for the UI import queue. No database migration is needed.
 * If Android recreates the Activity/process while a batch is running, reopening the same
 * audio manager can safely continue the remaining sounds. Already imported IDs are skipped
 * by [FreesoundImporter]'s global duplicate check.
 */
class FreesoundPendingQueueStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "freesound-queue")

    suspend fun save(
        kind: AudioAssetKind,
        sounds: Collection<FreesoundSound>,
        autoResume: Boolean,
    ) = withContext(Dispatchers.IO) {
        val unique = sounds
            .asSequence()
            .distinctBy(FreesoundSound::id)
            .take(FreesoundImporter.MAX_BATCH_SIZE)
            .toList()
        if (unique.isEmpty()) {
            clearBlocking(kind)
            return@withContext
        }
        if (!root.isDirectory && !root.mkdirs()) return@withContext
        val target = file(kind)
        val temp = File(root, "${target.name}.part")
        val payload = JSONObject()
            .put("version", VERSION)
            .put("auto_resume", autoResume)
            .put(
                "sounds",
                JSONArray().apply {
                    unique.forEach { sound ->
                        put(
                            JSONObject()
                                .put("id", sound.id)
                                .put("name", sound.name)
                                .put("description", sound.description.take(4_000))
                                .put("duration", sound.durationSeconds)
                                .put("preview_hq_mp3", sound.previewHqMp3 ?: JSONObject.NULL)
                                .put("preview_hq_ogg", sound.previewHqOgg ?: JSONObject.NULL),
                        )
                    }
                },
            )
            .toString()
        temp.writeText(payload, Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    suspend fun load(kind: AudioAssetKind): FreesoundPendingQueue? = withContext(Dispatchers.IO) {
        val source = file(kind)
        if (!source.isFile) return@withContext null
        runCatching {
            val rootJson = JSONObject(source.readText(Charsets.UTF_8))
            if (rootJson.optInt("version", -1) != VERSION) error("Phiên bản checkpoint không hợp lệ.")
            val array = rootJson.optJSONArray("sounds") ?: JSONArray()
            val sounds = buildList {
                for (index in 0 until array.length()) {
                    val row = array.optJSONObject(index) ?: continue
                    val id = row.optInt("id", -1)
                    if (id <= 0) continue
                    val mp3 = row.optString("preview_hq_mp3").trim()
                        .takeIf { it.startsWith("https://", ignoreCase = true) }
                    val ogg = row.optString("preview_hq_ogg").trim()
                        .takeIf { it.startsWith("https://", ignoreCase = true) }
                    add(
                        FreesoundSound(
                            id = id,
                            name = row.optString("name").trim().ifBlank { "Sound #$id" }.take(300),
                            description = row.optString("description").trim().take(4_000),
                            durationSeconds = row.optDouble("duration", 0.0).coerceAtLeast(0.0),
                            previewHqMp3 = mp3,
                            previewHqOgg = ogg,
                        ),
                    )
                }
            }.distinctBy(FreesoundSound::id).take(FreesoundImporter.MAX_BATCH_SIZE)
            if (sounds.isEmpty()) {
                clearBlocking(kind)
                null
            } else {
                FreesoundPendingQueue(
                    sounds = sounds,
                    autoResume = rootJson.optBoolean("auto_resume", false),
                )
            }
        }.getOrElse {
            clearBlocking(kind)
            null
        }
    }

    suspend fun clear(kind: AudioAssetKind) = withContext(Dispatchers.IO) {
        clearBlocking(kind)
    }

    private fun clearBlocking(kind: AudioAssetKind) {
        file(kind).delete()
        File(root, "${file(kind).name}.part").delete()
    }

    private fun file(kind: AudioAssetKind): File =
        File(root, "${kind.name.lowercase()}.json")

    companion object {
        private const val VERSION = 1
    }
}
