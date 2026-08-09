package vn.nghetruyen.source.vbook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VBookEncryptFlagValidationTest {
    @Test
    fun readableEncryptFlagDoesNotBlockActivationButKeepsDistributionWarning() {
        val plugin = """
            {
              "metadata":{"name":"Encrypted flag","author":"test","version":1,"source":"https://site.example","description":"","locale":"vi","regexp":"site","type":"novel","nsfw":false,"encrypt":true},
              "script":{"search":"search.js","detail":"detail.js","toc":"toc.js","chap":"chap.js"},
              "config":{}
            }
        """.trimIndent()
        val scripts = mapOf(
            "src/search.js" to "function execute(q,p){return Response.success([], '');}",
            "src/detail.js" to "function execute(url){return Response.success({name:'x',url:url});}",
            "src/toc.js" to "function execute(url){return Response.success([]);}",
            "src/chap.js" to "function execute(url){return Response.success('ok');}",
        )

        val validation = VBookCandidateValidator().validate(VBookCandidate("encrypt-readable", plugin, scripts))
        assertTrue(validation.activatable)
        assertFalse(VBookFeature.METADATA_ENCRYPT in validation.blockingFeatures)
        assertTrue(validation.warnings.any { it.contains("ENCRYPTED_DISTRIBUTION") })
    }
}
