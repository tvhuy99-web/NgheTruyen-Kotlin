package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VBookContractModelTest {
    @Test
    fun detectsLegacyManifestWithoutConvertingItsConfig() {
        val manifest = VBookManifestParser.parse(LEGACY)
        val detection = VBookContractDetector.detect(manifest)

        assertEquals(VBookContractProfile.LEGACY_JS, detection.profile)
        assertEquals(VBookContentType.NOVEL, manifest.metadata.type)
        assertTrue(manifest.config.getValue("thread_num").legacyPrimitive)
        assertEquals("1", manifest.config.getValue("thread_num").defaultValue)
        assertTrue(VBookRequiredScripts.missing(manifest, detection.profile).isEmpty())
    }

    @Test
    fun detectsCurrentManifestAndDescriptorConfig() {
        val manifest = VBookManifestParser.parse(CURRENT)
        val detection = VBookContractDetector.detect(manifest)

        assertEquals(VBookContractProfile.CURRENT_JS, detection.profile)
        assertEquals(VBookConfigMode.INPUT, manifest.config.getValue("DOMAIN").mode)
        assertEquals("https://site.example", manifest.config.getValue("DOMAIN").defaultValue)
        assertTrue(VBookRequiredScripts.missing(manifest, detection.profile).isEmpty())
    }

    @Test
    fun signalFreeMinimalNovelDefaultsToCanonicalCurrentContract() {
        val manifest = VBookManifestParser.parse(MINIMAL_CURRENT)
        val detection = VBookContractDetector.detect(manifest)

        assertEquals(VBookContractProfile.CURRENT_JS, detection.profile)
        assertEquals(0, detection.currentScore)
        assertEquals(0, detection.legacyScore)
        assertTrue(detection.reasons.any { it.contains("canonical current") })
        assertTrue(VBookRequiredScripts.missing(manifest, detection.profile).isEmpty())
    }

    @Test
    fun comicMayOmitPageAndChapAndUseRawChapterUrlFallback() {
        val manifest = VBookManifestParser.parse(
            CURRENT.replace("\"novel\"", "\"comic\"")
                .replace(",\n    \"chap\": \"chap.js\"", ""),
        )
        val missing = VBookRequiredScripts.missing(manifest, VBookContractProfile.CURRENT_JS)
        assertTrue(missing.isEmpty())
    }

    companion object {
        private val LEGACY = """
            {
              "metadata": {
                "name": "Wiki Dịch", "author": "vBook", "version": 27,
                "source": "https://wikicv.org", "regexp": "wikicv", "description": "",
                "locale": "vi_VN", "type": "novel", "language": "javascript", "encrypt": true
              },
              "script": {
                "home": "home.js", "genre": "genre.js", "detail": "detail.js",
                "search": "search.js", "toc": "toc.js", "chap": "chap.js"
              },
              "config": { "thread_num": 1, "delay": 4000 }
            }
        """.trimIndent()

        private val CURRENT = """
            {
              "metadata": {
                "name": "Current", "author": "vBook", "version": 1,
                "source": "https://site.example", "regexp": "site", "description": "",
                "locale": "vi", "type": "novel", "nsfw": false, "encrypt": true
              },
              "script": {
                "home": "home.js", "explore": "explore.js", "genre": "genre.js",
                "search": "search.js", "detail": "detail.js", "toc": "toc.js",
                "chap": "chap.js"
              },
              "config": {
                "DOMAIN": { "title": "Domain", "default": "https://site.example", "mode": "input", "format": "text" }
              }
            }
        """.trimIndent()

        private val MINIMAL_CURRENT = """
            {
              "metadata": {
                "name": "Minimal", "author": "Author", "version": 1,
                "source": "https://minimal.example", "regexp": "minimal", "description": "",
                "locale": "vi", "type": "novel"
              },
              "script": {
                "search": "search.js", "detail": "detail.js", "toc": "toc.js", "chap": "chap.js"
              },
              "config": {}
            }
        """.trimIndent()
    }
}
