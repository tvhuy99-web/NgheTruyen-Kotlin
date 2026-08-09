package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue

class VBookAbiTest {
    @Test
    fun currentSearchPassesOpaqueContinuationVerbatim() {
        val invocation = VBookInvocationPlanner.current(
            role = VBookScriptRole.SEARCH,
            scriptPath = "search.js",
            input = "kiem tien",
            continuation = VBookContinuation("https://api.example/list?cursor=a%2Bb"),
        )
        assertEquals(listOf("kiem tien", "https://api.example/list?cursor=a%2Bb"), invocation.args)
    }

    @Test
    fun homeExploreGenreReceiveNoArgumentsInCurrentContract() {
        listOf(VBookScriptRole.HOME, VBookScriptRole.EXPLORE, VBookScriptRole.GENRE).forEach { role ->
            assertTrue(VBookInvocationPlanner.current(role, role.manifestKey + ".js").args.isEmpty())
        }
    }

    @Test
    fun currentResponseRejectsNumericData2InsteadOfInventingMeaning() {
        val raw = JsonCodec.parse("""{"code":0,"data":[],"data2":30}""")
        val failure = runCatching { VBookResponseEnvelopeParser.parse(raw, VBookContractProfile.CURRENT_JS) }.exceptionOrNull()
        assertTrue(failure is VBookResponseException)
        assertTrue(failure?.message.orEmpty().contains("DATA2_STRING_REQUIRED"))
    }

    @Test
    fun legacyAndCurrentResponseCodesStaySeparated() {
        val legacy = VBookResponseEnvelopeParser.parse(
            JsonCodec.parse("""{"code":200,"data":[1],"data2":2}"""),
            VBookContractProfile.LEGACY_JS,
        )
        assertEquals("2", legacy.continuation.token)
        val currentFailure = runCatching {
            VBookResponseEnvelopeParser.parse(
                JsonCodec.parse("""{"code":200,"data":[]}"""),
                VBookContractProfile.CURRENT_JS,
            )
        }.exceptionOrNull()
        assertTrue(currentFailure is VBookResponseException)
    }

    @Test
    fun configPreludeEscapesValuesAndSkipsBuiltInConnectionKeys() {
        val prelude = VBookConfigPrelude.build(
            VBookContractProfile.CURRENT_JS,
            VBookConfigValues(mapOf(
                "DOMAIN" to "https://x.example/\"quoted\"",
                "ENABLED" to "true",
                "thread_num" to "9",
            )),
        )
        assertTrue(prelude.contains("const DOMAIN = \"https://x.example/\\\"quoted\\\"\";"))
        assertTrue(prelude.contains("const ENABLED = \"true\";"))
        assertFalse(prelude.contains("thread_num"))
    }

    @Test
    fun cryptoLoadResolvesToBundledLibrary() {
        val target = VBookLoadPolicy.resolve("crypto.js")
        assertEquals(VBookLoadKind.BUNDLED_CRYPTO, target.kind)
        assertEquals(null, target.path)
    }

    @Test
    fun fetchPlannerPreservesExistingQueryAndEncodesNewQueries() {
        val plan = VBookFetchPlanner.create(
            url = "https://x.example/search?lang=vi#frag",
            queries = linkedMapOf("q" to "a b", "page" to "1"),
        )
        assertEquals("https://x.example/search?lang=vi&q=a%20b&page=1#frag", plan.url)
    }

    @Test
    fun rawResponseSupportsCharsetBase64BlobHeaderAndRequestInfo() {
        val bytes = "xin chào".toByteArray(Charsets.UTF_8)
        val response = VBookRawHttpResponse(
            status = 200,
            statusText = "OK",
            url = "https://x.example/final",
            headers = mapOf("Content-Type" to listOf("text/plain; charset=UTF-8"), "X-Test" to listOf("a")),
            bytes = bytes,
            request = VBookRequestInfo("https://x.example/start", mapOf("Referer" to "https://x.example")),
        )
        assertTrue(response.ok)
        assertEquals("xin chào", response.text())
        assertEquals("a", response.header("x-test"))
        assertEquals(bytes.size, response.blob().size)
        assertTrue(response.base64().isNotBlank())
        assertEquals("https://x.example/start", response.request.url)
    }

    @Test
    fun dynamicActionIsPackageLocalAndProducesOpaquePageInvocation() {
        val action = VBookDynamicActionParser.parse(
            JsonValue.Obj(linkedMapOf(
                "title" to JsonValue.Str("Hot"),
                "input" to JsonValue.Str("/hot"),
                "script" to JsonValue.Str("hot.js"),
            )),
        )!!
        assertEquals("src/hot.js", action.scriptPath)
        assertEquals(listOf("/hot", "cursor-z"), action.invocation(VBookContinuation("cursor-z")).args)
    }
}
