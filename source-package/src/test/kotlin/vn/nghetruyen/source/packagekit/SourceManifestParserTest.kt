package vn.nghetruyen.source.packagekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceManifestParserTest {
    private val valid = """{
      "schemaVersion":2,"id":"vn.nghetruyen.sources.test","name":"Test","version":"1.0.0","apiVersion":2,
      "runtime":{"mode":"DECLARATIVE"},"origins":["https://example.org"],"capabilities":{},
      "actions":{"detail":{"entry":"actions/detail.json"},"toc":{"entry":"actions/toc.json"},"chapter":{"entry":"actions/chapter.json"}}
    }""".trimIndent()

    @Test fun parsesStrictManifest() {
        assertEquals("vn.nghetruyen.sources.test", SourceManifestParser.parse(valid.toByteArray()).id)
    }

    @Test fun rejectsUnknownFields() {
        val invalid = valid.replace("\"schemaVersion\":2", "\"schemaVersion\":2,\"androidCall\":true")
        assertThrows(IllegalArgumentException::class.java) { SourceManifestParser.parse(invalid.toByteArray()) }
    }
}
