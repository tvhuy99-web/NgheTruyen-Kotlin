package vn.nghetruyen.source.vbook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VBookFeatureMatrixTest {
    @Test
    fun implementedFeaturesStillBlockFullParityUntilReferenceCertified() {
        val report = VBookCorpusReport(
            extensionCount = 1,
            profiles = mapOf(VBookContractProfile.CURRENT_JS to 1),
            contentTypes = mapOf(VBookContentType.NOVEL to 1),
            features = listOf(
                VBookCorpusFeatureRow(VBookFeature.HTML_MUTATION, 1, listOf("x")),
                VBookCorpusFeatureRow(VBookFeature.BROWSER_WAIT_URL, 1, listOf("x")),
            ),
            extensionsWithMissingRequiredScripts = emptyList(),
            extensionsWithMissingDynamicScripts = emptyList(),
        )
        val matrix = VBookEngineFeatureMatrix.matrix(report)
        assertTrue(matrix.blockingFeatures.isEmpty())
        assertTrue(matrix.uncertifiedRequiredFeatures.any { it.feature == VBookFeature.HTML_MUTATION })
        assertFalse(matrix.canClaimFullCorpusParity)

        val certified = VBookEngineFeatureMatrix.matrix(
            report,
            certifications = listOf(
                VBookFeatureCertification(VBookFeature.HTML_MUTATION, VBookFeatureCertificationState.CERTIFIED, setOf("dom-remove")),
                VBookFeatureCertification(VBookFeature.BROWSER_WAIT_URL, VBookFeatureCertificationState.CERTIFIED, setOf("browser-wait-url")),
            ),
        )
        assertTrue(certified.canClaimFullCorpusParity)
    }

    @Test
    fun partialWebsocketOrQuickTranslatorBlocksWhenCorpusUsesIt() {
        val report = VBookCorpusReport(
            extensionCount = 2,
            profiles = mapOf(VBookContractProfile.CURRENT_JS to 2),
            contentTypes = mapOf(VBookContentType.TTS to 1, VBookContentType.NOVEL to 1),
            features = listOf(
                VBookCorpusFeatureRow(VBookFeature.WEBSOCKET_FRAMES, 1, listOf("tts")),
                VBookCorpusFeatureRow(VBookFeature.QUICK_TRANSLATOR, 1, listOf("novel")),
            ),
            extensionsWithMissingRequiredScripts = emptyList(),
            extensionsWithMissingDynamicScripts = emptyList(),
        )
        val matrix = VBookEngineFeatureMatrix.matrix(report)
        assertTrue(matrix.blockingFeatures.any { it.feature == VBookFeature.WEBSOCKET_FRAMES })
        assertTrue(matrix.blockingFeatures.any { it.feature == VBookFeature.QUICK_TRANSLATOR })
        assertFalse(matrix.canClaimFullCorpusParity)
    }
}
