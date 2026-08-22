package vn.nghetruyen.app.sourceplatform

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.nghetruyen.app.core.common.AppResult
import vn.nghetruyen.source.api.JsonValue
import vn.nghetruyen.source.api.SourceActionName
import vn.nghetruyen.source.api.SourceActionResponse
import vn.nghetruyen.source.api.SourcePlatformResult
import vn.nghetruyen.source.lua.NativeLuaSourceImporter
import vn.nghetruyen.source.packagekit.SourceSignatureAlgorithm
import vn.nghetruyen.source.packagekit.VerifiedSourcePack

class SourcePackStorySourceGenreTest {
    @Test
    fun genreActionIsExposedAndDynamicMenuIsReturned() = runBlocking {
        val imported = NativeLuaSourceImporter.import(nativeSource())
        val entries = LinkedHashMap(imported.entries).apply {
            this["data/source-info.json"] = "{\"categories\":[]}".toByteArray(Charsets.UTF_8)
            this["data/native-source-info.json"] = "{\"categories\":[]}".toByteArray(Charsets.UTF_8)
        }
        val pack = VerifiedSourcePack(
            manifest = imported.manifest,
            entries = entries,
            packageSha256 = "test-package",
            signerKeyId = "test-key",
            signatureAlgorithm = SourceSignatureAlgorithm.ED25519,
        )
        val source = SourcePackStorySource(
            pack = pack,
            executor = SourcePackActionExecutor { _, _, request ->
                val value = when (request.action) {
                    SourceActionName.GENRE -> {
                        val category = (request.input["category"] as? JsonValue.Str)?.value.orEmpty()
                        if (category.isBlank()) {
                            JsonValue.Arr(listOf(
                                JsonValue.Obj(linkedMapOf(
                                    "title" to JsonValue.Str("Tiên hiệp"),
                                    "input" to JsonValue.Str("tien-hiep"),
                                )),
                                JsonValue.Obj(linkedMapOf(
                                    "label" to JsonValue.Str("Ngôn tình"),
                                    "key" to JsonValue.Str("ngon-tinh"),
                                )),
                            ))
                        } else {
                            JsonValue.Arr(listOf(
                                JsonValue.Obj(linkedMapOf(
                                    "id" to JsonValue.Str("story-1"),
                                    "title" to JsonValue.Str("Truyện Tiên Hiệp"),
                                    "url" to JsonValue.Str("https://example.com/story-1"),
                                )),
                            ))
                        }
                    }
                    else -> JsonValue.Arr(emptyList())
                }
                SourcePlatformResult.Success(
                    SourceActionResponse(
                        value = value,
                        traceId = request.traceId,
                        instructionCount = 1,
                    ),
                )
            },
        )

        assertTrue(source.descriptor.supportsGenre)

        val menu = source.genreMenu()
        assertTrue(menu is AppResult.Success)
        val entriesResult = (menu as AppResult.Success).value
        assertEquals(listOf("tien-hiep", "ngon-tinh"), entriesResult.map { it.key })
        assertEquals(listOf("Tiên hiệp", "Ngôn tình"), entriesResult.map { it.label })

        val category = source.category("tien-hiep", 1)
        assertTrue(category is AppResult.Success)
        val stories = (category as AppResult.Success).value
        assertEquals(1, stories.size)
        assertEquals("Truyện Tiên Hiệp", stories.single().title)
    }

    @Test
    fun storyObjectsAreNotMisreadAsGenreMenuEntries() = runBlocking {
        val imported = NativeLuaSourceImporter.import(nativeSource())
        val entries = LinkedHashMap(imported.entries).apply {
            this["data/source-info.json"] = "{\"categories\":[]}".toByteArray(Charsets.UTF_8)
            this["data/native-source-info.json"] = "{\"categories\":[]}".toByteArray(Charsets.UTF_8)
        }
        val pack = VerifiedSourcePack(
            manifest = imported.manifest,
            entries = entries,
            packageSha256 = "test-package",
            signerKeyId = "test-key",
            signatureAlgorithm = SourceSignatureAlgorithm.ED25519,
        )
        val source = SourcePackStorySource(
            pack = pack,
            executor = SourcePackActionExecutor { _, _, request ->
                SourcePlatformResult.Success(
                    SourceActionResponse(
                        value = JsonValue.Arr(listOf(
                            JsonValue.Obj(linkedMapOf(
                                "id" to JsonValue.Str("story-1"),
                                "title" to JsonValue.Str("Không phải thể loại"),
                                "url" to JsonValue.Str("https://example.com/story-1"),
                            )),
                        )),
                        traceId = request.traceId,
                        instructionCount = 1,
                    ),
                )
            },
        )

        val menu = source.genreMenu()
        assertTrue(menu is AppResult.Success)
        assertTrue((menu as AppResult.Success).value.isEmpty())
    }

    private fun nativeSource(): ByteArray = """
        return {
          api_version = 2,
          metadata = {
            id = "sourcepack-genre-test",
            name = "SourcePack Genre Test",
            version = 1,
            website = "https://example.com"
          },
          source = {
            base_url = "https://example.com",
            actions = {
              categories = { steps = {}, result = { type = "categories", fields = {} } },
              search = { steps = {}, result = { type = "items", fields = {} } },
              chapters = { steps = {}, result = { type = "items", fields = {} } },
              content = { steps = {}, result = { type = "content", fields = {} } }
            }
          }
        }
    """.trimIndent().toByteArray(Charsets.UTF_8)
}
