package vn.nghetruyen.source.lua

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.api.SourceActionName
import vn.nghetruyen.source.api.SourceFullAuthorityPolicy
import vn.nghetruyen.source.api.SourceRuntimeMode
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Exact 2026-08-03 default-source bytes from the supplied Lua/XPK, stored gzip-compressed as test resources. */
class XpkDefaultSourceCompatibilityTest {
    @Test
    fun allSevenDefaultXpkSourcesImportWithoutRewriting() {
        cases.forEach { case ->
            val bytes = exactBytes(case)
            val (pack, warnings) = NativeLuaArchiveImporter.import(ByteArrayInputStream(bytes))

            assertEquals(case.id, pack.manifest.id)
            assertEquals(case.name, pack.manifest.name)
            assertEquals(case.runtime, pack.manifest.runtime.mode)
            assertTrue("${case.file}: actions", pack.manifest.actions.isNotEmpty())
            assertTrue("${case.file}: TOC", SourceActionName.TOC in pack.manifest.actions)
            assertTrue("${case.file}: CHAPTER", SourceActionName.CHAPTER in pack.manifest.actions)
            assertTrue("${case.file}: source.json", "source.json" in pack.entries)
            assertFullAuthority(case.file, pack.manifest)
            assertTrue("${case.file}: warnings", warnings.isNotEmpty())
        }
    }

    @Test
    fun legacyWattpadWrapperIsMigratedButNeverExecutedAsRuntimeLua() {
        val case = cases.first { it.file == "nguon_wattpad_vbook.lua" }
        val (pack, warnings) = NativeLuaArchiveImporter.import(ByteArrayInputStream(exactBytes(case)))

        assertEquals(SourceRuntimeMode.VBOOK_JS_COMPAT, pack.manifest.runtime.mode)
        listOf("plugin.json", "src/config.js", "src/search.js", "src/detail.js", "src/toc.js", "src/chap.js", "legacy/source.lua").forEach {
            assertTrue("Wattpad missing $it", it in pack.entries)
        }
        assertTrue(warnings.any { "wrapper Lua vBook cũ" in it })
    }

    @Test
    fun fakeEmbeddedWrapperCannotTurnArbitraryLuaIntoAnExtension() {
        val fake = """
            return {
              source = {
                vbook_manifest = "{}",
                vbook_files_json = "{}"
              }
            }
        """.trimIndent().toByteArray()

        val error = runCatching { NativeLuaArchiveImporter.import(ByteArrayInputStream(fake)) }.exceptionOrNull()
        requireNotNull(error)
        assertTrue(error.message.orEmpty().startsWith("VBOOK_"))
    }

    private fun assertFullAuthority(file: String, manifest: vn.nghetruyen.source.api.SourceManifest) {
        val network = requireNotNull(manifest.capabilities.network)
        assertEquals("FULL_IN_APP", SourceFullAuthorityPolicy.AUTHORITY_ID)
        assertTrue("$file: public internet", network.publicInternet)
        assertTrue("$file: browser", manifest.capabilities.browser.navigate)
        assertTrue("$file: storage", manifest.capabilities.storageBytes >= 16 * 1024 * 1024)
        assertTrue("$file: websocket", manifest.capabilities.websocket.enabled)
    }

    private fun exactBytes(case: Case): ByteArray {
        val resource = "/xpk-defaults/${case.file}.gz"
        val bytes = requireNotNull(javaClass.getResourceAsStream(resource)) {
            "Missing exact XPK fixture: $resource"
        }.use { compressed -> GZIPInputStream(compressed).use { it.readBytes() } }
        assertEquals("${case.file}: exact XPK SHA-256", case.sha256, sha256(bytes))
        return bytes
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class Case(
        val file: String,
        val id: String,
        val name: String,
        val runtime: SourceRuntimeMode,
        val sha256: String,
    )

    private val cases = listOf(
        Case("nguon_sangtacviet_native.lua", "vn.nghetruyen.native.sangtacviet-native-instant-fast-v50", "Sáng Tác Việt", SourceRuntimeMode.NATIVE_LUA_COMPAT, "71999190a601cc334d05e87053af900350c8afb7c52a7017fada61f2de482b74"),
        Case("nguon_truyencom_native.lua", "vn.nghetruyen.native.truyencom-default-native", "Truyện Com", SourceRuntimeMode.NATIVE_LUA_COMPAT, "1052cddf2059b973f04a7a2e02d0ddea06d0f4e0ef49210a359fc4651102d58f"),
        Case("nguon_truyencv_native.lua", "vn.nghetruyen.native.truyencv-io-default-native", "TCV", SourceRuntimeMode.NATIVE_LUA_COMPAT, "5bcb34b1c6e87ab0c63f430e34b3b14e41eb2903cff99f1a8310e650b1a83d8b"),
        Case("nguon_truyenfull_native.lua", "vn.nghetruyen.native.truyenfull-native", "Truyện Full", SourceRuntimeMode.NATIVE_LUA_COMPAT, "77d4a70859592c391763ed883048d219bb973931aef4131c0ae4e5a10b8d3c68"),
        Case("nguon_truyenyy_native.lua", "vn.nghetruyen.native.truyenyy-co-native", "TruyenYY.co", SourceRuntimeMode.NATIVE_LUA_COMPAT, "2f9b40b0c7fa2274ef57e1994b314472b937be5dd13bb9287bdbfd7557f7bffc"),
        Case("nguon_wikidich_native.lua", "vn.nghetruyen.native.wikidich-default-native-v9-complete-scroll", "WikiDich", SourceRuntimeMode.NATIVE_LUA_COMPAT, "d49c62c4cd14f2111e43b53491e8d5623a7f2eaf6821233bc1aa7d0950501ecc"),
        Case("nguon_wattpad_vbook.lua", "vn.nghetruyen.vbook.wattpad-default-vbook", "Wattpad", SourceRuntimeMode.VBOOK_JS_COMPAT, "bd678f1a7245dbf979d24f0423920bb3c4f00654ae89b4beca03164ec4d79b0b"),
    )
}
