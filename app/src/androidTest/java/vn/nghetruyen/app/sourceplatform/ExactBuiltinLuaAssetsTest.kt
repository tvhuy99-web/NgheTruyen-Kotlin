package vn.nghetruyen.app.sourceplatform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.lua.NativeLuaArchiveImporter
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

@RunWith(AndroidJUnit4::class)
class ExactBuiltinLuaAssetsTest {
    @Test
    fun productionAssetsStayByteExactAndImportWithExpectedRuntime() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets

        cases.forEach { case ->
            val exactBytes = assets.open("source-lua/${case.asset}").use { compressed ->
                GZIPInputStream(compressed).use { it.readBytes() }
            }
            assertEquals("${case.asset}: exact XPK SHA-256", case.sha256, sha256(exactBytes))

            val (pack, _) = NativeLuaArchiveImporter.import(ByteArrayInputStream(exactBytes))
            assertEquals("${case.asset}: package SHA-256", case.sha256, pack.packageSha256)
            assertEquals("${case.asset}: source id", case.sourceId, pack.manifest.id)
            assertEquals("${case.asset}: runtime", case.runtime, pack.manifest.runtime.mode)
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class Case(
        val asset: String,
        val sourceId: String,
        val runtime: SourceRuntimeMode,
        val sha256: String,
    )

    private val cases = listOf(
        Case("nguon_sangtacviet_native.lua.gz", "vn.nghetruyen.native.sangtacviet-native-instant-fast-v50", SourceRuntimeMode.NATIVE_LUA_COMPAT, "f51d7eeed874eb93220fda0750670d9bf72dcdcd6648440f43f41315d83b0577"),
        Case("nguon_truyencom_native.lua.gz", "vn.nghetruyen.native.truyencom-default-native", SourceRuntimeMode.NATIVE_LUA_COMPAT, "1052cddf2059b973f04a7a2e02d0ddea06d0f4e0ef49210a359fc4651102d58f"),
        Case("nguon_truyencv_native.lua.gz", "vn.nghetruyen.native.truyencv-io-default-native", SourceRuntimeMode.NATIVE_LUA_COMPAT, "5bcb34b1c6e87ab0c63f430e34b3b14e41eb2903cff99f1a8310e650b1a83d8b"),
        Case("nguon_truyenfull_native.lua.gz", "vn.nghetruyen.native.truyenfull-native", SourceRuntimeMode.NATIVE_LUA_COMPAT, "360f4182088704d0b40b2d387f2c7fd6eb94863bce46e402c8dc567751315685"),
        Case("nguon_truyenyy_native.lua.gz", "vn.nghetruyen.native.truyenyy-co-native", SourceRuntimeMode.NATIVE_LUA_COMPAT, "2f9b40b0c7fa2274ef57e1994b314472b937be5dd13bb9287bdbfd7557f7bffc"),
        Case("nguon_wikidich_native.lua.gz", "vn.nghetruyen.native.wikidich-default-native-v9-complete-scroll", SourceRuntimeMode.NATIVE_LUA_COMPAT, "d49c62c4cd14f2111e43b53491e8d5623a7f2eaf6821233bc1aa7d0950501ecc"),
        Case("nguon_wattpad_vbook.lua.gz", "vn.nghetruyen.vbook.wattpad-default-vbook", SourceRuntimeMode.VBOOK_JS_COMPAT, "bd678f1a7245dbf979d24f0423920bb3c4f00654ae89b4beca03164ec4d79b0b"),
    )
}
