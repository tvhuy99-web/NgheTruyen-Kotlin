package vn.nghetruyen.app.playback

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest





class TtsAudioCache(
    private val directory: File,
    maxBytes: Long,
) {
    private val limitBytes = maxBytes.coerceIn(MIN_LIMIT_BYTES, MAX_LIMIT_BYTES)

    data class Key(
        val text: String,
        val enginePackage: String?,
        val voiceName: String?,
        val languageTag: String,
        val rate: Float,
        val pitch: Float,
        val volume: Float,
        val sonicSpeed: Float,
        val sonicPitch: Float,
        val pronunciationRevision: String,
    ) {
        fun stableId(): String = sha256(
            listOf(
                CACHE_KEY_VERSION,
                text,
                enginePackage.orEmpty(),
                voiceName.orEmpty(),
                languageTag,
                "%.4f".format(java.util.Locale.US, rate),
                "%.4f".format(java.util.Locale.US, pitch),
                "%.4f".format(java.util.Locale.US, volume),
                "%.4f".format(java.util.Locale.US, sonicSpeed),
                "%.4f".format(java.util.Locale.US, sonicPitch),
                pronunciationRevision,
            ).joinToString("\u001f"),
        )
    }

    data class Entry(val audioFile: File, val bytes: Long)

    init {
        directory.mkdirs()
    }

    @Synchronized
    fun get(key: Key): Entry? {
        val id = key.stableId()
        val audio = audioFile(id)
        val checksum = checksumFile(id)
        if (!audio.isFile || !checksum.isFile || audio.length() <= 0L) {
            deleteId(id)
            return null
        }
        val expected = checksum.readText().trim()
        if (expected.length != 64 || !expected.equals(sha256(audio), ignoreCase = true)) {
            deleteId(id)
            return null
        }
        val now = System.currentTimeMillis()
        audio.setLastModified(now)
        checksum.setLastModified(now)
        return Entry(audio, audio.length())
    }


    @Synchronized
    fun put(key: Key, source: File): Entry {
        require(source.isFile && source.length() > 0L) { "Tệp TTS cache rỗng hoặc không tồn tại." }
        val id = key.stableId()
        val temp = File(directory, "$id.tmp-${System.nanoTime()}")
        FileInputStream(source).use { input ->
            FileOutputStream(temp).use { output -> input.copyTo(output, BUFFER_SIZE) }
        }
        val digest = sha256(temp)
        val audio = audioFile(id)
        val checksum = checksumFile(id)
        if (audio.exists()) audio.delete()
        check(temp.renameTo(audio)) { "Không commit được tệp TTS cache." }
        checksum.writeText(digest)
        val now = System.currentTimeMillis()
        audio.setLastModified(now)
        checksum.setLastModified(now)
        trim()
        return Entry(audio, audio.length())
    }

    @Synchronized
    fun clear() {
        directory.listFiles().orEmpty().forEach(File::delete)
    }

    @Synchronized
    fun trim(): Long {
        val entries = directory.listFiles().orEmpty()
            .filter { it.isFile && it.extension == AUDIO_EXTENSION }
            .sortedBy(File::lastModified)
        var total = entries.sumOf(File::length)
        for (audio in entries) {
            if (total <= limitBytes) break
            val length = audio.length()
            val id = audio.nameWithoutExtension
            deleteId(id)
            total -= length
        }

        directory.listFiles().orEmpty().forEach { file ->
            val isKnownAudio = file.extension == AUDIO_EXTENSION
            val isKnownChecksum = file.extension == CHECKSUM_EXTENSION && audioFile(file.nameWithoutExtension).exists()
            if (!isKnownAudio && !isKnownChecksum) file.delete()
        }
        return total.coerceAtLeast(0L)
    }

    @Synchronized
    fun sizeBytes(): Long = directory.listFiles().orEmpty()
        .filter { it.isFile && it.extension == AUDIO_EXTENSION }
        .sumOf(File::length)

    private fun audioFile(id: String) = File(directory, "$id.$AUDIO_EXTENSION")
    private fun checksumFile(id: String) = File(directory, "$id.$CHECKSUM_EXTENSION")

    private fun deleteId(id: String) {
        audioFile(id).delete()
        checksumFile(id).delete()
    }

    companion object {
        const val MIN_LIMIT_BYTES = 8L * 1024L * 1024L
        const val MAX_LIMIT_BYTES = 512L * 1024L * 1024L
        private const val CACHE_KEY_VERSION = "tts-render-volume-v2"
        private const val AUDIO_EXTENSION = "wav"
        private const val CHECKSUM_EXTENSION = "sha256"
        private const val BUFFER_SIZE = 64 * 1024

        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
