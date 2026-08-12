package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VBookLoadGraphValidatorTest {
    @Test
    fun currentLiteralPackageLoadIsAcceptedAndBundledCryptoIsIgnored() {
        val issues = VBookLoadGraphValidator.validate(
            mapOf(
                "src/search.js" to "load('libs.js');load('crypto.js');function execute(){return Response.success([]);}",
                "src/libs.js" to "function helper(){return 1;}",
            ),
            VBookContractProfile.CURRENT_JS,
        )
        assertTrue(issues.isEmpty())
    }

    @Test
    fun currentLiteralMjsLoadIsAccepted() {
        val issues = VBookLoadGraphValidator.validate(
            mapOf(
                "src/search.js" to "load('helper.mjs');function execute(){return Response.success([]);}",
                "src/helper.mjs" to "function helper(){return 1;}",
            ),
            VBookContractProfile.CURRENT_JS,
        )
        assertTrue(issues.isEmpty())
    }

    @Test
    fun currentRejectsNonLiteralMissingAndRecursiveLoad() {
        val nonLiteral = VBookLoadGraphValidator.validate(
            mapOf("src/search.js" to "var x='libs.js';load(x);function execute(){return Response.success([]);}"),
            VBookContractProfile.CURRENT_JS,
        )
        assertEquals(VBookLoadIssueCode.NON_LITERAL, nonLiteral.single().code)

        val missing = VBookLoadGraphValidator.validate(
            mapOf("src/search.js" to "load('missing.js');function execute(){return Response.success([]);}"),
            VBookContractProfile.CURRENT_JS,
        )
        assertEquals(VBookLoadIssueCode.MISSING_TARGET, missing.single().code)

        val recursive = VBookLoadGraphValidator.validate(
            mapOf(
                "src/search.js" to "load('libs.js');function execute(){return Response.success([]);}",
                "src/libs.js" to "load('nested.js');",
                "src/nested.js" to "var x=1;",
            ),
            VBookContractProfile.CURRENT_JS,
        )
        assertTrue(recursive.any { it.code == VBookLoadIssueCode.RECURSIVE && it.scriptPath == "src/libs.js" })
    }

    @Test
    fun legacyIsNotForcedIntoCurrentLoadRulesWithoutReferenceProof() {
        val issues = VBookLoadGraphValidator.validate(
            mapOf("src/search.js" to "var x='legacy.js';load(x);"),
            VBookContractProfile.LEGACY_JS,
        )
        assertTrue(issues.isEmpty())
    }
}
