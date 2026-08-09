package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VBookReferenceCaptureFileTest {
    @Test
    fun parsesCapturedCaseAndFeedsOfficialDifferentialAdapter() {
        val hash = "a".repeat(64)
        val captured = VBookReferenceCaptureParser.parse(
            """{
              "schema":1,
              "referenceServer":"http://127.0.0.1:3000",
              "capturedAtEpochMs":1,
              "planSha256":"$hash",
              "cases":[{
                "id":"search-opaque",
                "artifactId":"fixture.current",
                "profile":"CURRENT_JS",
                "features":["CONTRACT_CURRENT","FETCH"],
                "script":"search.js",
                "args":["q","cursor/a"],
                "sourceHashes":{"plugin.json":"$hash","src/search.js":"$hash"},
                "response":{"code":200,"log":"","data":"{\"code\":0,\"data\":[],\"data2\":\"cursor/b\"}"}
              }]
            }""",
        )
        val case = captured.cases.single()
        assertEquals(VBookContractProfile.CURRENT_JS, case.profile)
        assertTrue(VBookFeature.FETCH in case.features)
        val differential = VBookDifferentialFixtures.caseFromOfficialCapture(case.officialCapture())
        assertEquals("cursor/b", differential.expected.continuation)
    }
}
