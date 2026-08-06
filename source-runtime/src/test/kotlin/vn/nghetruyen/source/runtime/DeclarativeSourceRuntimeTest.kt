package vn.nghetruyen.source.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.api.*

class DeclarativeSourceRuntimeTest {
    @Test fun filtersAndPaginatesDeterministically() {
        val manifest = SourceManifest(
            schemaVersion = 2, id = "vn.nghetruyen.sources.test", name = "Test", version = SemanticVersion(1,0,0), apiVersion = 2,
            runtime = SourceRuntimePolicy(SourceRuntimeMode.DECLARATIVE, instructionBudget = 1000), origins = setOf("https://example.org"),
            capabilities = SourceCapabilities(),
            actions = mapOf(SourceActionName.SEARCH to SourceActionSpec("actions/search.json"), SourceActionName.DETAIL to SourceActionSpec("actions/detail.json"), SourceActionName.TOC to SourceActionSpec("actions/toc.json"), SourceActionName.CHAPTER to SourceActionSpec("actions/chapter.json")),
        )
        val action = """{"version":1,"steps":[{"op":"resourceJson","path":"data.json","as":"root"},{"op":"path","from":"root","path":"items","as":"items"},{"op":"filterText","from":"items","fields":["title"],"queryInput":"query","as":"filtered"},{"op":"paginate","from":"filtered","pageInput":"page","pageSize":10,"as":"result"},{"op":"emit","from":"result"}]}"""
        val data = """{"items":[{"title":"Miền Gió"},{"title":"Mặt Trăng"}]}"""
        val resources = MapSourceResourceProvider(mapOf("actions/search.json" to action.toByteArray(), "data.json" to data.toByteArray()))
        val request = SourceActionRequest(manifest.id, SourceActionName.SEARCH, JsonValue.Obj(linkedMapOf("query" to JsonValue.Str("gio"), "page" to JsonValue.Num(1.0,"1"))))
        val result = DeclarativeSourceRuntime().execute(manifest, resources, request)
        assertTrue(result is SourcePlatformResult.Success)
        val output = (result as SourcePlatformResult.Success).value.value as JsonValue.Obj
        assertEquals(1, output.array("items")!!.values.size)
    }
    @Test fun fetchUsesBrokerAndCarriesRemainingDeadline() {
        val manifest = SourceManifest(
            schemaVersion = 2, id = "vn.nghetruyen.sources.remote", name = "Remote", version = SemanticVersion(1,0,0), apiVersion = 2,
            runtime = SourceRuntimePolicy(SourceRuntimeMode.DECLARATIVE, instructionBudget = 1000, actionTimeoutMs = 5_000),
            origins = setOf("https://api.example.org"),
            capabilities = SourceCapabilities(network = SourceNetworkCapability(setOf("GET"))),
            actions = mapOf(SourceActionName.SEARCH to SourceActionSpec("actions/search.json"), SourceActionName.DETAIL to SourceActionSpec("actions/detail.json"), SourceActionName.TOC to SourceActionSpec("actions/toc.json"), SourceActionName.CHAPTER to SourceActionSpec("actions/chapter.json")),
        )
        val action = """{"version":1,"steps":[{"op":"fetch","url":"https://api.example.org/search?q={{input.query|urlencode}}","response":"JSON","as":"http"},{"op":"path","from":"http","path":"body.items","as":"result"},{"op":"emit","from":"result"}]}"""
        val resources = MapSourceResourceProvider(mapOf("actions/search.json" to action.toByteArray()))
        var captured: SourceNetworkRequest? = null
        val broker = SourceNetworkBroker { _, request ->
            captured = request
            SourcePlatformResult.Success(SourceNetworkResponse(200, request.url, emptyMap(), "{\"items\":[]}".toByteArray(), timing = SourceNetworkTiming(0,0), traceId = request.traceId))
        }
        val request = SourceActionRequest(manifest.id, SourceActionName.SEARCH, JsonValue.Obj(linkedMapOf("query" to JsonValue.Str("x y"))))
        assertTrue(DeclarativeSourceRuntime(networkBroker = broker).execute(manifest, resources, request) is SourcePlatformResult.Success)
        assertTrue(captured!!.url.endsWith("q=x%20y"))
        assertTrue(captured!!.timeoutMs in 100L..5_000L)
    }

}
