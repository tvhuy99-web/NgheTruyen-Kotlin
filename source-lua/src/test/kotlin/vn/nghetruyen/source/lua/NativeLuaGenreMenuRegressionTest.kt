package vn.nghetruyen.source.lua

import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLuaGenreMenuRegressionTest {
    @Test
    fun generatedGenreScriptReturnsMenuWhenInputIsBlank() {
        val imported = NativeLuaSourceImporter.import(nativeSource())
        val genre = requireNotNull(imported.entries["src/native_v2_genre.js"]).toString(Charsets.UTF_8)
        val core = requireNotNull(imported.entries["src/native_v2_core.js"]).toString(Charsets.UTF_8)

        assertTrue(genre.contains("if (!wanted) return Response.success(list);"))
        assertTrue(genre.contains("return NativeV2.response("))
        assertTrue(core.contains(NativeLuaRuntimeOverlay.HOST_RUNTIME_MARKER))
    }

    private fun nativeSource(): ByteArray = """
        return {
          api_version = 2,
          metadata = {
            id = "native-genre-menu-test",
            name = "Native Genre Menu Test",
            version = 1,
            website = "https://example.com"
          },
          source = {
            base_url = "https://example.com",
            actions = {
              categories = {
                steps = {},
                result = {
                  type = "categories",
                  fields = {
                    title = { value = "Tiên hiệp" },
                    input = { value = "tien-hiep" }
                  }
                }
              },
              search = { steps = {}, result = { type = "items", fields = {} } },
              chapters = { steps = {}, result = { type = "items", fields = {} } },
              content = { steps = {}, result = { type = "content", fields = {} } }
            }
          }
        }
    """.trimIndent().toByteArray(Charsets.UTF_8)
}
