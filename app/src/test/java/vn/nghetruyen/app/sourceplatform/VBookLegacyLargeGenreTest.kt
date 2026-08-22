package vn.nghetruyen.app.sourceplatform

import com.nghetruyen.source.platform.SourceArtifactIdentity
import com.nghetruyen.source.platform.SourceArtifactState
import com.nghetruyen.source.platform.SourceCompatibilityProfile
import com.nghetruyen.source.platform.SourceEcosystem
import com.nghetruyen.source.platform.SourceTrustState
import com.nghetruyen.source.store.SourceArtifactLifecycle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.app.sources.SourceRegistry
import vn.nghetruyen.app.sources.StorySource
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourceActionResponse
import vn.nghetruyen.source.api.SourceCapabilityBrokers
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.vbook.VBookActionRuntime
import vn.nghetruyen.source.vbook.VBookActionRuntimeRegistry
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VBookLegacyLargeGenreTest {
    @Before
    fun clearPlatformVBookRuntime() {
        VBookActionRuntimeRegistry.clear()
    }

    @After
    fun clearPortableVBookRuntimePolicy() {
        VBookActionRuntimeRegistry.clear()
    }

    @Test
    fun legacyChineseNovelGenreWithHundredsOfDynamicActionsIsVisible() = runTest {
        assertLargeGenreMenu(source())
    }

    @Test
    fun registryIoBoundaryPreservesLargeDynamicGenreMenu() = runTest {
        val raw = source()
        val registry = SourceRegistry(
            sources = emptyList(),
            sourcePackSources = listOf(raw),
        )
        val wrapped = requireNotNull(registry.get(raw.descriptor.id))

        assertLargeGenreMenu(wrapped)
    }

    @Test
    fun legacyGenreBypassesInstalledPlatformRuntimeBeforeExecution() = runTest {
        var platformCalls = 0
        VBookActionRuntimeRegistry.install { _, _ ->
            VBookActionRuntime { _, _, request ->
                platformCalls += 1
                SourcePlatformResult.Success(
                    SourceActionResponse(
                        value = JsonValue.Obj(linkedMapOf(
                            "__ngheVBookRawResult" to JsonValue.Str("{\"code\":200,\"data\":[]}"),
                        )),
                        traceId = request.traceId,
                        instructionCount = 0,
                    ),
                )
            }
        }

        assertLargeGenreMenu(source())
        assertEquals(0, platformCalls)
    }

    private suspend fun assertLargeGenreMenu(source: StorySource) {
        assertTrue(source.descriptor.supportsGenre)
        val menu = source.genreMenu().requireSuccess("genre menu")
        assertEquals(360, menu.value.size)
        assertEquals("Fanqie Tuần", menu.value.first().label)
        assertTrue(menu.value.first().selectable)
        assertEquals("————", menu.value.last().label)
        assertFalse(menu.value.last().selectable)
    }

    private fun source(): VBookStorySource {
        val zip = packageZip()
        val identity = SourceArtifactIdentity(SourceEcosystem.VBOOK, "fixture-repo", "legacy-large-genre/plugin.zip")
        val artifact = SourceArtifactLifecycle.candidate(
            artifactId = "legacy-large-genre",
            identity = identity,
            version = "70",
            bytes = zip,
            profile = SourceCompatibilityProfile(SourceEcosystem.VBOOK, "legacy-js"),
            trust = SourceTrustState.REPOSITORY_TRUSTED,
            installedAtEpochMs = 1,
        ).copy(state = SourceArtifactState.ACTIVE, activatedAtEpochMs = 2)
        return VBookStorySource(artifact, zip, SourceCapabilityBrokers())
    }

    private fun <T> AppResult<T>.requireSuccess(label: String): AppResult.Success<T> = when (this) {
        is AppResult.Success -> this
        is AppResult.Failure -> throw AssertionError("$label failed: $code: $message", cause)
    }

    private fun packageZip(): ByteArray {
        val entries = buildList {
            add("{title:'Fanqie Tuần',input:'?find=&host=fanqie&minc=0&sort=viewweek&step=1&tag=',script:'gen1.js'}")
            for (index in 1 until 359) {
                add("{title:'Thể loại $index',input:'/rank?pageNum={page}&catId=$index&{_csrfToken}',script:'gen0.js'}")
            }
            add("{title:'————',input:'',script:'gen0.js'}")
        }
        val genreScript = "function execute(){var data2=[${entries.joinToString(",")}];return Response.success(data2);}"
        val files = linkedMapOf(
            "plugin.json" to """
                {
                  "metadata":{"name":"Legacy Large Genre","author":"B","version":70,"source":"http://14.225.254.182","description":"","locale":"zh_CN","language":"javascript","regexp":"x","type":"chinese_novel"},
                  "script":{"detail":"detail.js","toc":"toc.js","chap":"chap.js","home":"home.js","search":"search.js","genre":"genre.js"}
                }
            """.trimIndent(),
            "src/genre.js" to genreScript,
            "src/gen0.js" to "function execute(input,page){return Response.success([],'');}",
            "src/gen1.js" to "function execute(input,page){return Response.success([],'');}",
            "src/home.js" to "function execute(){return Response.success([]);}",
            "src/search.js" to "function execute(input,page){return Response.success([],'');}",
            "src/detail.js" to "function execute(input){return Response.success({name:'x',url:input});}",
            "src/toc.js" to "function execute(input){return Response.success([]);}",
            "src/chap.js" to "function execute(input){return Response.success({content:'x'});}",
        )
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, source) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(source.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
