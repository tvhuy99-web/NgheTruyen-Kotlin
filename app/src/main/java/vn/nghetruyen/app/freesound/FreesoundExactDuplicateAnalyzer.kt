package vn.nghetruyen.app.freesound

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
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
 * the database. File size is used as a cheap first pass, so SHA-256 only reads groups that can
 * actually contain duplicates. Unknown-size content URIs are conservatively hashed together.
 */
object FreesoundExactDuplicateAnalyzer {
    private data class Candidate(
        val track: SceneMusicTrackEntity,
        val sizeBytes: Long,
    )

    suspend fun find(
        context: Context,
        tracks: List<SceneMusicTrackEntity>,
        maxGroups: Int = 40,
    ): List<FreesoundExactDuplicateGroup> = withContext(Dispatchers.IO) {
        if (tracks.size < 2) return@withContext emptyList()

        val candidates = tracks.map { track ->
            Candidate(track, contentLength(context, track.uri))
        }
        val bySize = candidates.groupBy(Candidate::sizeBytes)
        val hashCandidates = buildList {
            bySize.forEach { (size, rows) ->
                if (size < 0L || rows.size >= 2) addAll(rows)
            }
        }
        if (hashCandidates.size < 2) return@withContext emptyList()

        val byHash = linkedMapOf<String, MutableList<String>>()
        hashCandidates.forEach { candidate ->
            val hash = sha256(context, candidate.track.uri) ?: return@forEach
            byHash.getOrPut(hash) { mutableListOf() } += candidate.track.id
        }
        byHash.entries
            .asSequence()
            .filter { it.value.size >= 2 }
            .map { FreesoundExactDuplicateGroup(it.key, it.value.distinct()) }
            .sortedByDescending { it.trackIds.size }
            .take(maxGroups.coerceIn(1, 100))
            .toList()
    }

    internal fun candidateSizesToHash(sizes: List<Long>): Set<Long> = sizes
        .groupingBy { it }
        .eachCount()
        .filter { (size, count) -> size < 0L || count >= 2 }
        .keys

    private fun contentLength(context: Context, uriValue: String): Long = runCatching {
        val uri = Uri.parse(uriValue)
        when (uri.scheme?.lowercase()) {
            "file" -> File(uri.path ?: return@runCatching -1L).length().takeIf { it > 0L } ?: -1L
            "content" -> {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                    descriptor.length.takeIf { it >= 0L }
                } ?: context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getLong(column) else -1L
                } ?: -1L
            }
            else -> -1L
        }
    }.getOrDefault(-1L)

    internal fun sha256(context: Context, uriValue: String): String? = runCatching {
        val uri = Uri.parse(uriValue)
        val input = when (uri.scheme?.lowercase()) {
            "file" -> File(uri.path ?: error("Thiếu đường dẫn file")).inputStream()
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
