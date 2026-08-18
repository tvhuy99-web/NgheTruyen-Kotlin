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
    fun currentAllowsNestedAcyclicLoadsButRejectsNonLiteralMissingAndCycles() {
        val nested = VBookLoadGraphValidator.validate(
            mapOf(
                "src/search.js" to "load('libs.js');function execute(){return Response.success([]);}",
                "src/libs.js" to "load('nested.js');function helper(){return nested();}",
                "src/nested.js" to "function nested(){return 1;}",
            ),
            VBookContractProfile.CURRENT_JS,
        )
        assertTrue(nested.isEmpty())

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

        val cycle = VBookLoadGraphValidator.validate(
            mapOf(
                "src/search.js" to "load('libs.js');function execute(){return Response.success([]);}",
                "src/libs.js" to "load('nested.js');",
                "src/nested.js" to "load('libs.js');",
            ),
            VBookContractProfile.CURRENT_JS,
        )
        assertTrue(cycle.any { it.code == VBookLoadIssueCode.RECURSIVE && it.target != null })
    }

    @Test
    fun legacyIsNotForcedIntoCurrentStaticLoadRules() {
        val issues = VBookLoadGraphValidator.validate(
            mapOf("src/search.js" to "var x='legacy.js';load(x);"),
            VBookContractProfile.LEGACY_JS,
        )
        assertTrue(issues.isEmpty())
    }
}
