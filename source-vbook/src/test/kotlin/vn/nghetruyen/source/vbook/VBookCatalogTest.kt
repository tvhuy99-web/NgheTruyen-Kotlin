package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VBookCatalogTest {
    @Test
    fun catalogToleratesMissingTypeAndLegacyNsfwTag() {
        val catalog = VBookCatalogParser.parse(
            """{
              "metadata":{"author":"Repo","description":""},
              "data":[
                {"name":"A","path":"https://repo.example/a.zip","version":1,"source":"https://a.example","type":"novel"},
                {"name":"B","path":"https://repo.example/b.zip","version":"2","source":"http://b.example","tag":"nsfw"}
              ]
            }""",
        )
        assertEquals(2, catalog.items.size)
        assertEquals(VBookContentType.UNKNOWN, catalog.items[1].contentType)
        assertTrue(catalog.items[1].nsfw)
        assertTrue(catalog.items[1].usesCleartextHttp)
    }

    @Test
    fun stableIdentityDependsOnRepositoryAndPackagePathNotDisplayNameOrSourceHost() {
        val first = VBookCatalogParser.parse(
            """{"metadata":{},"data":[{"name":"Old","path":"https://repo.example/a.zip","version":1,"source":"https://old.example"}]}""",
        ).items.single()
        val renamed = first.copy(name = "New", source = "https://new.example")
        val a = first.stableRemoteIdentity("https://catalog.example/plugin.json")
        val b = renamed.stableRemoteIdentity("https://catalog.example/plugin.json")
        assertEquals(a, b)
        assertNotEquals(a, renamed.stableRemoteIdentity("https://other.example/plugin.json"))
    }
}
