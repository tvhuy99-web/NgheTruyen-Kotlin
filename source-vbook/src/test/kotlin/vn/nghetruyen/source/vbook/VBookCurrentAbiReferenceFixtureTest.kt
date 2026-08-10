package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SemanticVersion
import vn.nghetruyen.source.api.SourceCapabilities
import vn.nghetruyen.source.api.SourceCapabilityBrokers
import vn.nghetruyen.source.api.SourceContentType
import vn.nghetruyen.source.api.SourceCryptoBroker
import vn.nghetruyen.source.api.SourceCryptoOperation
import vn.nghetruyen.source.api.SourceManifest
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.api.SourceRuntimeMode
import vn.nghetruyen.source.api.SourceRuntimePolicy
import vn.nghetruyen.source.api.SourceStorageBroker
import vn.nghetruyen.source.api.SourceStorageRequest
import vn.nghetruyen.source.runtime.SourceResourceProvider
import java.security.MessageDigest

class VBookCurrentAbiReferenceFixtureTest {
    private val resources = ClasspathFixtureResources("reference-fixtures/current-abi")
    private val storageValues = linkedMapOf<String, ByteArray>()
    private val storage = VBookStorageBoundaryBroker(object : SourceStorageBroker {
        override fun get(manifest: SourceManifest, request: SourceStorageRequest) =
            SourcePlatformResult.Success(storageValues[request.key]?.copyOf())

        override fun put(manifest: SourceManifest, request: SourceStorageRequest): SourcePlatformResult<Unit> {
            storageValues[request.key] = request.value?.copyOf() ?: ByteArray(0)
            return SourcePlatformResult.Success(Unit)
        }

        override fun delete(manifest: SourceManifest, request: SourceStorageRequest): SourcePlatformResult<Unit> {
            storageValues.remove(request.key)
            return SourcePlatformResult.Success(Unit)
        }

        override fun keys(manifest: SourceManifest, sourceId: String, prefix: String, traceId: String) =
            SourcePlatformResult.Success(storageValues.keys.filter { it.startsWith(prefix) }.sorted())

        override fun clearPrefix(manifest: SourceManifest, sourceId: String, prefix: String, traceId: String): SourcePlatformResult<Unit> {
            storageValues.keys.filter { it.startsWith(prefix) }.toList().forEach(storageValues::remove)
            return SourcePlatformResult.Success(Unit)
        }

        override fun clear(sourceId: String): SourcePlatformResult<Unit> {
            storageValues.clear()
            return SourcePlatformResult.Success(Unit)
        }
    })
    private val crypto = SourceCryptoBroker { _, request ->
        val algorithm = when (request.operation) {
            SourceCryptoOperation.MD5 -> "MD5"
            SourceCryptoOperation.SHA1 -> "SHA-1"
            SourceCryptoOperation.SHA256 -> "SHA-256"
            SourceCryptoOperation.SHA512 -> "SHA-512"
            else -> error("Fixture does not need ${request.operation}")
        }
        SourcePlatformResult.Success(MessageDigest.getInstance(algorithm).digest(request.payload))
    }
    private val runtime = VBookCompatibilityRuntime(SourceCapabilityBrokers(storage = storage, crypto = crypto))

    @Test
    fun currentCoreFixtureExecutesWithOpaqueCursorDomConfigAndCrypto() {
        val result = runtime.executeDeclared(
            sourceManifest = manifest(),
            resources = resources,
            role = VBookScriptRole.SEARCH,
            input = "fixture-query",
            continuation = VBookContinuation("opaque://cursor?x=1"),
            traceId = "current-search-core-abi",
        ) as SourcePlatformResult.Success

        assertEquals("opaque://cursor?x=1/next?opaque=1", result.value.continuation.token)
        val row = (result.value.data as JsonValue.Arr).values.single() as JsonValue.Obj
        assertEquals("fixture-query", row.string("query"))
        assertEquals("fixture.example", row.string("domain"))
        assertEquals("One|Two", row.string("mapped"))
        assertEquals("a", row.string("firstAttr"))
        assertFalse(row.string("cleaned").orEmpty().contains("script", ignoreCase = true))
        assertEquals(32, row.string("md5").orEmpty().length)
    }

