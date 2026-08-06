package vn.nghetruyen.app.ai.vietphrase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VietPhraseBundleCodecTest {
    @Test fun bundleRoundTripPreservesKinds() {
        val rules = listOf(
            VietPhraseRule("n", "叶凡", "Diệp Phàm", VietPhraseDictionaryKind.NAMES),
            VietPhraseRule("v", "天道", "Thiên Đạo", VietPhraseDictionaryKind.VIET_PHRASE),
        )
        val states = listOf(
            VietPhrasePersistenceArchiveCodec.DictionaryState(
                id = "NAMES:GLOBAL:",
                kind = VietPhraseDictionaryKind.NAMES,
                scope = VietPhraseScope.GLOBAL,
                storyId = null,
                enabled = false,
                sourceName = "Names.dic",
                sourceFormat = "DIC",
                checksum = "a".repeat(64),
                entryCount = 1,
                revision = 2,
                importedAt = 3,
            ),
        )
        val decoded = VietPhraseBundleCodec.decodeZip(VietPhraseBundleCodec.encodeZip(rules, states))
        assertEquals(setOf(VietPhraseDictionaryKind.NAMES, VietPhraseDictionaryKind.VIET_PHRASE), decoded.importedKinds)
        assertEquals(2, decoded.rules.size)
        assertEquals(states, decoded.dictionaryStates)
        assertEquals(false, decoded.legacyRuleOnly)
    }

    @Test(expected = IllegalArgumentException::class)
    fun pathTraversalIsRejected() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("../Names.txt"))
            zip.write("叶凡=Diệp Phàm".toByteArray())
            zip.closeEntry()
        }
        VietPhraseBundleCodec.decodeZip(output.toByteArray())
    }

    @Test fun delimiterAndUtf16AreDetected() {
        val bytes = "\uFEFF叶凡\tDiệp Phàm\n".toByteArray(Charsets.UTF_16LE)
        val result = VietPhraseDictionaryCodec.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + bytes, "Names.txt")
        assertEquals("\t", result.delimiter)
        assertTrue(result.rules.single().target.contains("Diệp"))
    }
}
