package vn.nghetruyen.source.packagekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import vn.nghetruyen.source.api.SourceUiActionContext

class SourceManifestParserTest {
    private val valid = """{
      "schemaVersion":2,"id":"vn.nghetruyen.sources.test","name":"Test","version":"1.0.0","apiVersion":2,
      "runtime":{"mode":"DECLARATIVE"},"origins":["https://example.org"],"capabilities":{},
      "actions":{"detail":{"entry":"actions/detail.json"},"toc":{"entry":"actions/toc.json"},"chapter":{"entry":"actions/chapter.json"}}
    }""".trimIndent()

    @Test fun parsesStrictManifest() {
        assertEquals("vn.nghetruyen.sources.test", SourceManifestParser.parse(valid.toByteArray()).id)
    }

    @Test fun parsesPluginUiActions() {
        val withUi = valid
            .replace("\"actions\":{", "\"uiActions\":[{\"id\":\"refresh-index\",\"label\":\"LÀM MỚI NGUỒN\",\"contexts\":[\"EXPLORE\",\"STORY\"],\"order\":20}],\"actions\":{\"uiAction\":{\"entry\":\"actions/ui-action.json\"},")
        val parsed = SourceManifestParser.parse(withUi.toByteArray())
        assertEquals("refresh-index", parsed.uiActions.single().id)
        assertEquals(setOf(SourceUiActionContext.EXPLORE, SourceUiActionContext.STORY), parsed.uiActions.single().contexts)
        val roundTrip = SourceManifestParser.parse(SourceManifestWriter.write(parsed))
        assertEquals(parsed.uiActions, roundTrip.uiActions)
    }

    @Test fun rejectsUnknownFields() {
        val invalid = valid.replace("\"schemaVersion\":2", "\"schemaVersion\":2,\"androidCall\":true")
        assertThrows(IllegalArgumentException::class.java) { SourceManifestParser.parse(invalid.toByteArray()) }
    }
}
