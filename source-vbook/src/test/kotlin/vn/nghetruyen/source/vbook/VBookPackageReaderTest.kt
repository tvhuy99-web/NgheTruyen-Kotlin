package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VBookPackageReaderTest {
    @Test
    fun readsPluginAndScriptsWithoutExecutingThem() {
        val zip = packageZip(mapOf(
            "plugin.json" to PLUGIN.toByteArray(),
            "src/search.js" to "function execute(q,p){return Response.success([],p);}".toByteArray(),
            "src/detail.js" to "function execute(u){return Response.success({name:'x',url:u});}".toByteArray(),
            "src/toc.js" to "function execute(u){return Response.success([]);}".toByteArray(),
            "src/chap.js" to "function execute(u){return Response.success('x');}".toByteArray(),
        ))
        val pkg = VBookPackageReader.read(zip)
        assertEquals(4, pkg.scripts.size)
        assertTrue(pkg.decodeScripts().getValue("src/search.js").contains("execute"))
    }

    @Test
    fun rejectsZipTraversal() {
        val zip = packageZip(mapOf(
            "plugin.json" to PLUGIN.toByteArray(),
            "src/search.js" to "x".toByteArray(),
            "../evil.js" to "x".toByteArray(),
        ))
        val failure = runCatching { VBookPackageReader.read(zip) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("TRAVERSAL"))
    }

    @Test
    fun encryptedMetadataWithNonUtf8ScriptRequiresDecoderInsteadOfGuessing() {
        val encryptedPlugin = PLUGIN.replace("\"encrypt\":false", "\"encrypt\":true")
        val zip = packageZip(mapOf(
            "plugin.json" to encryptedPlugin.toByteArray(),
            "src/search.js" to byteArrayOf(0xC3.toByte(), 0x28),
        ))
        val pkg = VBookPackageReader.read(zip)
        val failure = runCatching { pkg.decodeScripts() }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("ENCRYPTED_SCRIPT_DECODER_REQUIRED"))
    }

    private fun packageZip(files: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    companion object {
        private val PLUGIN = """
            {
              "metadata":{"name":"x","author":"a","version":1,"source":"https://x.example","description":"","locale":"vi","regexp":"x","type":"novel","encrypt":false},
              "script":{"search":"search.js","detail":"detail.js","toc":"toc.js","chap":"chap.js"},
              "config":{"DOMAIN":{"title":"Domain","default":"https://x.example","mode":"input","format":"text"}}
            }
        """.trimIndent()
    }
}
