package vn.nghetruyen.source.vbook

data class VBookDifferentialCaseOutcome(
    val caseId: String,
    val features: Set<VBookFeature>,
    val matchesReference: Boolean,
    val detail: String = "",
)

data class VBookCertificationReport(
    val certifications: List<VBookFeatureCertification>,
    val passedCases: Set<String>,
    val failedCases: Set<String>,
) {
    val fullyPassing: Boolean get() = failedCases.isEmpty()
}

object VBookCertificationEngine {
    fun certify(
        requiredFeatures: Set<VBookFeature>,
        outcomes: Collection<VBookDifferentialCaseOutcome>,
    ): VBookCertificationReport {
        require(outcomes.map(VBookDifferentialCaseOutcome::caseId).distinct().size == outcomes.size) {
            "VBOOK_DIFFERENTIAL_CASE_ID_DUPLICATE"
        }
        val certifications = requiredFeatures.sortedBy(Enum<*>::name).map { feature ->
            val cases = outcomes.filter { feature in it.features }
            when {
                cases.isEmpty() -> VBookFeatureCertification(
                    feature = feature,
                    state = if (VBookEngineFeatureMatrix.support(feature).implementation == VBookFeatureImplementationLevel.REFERENCE_REJECTS)
                        VBookFeatureCertificationState.NOT_APPLICABLE else VBookFeatureCertificationState.UNTESTED,
                    detail = "No differential case covers this required feature.",
                )
                cases.any { !it.matchesReference } -> VBookFeatureCertification(
                    feature = feature,
                    state = VBookFeatureCertificationState.DIVERGED,
                    caseIds = cases.mapTo(linkedSetOf(), VBookDifferentialCaseOutcome::caseId),
                    detail = cases.filterNot(VBookDifferentialCaseOutcome::matchesReference)
                        .joinToString("; ") { "${it.caseId}:${it.detail}" }.take(2_000),
                )
                else -> VBookFeatureCertification(
                    feature = feature,
                    state = VBookFeatureCertificationState.CERTIFIED,
                    caseIds = cases.mapTo(linkedSetOf(), VBookDifferentialCaseOutcome::caseId),
                    detail = "All ${cases.size} covering differential case(s) match the reference.",
                )
            }
        }
        return VBookCertificationReport(
            certifications = certifications,
            passedCases = outcomes.filter(VBookDifferentialCaseOutcome::matchesReference).mapTo(linkedSetOf(), VBookDifferentialCaseOutcome::caseId),
            failedCases = outcomes.filterNot(VBookDifferentialCaseOutcome::matchesReference).mapTo(linkedSetOf(), VBookDifferentialCaseOutcome::caseId),
        )
    }
}
