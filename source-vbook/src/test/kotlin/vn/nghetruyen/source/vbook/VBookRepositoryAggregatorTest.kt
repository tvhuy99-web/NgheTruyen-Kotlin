package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class VBookRepositoryAggregatorTest {
    @Test
    fun repositoryFailureIsExplicitWhileHealthyCatalogsRemainUsable() {
        val bodies = mapOf(
            INDEX to """[
              {"link":"https://repo.example/a.json","author":"A","description":"a"},
              {"link":"https://repo.example/b.json","author":"B","description":"b"}
            ]""",
            "https://repo.example/a.json" to """{
              "metadata":{"author":"A","description":""},
              "data":[
                {"name":"One","author":"A","path":"https://pkg.example/one/plugin.zip","version":1,"source":"https://site.example","type":"novel","locale":"vi_VN"}
              ]
            }""",
        )
        val fetcher = VBookRepositoryFetcher { url, _ ->
            val body = bodies[url] ?: error("HTTP_503")
            VBookRepositoryFetchResult(url, body, sha(body))
        }
        val snapshot = VBookRepositoryAggregator(fetcher).fetchIndex(INDEX)

        assertFalse(snapshot.complete)
        assertEquals(1, snapshot.items.size)
        assertEquals("One", snapshot.items.single().item.name)
        assertEquals(1, snapshot.errors.size)
        assertEquals("https://repo.example/b.json", snapshot.errors.single().url)
        assertTrue(snapshot.items.single().repositoryId.startsWith("vbook-repo-"))
    }

    @Test
    fun identitySurvivesWebsiteDomainChangeButChangesWhenPackagePathChanges() {
        val itemA = VBookCatalogParser.parse("""{
          "metadata":{},
          "data":[{"name":"One","path":"https://pkg.example/source/plugin.zip","version":1,"source":"https://old.example","type":"novel"}]
        }""").items.single()
        val itemB = VBookCatalogParser.parse("""{
          "metadata":{},
          "data":[{"name":"Renamed","path":"https://pkg.example/source/plugin.zip","version":2,"source":"https://new.example","type":"novel"}]
        }""").items.single()
        val itemC = VBookCatalogParser.parse("""{
          "metadata":{},
          "data":[{"name":"One","path":"https://pkg.example/source-v2/plugin.zip","version":2,"source":"https://new.example","type":"novel"}]
        }""").items.single()

        assertEquals(itemA.stableRemoteIdentity("https://repo.example/a.json"), itemB.stableRemoteIdentity("https://repo.example/a.json"))
        assertNotEquals(itemA.stableRemoteIdentity("https://repo.example/a.json"), itemC.stableRemoteIdentity("https://repo.example/a.json"))
    }

    @Test
    fun strictModeRejectsIncompleteSnapshot() {
        val fetcher = VBookRepositoryFetcher { url, _ ->
            if (url == INDEX) {
                val body = """[{"link":"https://repo.example/missing.json","author":"A","description":""}]"""
                VBookRepositoryFetchResult(url, body, sha(body))
            } else error("HTTP_500")
        }
        val error = runCatching { VBookRepositoryAggregator(fetcher).fetchIndex(INDEX, strict = true) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("SNAPSHOT_INCOMPLETE"))
    }

    companion object {
        private const val INDEX = "https://index.example/repository.json"
        private fun sha(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
