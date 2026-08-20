package vn.nghetruyen.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Mode3FastNormalizationPolicyTest {
    @Test
    fun mode3AnalysisWindowsAreBoundedByKind() {
        assertEquals(45_000_000L, SceneMusicAnalysisWorker.fastAnalysisDurationUs(AudioAssetKind.MUSIC))
        assertEquals(30_000_000L, SceneMusicAnalysisWorker.fastAnalysisDurationUs(AudioAssetKind.AMBIENCE))
        assertEquals(15_000_000L, SceneMusicAnalysisWorker.fastAnalysisDurationUs(AudioAssetKind.SFX))
        assertTrue(SceneMusicAnalysisWorker.fastAnalysisDurationUs(AudioAssetKind.AMBIENCE) < 60_000_000L)
    }
}
