package vn.nghetruyen.app.freesound

import android.content.Context
import android.net.Uri
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.nghetruyen.app.data.local.SceneMusicTrackEntity

data class FreesoundExactDuplicateGroup(
    val sha256: String,
    val trackIds: List<String>,
)

/**
 * Exact-content duplicate detector for the local audio library. It stores no fingerprint in
 * the database: SHA-256 is calculated only when the user explicitly runs duplicate inspection.
 */
object FreesoundExactDuplicateAnalyzer {
    suspend fun find(
        context: Context,
        tracks: List<SceneMusicTrackEntity>,
        maxGroups: Int = 40,
    ): List<FreesoundExactDuplicateGroup> = withContext(Dispatchers.IO) {
        if (tracks.size < 2) return@withContext emptyList()
        val byHash = linkedMapOf<String, MutableList<String>>()
        tracks.forEach { track ->
            val hash = sha256(context, track.uri) ?: return@forEach
            byHash.getOrPut(hash) { mutableListOf() } += track.id
        }
        byHash.entries
            .asSequence()
            .filter { it.value.size >= 2 }
            .map { FreesoundExactDuplicateGroup(it.key, it.value.distinct()) }
            .sortedByDescending { it.trackIds.size }
            .take(maxGroups.coerceIn(1, 100))
            .toList()
    }

    internal fun sha256(context: Context, uriValue: String): String? = runCatching {
        val uri = Uri.parse(uriValue)
        val input = when (uri.scheme?.lowercase()) {
            "file" -> java.io.File(uri.path ?: error("Thiếu đường dẫn file")).inputStream()
            "content" -> context.contentResolver.openInputStream(uri) ?: error("Không mở được content URI")
            else -> error("URI âm thanh không hỗ trợ")
        }
        input.buffered().use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }.getOrNull()
}
