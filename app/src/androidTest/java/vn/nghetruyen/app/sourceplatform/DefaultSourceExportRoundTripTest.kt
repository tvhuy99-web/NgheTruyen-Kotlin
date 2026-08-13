package vn.nghetruyen.app.sourceplatform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import vn.nghetruyen.app.NgheTruyenApplication
import vn.nghetruyen.source.lua.NativeLuaArchiveImporter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class DefaultSourceExportRoundTripTest {
    @Test
    fun allSevenDefaultSourcesExportAndReimport() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NgheTruyenApplication
        val manager = app.container.sourcePlatformManager

        cases.forEach { case ->
            val output = ByteArrayOutputStream()
            manager.exportInstalledPack(case.sourceId, output).getOrThrow()
            val exported = output.toByteArray()
            assertTrue("${case.sourceId}: export must be a ZIP", exported.size >= 4 && exported[0] == 'P'.code.toByte() && exported[1] == 'K'.code.toByte())

            val (imported, _) = NativeLuaArchiveImporter.import(ByteArrayInputStream(exported))
            assertEquals("${case.sourceId}: round-trip id", case.sourceId, imported.manifest.id)
            assertEquals("${case.sourceId}: round-trip package hash", case.originalSha256, imported.packageSha256)
        }
    }

    @Test
    fun disablingDefaultSourceDoesNotDisableExport() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NgheTruyenApplication
        val manager = app.container.sourcePlatformManager
        val case = cases.first()

        manager.setEnabled(case.sourceId, false).getOrThrow()
        try {
            val output = ByteArrayOutputStream()
            manager.exportInstalledPack(case.sourceId, output).getOrThrow()
            assertTrue("disabled builtin must still be exportable", output.size() > 0)
        } finally {
            manager.setEnabled(case.sourceId, true).getOrThrow()
        }
    }

    private data class Case(val sourceId: String, val originalSha256: String)

    private val cases = listOf(
        Case("vn.nghetruyen.native.truyenfull-native", "77d4a70859592c391763ed883048d219bb973931aef4131c0ae4e5a10b8d3c68"),
        Case("vn.nghetruyen.native.truyencv-io-default-native", "5bcb34b1c6e87ab0c63f430e34b3b14e41eb2903cff99f1a8310e650b1a83d8b"),
        Case("vn.nghetruyen.native.truyencom-default-native", "1052cddf2059b973f04a7a2e02d0ddea06d0f4e0ef49210a359fc4651102d58f"),
        Case("vn.nghetruyen.native.truyenyy-co-native", "2f9b40b0c7fa2274ef57e1994b314472b937be5dd13bb9287bdbfd7557f7bffc"),
        Case("vn.nghetruyen.native.wikidich-default-native-v9-complete-scroll", "d49c62c4cd14f2111e43b53491e8d5623a7f2eaf6821233bc1aa7d0950501ecc"),
        Case("vn.nghetruyen.native.sangtacviet-native-instant-fast-v50", "71999190a601cc334d05e87053af900350c8afb7c52a7017fada61f2de482b74"),
        Case("vn.nghetruyen.vbook.wattpad-default-vbook", "bd678f1a7245dbf979d24f0423920bb3c4f00654ae89b4beca03164ec4d79b0b"),
    )
}