    @Test
    fun dynamicActionUsesExplicitDataInitiallyAndOpaqueData2Later() {
        val explore = runtime.executeDeclared(
            sourceManifest = manifest(),
            resources = resources,
            role = VBookScriptRole.EXPLORE,
            traceId = "current-explore-dynamic-action",
        ) as SourcePlatformResult.Success
        val action = VBookDynamicActionCollector.collect(explore.value.data).single()
        assertTrue(action.hasDataArgument)
        assertEquals(listOf("/latest", "server-a"), action.invocation().args)

        val initial = runtime.executeDynamic(
            sourceManifest = manifest(),
            resources = resources,
            scriptPath = action.scriptPath,
            args = action.invocation().args,
            traceId = "current-dynamic-initial-data",
        ) as SourcePlatformResult.Success
        val initialRow = (initial.value.data as JsonValue.Arr).values.single() as JsonValue.Obj
        assertEquals("server-a", initialRow.string("data"))

        val next = runtime.executeDynamic(
            sourceManifest = manifest(),
            resources = resources,
            scriptPath = action.scriptPath,
            args = action.invocation(VBookContinuation("opaque://next?token=a%2Bb")).args,
            traceId = "current-dynamic-opaque-data2",
        ) as SourcePlatformResult.Success
        val nextRow = (next.value.data as JsonValue.Arr).values.single() as JsonValue.Obj
        assertEquals("opaque://next?token=a%2Bb", nextRow.string("data"))
    }

    @Test
    fun detailTocAndChapNormalizeWithoutSiteSpecificRules() {
        val detail = runtime.executeDeclared(
            manifest(), resources, VBookScriptRole.DETAIL,
            input = "https://example.invalid/story/1",
        ) as SourcePlatformResult.Success
        val normalized = VBookStoryNormalizer.detail(
            detail.value.data,
            "https://example.invalid/story/1",
            "https://example.invalid",
        )!!
        assertEquals("Fixture Story", normalized.story.title)
        assertEquals("Fixture Genre", normalized.genres.single())
        assertEquals(2, normalized.dynamicActions.size)

        val toc = runtime.executeDeclared(
            manifest(), resources, VBookScriptRole.TOC,
            input = "https://example.invalid/story/1",
        ) as SourcePlatformResult.Success
        val chapters = VBookStoryNormalizer.chapters(
            toc.value.data,
            "https://example.invalid/story/1",
            "https://example.invalid",
        )
        assertEquals(listOf("Chapter 1", "Chapter 2"), chapters.map { it.title })

        val chap = runtime.executeDeclared(
            manifest(), resources, VBookScriptRole.CHAP,
            input = "https://example.invalid/story/1/chapter-1",
        ) as SourcePlatformResult.Success
        val body = VBookStoryNormalizer.chapterBody(chap.value.data)
        assertEquals(listOf("Paragraph one", "Paragraph two"), body.paragraphs)
        assertEquals("Fixture Chapter", chap.value.continuation.token)
    }

    private fun manifest() = SourceManifest(
        schemaVersion = 2,
        id = "fixture.vbook.current",
        name = "Current ABI Fixture",
        version = SemanticVersion(1, 0, 0),
        apiVersion = 2,
        contentType = SourceContentType.NOVEL,
        runtime = SourceRuntimePolicy(mode = SourceRuntimeMode.VBOOK_JS_COMPAT),
        origins = setOf("https://example.invalid"),
        capabilities = SourceCapabilities(),
        actions = emptyMap(),
    )

    private class ClasspathFixtureResources(private val root: String) : SourceResourceProvider {
        override fun read(path: String, maxBytes: Int): ByteArray? {
            val stream = javaClass.classLoader?.getResourceAsStream("$root/$path") ?: return null
            return stream.use { input ->
                val bytes = input.readBytes()
                bytes.takeIf { it.size <= maxBytes }
            }
        }
    }
}
