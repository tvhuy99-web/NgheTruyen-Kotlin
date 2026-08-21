package vn.nghetruyen.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Mode3FastNormalizationPolicyTest {
    @Test
    fun mode3AnalysisWindowsAreBoundedByKind() {
        assertEquals(24_000_000L, SceneMusicAnalysisWorker.fastAnalysisDurationUs(AudioAssetKind.MUSIC))
        assertEquals(20_000_000L, SceneMusicAnalysisWorker.fastAnalysisDurationUs(AudioAssetKind.AMBIENCE))
        assertEquals(10_000_000L, SceneMusicAnalysisWorker.fastAnalysisDurationUs(AudioAssetKind.SFX))
        assertTrue(SceneMusicAnalysisWorker.fastAnalysisDurationUs(AudioAssetKind.AMBIENCE) < 60_000_000L)
        assertEquals(4, SceneMusicAnalysisWorker.MAX_PARALLEL_FREESOUND_ANALYSES)
    }
}
