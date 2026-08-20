package vn.nghetruyen.app.audio

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Crash/process-death journal for assets that were physically inserted while the manager dialog
 * was still in its draft transaction. Draft-only edits do not need persistence because they have
 * not touched the database yet; newly inserted rows do, so HỦY can still roll them back after a
 * process recreation.
 */
class AudioAssetManagerJournalStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "audio-manager-journal")

    suspend fun load(kind: AudioAssetKind): Set<String> = withContext(Dispatchers.IO) {
        val source = file(kind)
        if (!source.isFile) return@withContext emptySet()
        runCatching { decode(source.readText(Charsets.UTF_8)) }
            .getOrElse {
                source.delete()
                emptySet()
            }
    }

    suspend fun save(kind: AudioAssetKind, transientTrackIds: Collection<String>) = withContext(Dispatchers.IO) {
        val ids = transientTrackIds
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .take(MAX_TRACK_IDS)
            .toList()
        if (ids.isEmpty()) {
            clearBlocking(kind)
            return@withContext
        }
        if (!root.isDirectory && !root.mkdirs()) return@withContext
        val target = file(kind)
        val temp = File(root, "${target.name}.part")
        temp.writeText(encode(ids), Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    suspend fun clear(kind: AudioAssetKind) = withContext(Dispatchers.IO) {
        clearBlocking(kind)
    }

    private fun clearBlocking(kind: AudioAssetKind) {
        val target = file(kind)
        target.delete()
        File(root, "${target.name}.part").delete()
    }

    private fun file(kind: AudioAssetKind): File = File(root, "${kind.name.lowercase()}.json")

    companion object {
        private const val VERSION = 1
        private const val MAX_TRACK_IDS = 500

        internal fun encode(ids: Collection<String>): String = JSONObject()
            .put("version", VERSION)
            .put("transient_track_ids", JSONArray(ids.toList()))
            .toString()

        internal fun decode(raw: String): Set<String> {
            val root = JSONObject(raw)
            require(root.optInt("version", -1) == VERSION) { "Phiên bản journal không hợp lệ." }
            val array = root.optJSONArray("transient_track_ids") ?: JSONArray()
            return buildSet {
                for (index in 0 until array.length()) {
                    val value = array.optString(index).trim()
                    if (value.isNotBlank()) add(value)
                    if (size >= MAX_TRACK_IDS) break
                }
            }
        }
    }
}
