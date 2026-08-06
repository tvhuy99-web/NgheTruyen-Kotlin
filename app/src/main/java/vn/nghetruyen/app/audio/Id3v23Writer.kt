package vn.nghetruyen.app.audio

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** Minimal ID3v2.3 metadata writer with ordered audiobook chapter frames. */
object Id3v23Writer {
    data class Chapter(
        val title: String,
        val startTimeMs: Long,
        val endTimeMs: Long,
    )

    data class Metadata(
        val title: String,
        val artist: String = "",
        val album: String = "",
        val comment: String = "Xuất bởi Nghe Truyện",
        val chapters: List<Chapter> = emptyList(),
    )

    fun write(output: OutputStream, metadata: Metadata) {
        val frames = ByteArrayOutputStream()
        textFrame("TIT2", metadata.title).takeIf { metadata.title.isNotBlank() }?.let(frames::write)
        textFrame("TPE1", metadata.artist).takeIf { metadata.artist.isNotBlank() }?.let(frames::write)
        textFrame("TALB", metadata.album).takeIf { metadata.album.isNotBlank() }?.let(frames::write)
        commentFrame(metadata.comment).takeIf { metadata.comment.isNotBlank() }?.let(frames::write)
        val chapters = metadata.chapters
            .filter { it.title.isNotBlank() && it.endTimeMs > it.startTimeMs }
            .take(MAX_CHAPTERS)
        chapters.forEachIndexed { index, chapter ->
            frames.write(chapterFrame("ch${index.toString().padStart(5, '0')}", chapter))
        }
        if (chapters.isNotEmpty()) frames.write(tableOfContentsFrame(chapters.size))
        val payload = frames.toByteArray()
        output.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0))
        output.write(synchsafe(payload.size))
        output.write(payload)
    }

    private fun chapterFrame(id: String, chapter: Chapter): ByteArray {
        val payload = ByteArrayOutputStream()
        payload.write(id.toByteArray(Charsets.ISO_8859_1))
        payload.write(0)
        payload.write(int32(chapter.startTimeMs.coerceIn(0L, 0xffffffffL).toInt()))
        payload.write(int32(chapter.endTimeMs.coerceIn(0L, 0xffffffffL).toInt()))
        payload.write(byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte()))
        payload.write(byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte()))
        payload.write(textFrame("TIT2", chapter.title))
        return frame("CHAP", payload.toByteArray())
    }

    private fun tableOfContentsFrame(count: Int): ByteArray {
        val payload = ByteArrayOutputStream()
        payload.write("toc".toByteArray(Charsets.ISO_8859_1))
        payload.write(0)
        payload.write(0x03) // top-level and ordered
        payload.write(count.coerceAtMost(255))
        repeat(count.coerceAtMost(255)) { index ->
            payload.write("ch${index.toString().padStart(5, '0')}".toByteArray(Charsets.ISO_8859_1))
            payload.write(0)
        }
        payload.write(textFrame("TIT2", "Mục lục"))
        return frame("CTOC", payload.toByteArray())
    }

    private fun textFrame(id: String, value: String): ByteArray = frame(
        id,
        byteArrayOf(1) + utf16(value),
    )

    private fun commentFrame(value: String): ByteArray = frame(
        "COMM",
        byteArrayOf(1) + "vie".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 0) + utf16(value),
    )

    private fun utf16(value: String): ByteArray = byteArrayOf(0xff.toByte(), 0xfe.toByte()) +
        value.toByteArray(Charsets.UTF_16LE)

    private fun frame(id: String, payload: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(10 + payload.size)
        output.write(id.toByteArray(Charsets.US_ASCII))
        output.write(int32(payload.size))
        output.write(byteArrayOf(0, 0))
        output.write(payload)
        return output.toByteArray()
    }

    private fun int32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun synchsafe(value: Int): ByteArray = byteArrayOf(
        ((value ushr 21) and 0x7f).toByte(),
        ((value ushr 14) and 0x7f).toByte(),
        ((value ushr 7) and 0x7f).toByte(),
        (value and 0x7f).toByte(),
    )

    private const val MAX_CHAPTERS = 255
}
