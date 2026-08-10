package vn.nghetruyen.source.vbook

import com.nghetruyen.source.compat.CompatValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VBookDifferentialFixturesTest {
    @Test
    fun importsOfficialCurrentTestResponseWithoutFlatteningContinuation() {
        val capture = VBookOfficialTestCapture(
            caseId = "search-cursor",
            artifactId = "fixture.current",
            profile = VBookContractProfile.CURRENT_JS,
            script = "search.js",
            args = listOf("kiem tien", "cursor-a/b?c=1"),
            responseJson = """{
              "code":200,
              "log":"",
              "data":"{\"code\":0,\"data\":[{\"name\":\"A\"}],\"data2\":\"cursor-x/y?z=2\"}"
            }""",
        )
        val case = VBookDifferentialFixtures.caseFromOfficialCapture(capture)
        assertEquals("cursor-x/y?z=2", case.expected.continuation)
        assertTrue(case.expected.data is CompatValue.ArrayValue)
    }

    @Test
    fun importsOfficialLegacyResponseUnderLegacyContractOnly() {
        val capture = VBookOfficialTestCapture(
            caseId = "legacy",
            artifactId = "fixture.legacy",
            profile = VBookContractProfile.LEGACY_JS,
            script = "home.js",
            args = emptyList(),
            responseJson = """{"code":200,"log":"","data":"{\"code\":200,\"data\":[],\"data2\":1}"}""",
        )
        val case = VBookDifferentialFixtures.caseFromOfficialCapture(capture)
        assertEquals("1", case.expected.continuation)
    }
}
