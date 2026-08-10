package vn.nghetruyen.source.vbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VBookCertificationTest {
    @Test
    fun featureIsCertifiedOnlyWhenEveryCoveringCaseMatchesReference() {
        val report = VBookCertificationEngine.certify(
            requiredFeatures = setOf(VBookFeature.FETCH, VBookFeature.FETCH_CHARSET, VBookFeature.BROWSER),
            outcomes = listOf(
                VBookDifferentialCaseOutcome("fetch-basic", setOf(VBookFeature.FETCH), true),
                VBookDifferentialCaseOutcome("fetch-gbk", setOf(VBookFeature.FETCH, VBookFeature.FETCH_CHARSET), true),
                VBookDifferentialCaseOutcome("browser-redirect", setOf(VBookFeature.BROWSER), false, "finalUrl mismatch"),
            ),
        )

        val byFeature = report.certifications.associateBy(VBookFeatureCertification::feature)
        assertEquals(VBookFeatureCertificationState.CERTIFIED, byFeature.getValue(VBookFeature.FETCH).state)
        assertEquals(VBookFeatureCertificationState.CERTIFIED, byFeature.getValue(VBookFeature.FETCH_CHARSET).state)
        assertEquals(VBookFeatureCertificationState.DIVERGED, byFeature.getValue(VBookFeature.BROWSER).state)
        assertFalse(report.fullyPassing)
    }

    @Test
    fun implementedButUncoveredFeatureRemainsUntested() {
        val report = VBookCertificationEngine.certify(
            requiredFeatures = setOf(VBookFeature.CRYPTO),
            outcomes = emptyList(),
        )
        assertEquals(VBookFeatureCertificationState.UNTESTED, report.certifications.single().state)
        assertTrue(report.failedCases.isEmpty())
    }
}
