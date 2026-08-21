from pathlib import Path

path = Path(__file__).resolve().parents[2] / "app/src/test/java/vn/nghetruyen/app/audio/Mode3FastNormalizationPolicyTest.kt"
text = path.read_text(encoding="utf-8")
old = '''        assertEquals(45_000_000L, SceneMusicAnalysisWorker.fastAnalysisDurationUs(AudioAssetKind.MUSIC))
        assertEquals(30_000_000L, SceneMusicAnalysisWorker.fastAnalysisDurationUs(AudioAssetKind.AMBIENCE))
        assertEquals(15_000_000L, SceneMusicAnalysisWorker.fastAnalysisDurationUs(AudioAssetKind.SFX))
'''
new = '''        assertEquals(24_000_000L, SceneMusicAnalysisWorker.fastAnalysisDurationUs(AudioAssetKind.MUSIC))
        assertEquals(20_000_000L, SceneMusicAnalysisWorker.fastAnalysisDurationUs(AudioAssetKind.AMBIENCE))
        assertEquals(10_000_000L, SceneMusicAnalysisWorker.fastAnalysisDurationUs(AudioAssetKind.SFX))
'''
if text.count(old) != 1:
    raise RuntimeError(f"expected one V12 normalization-window assertion block, found {text.count(old)}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
print("V14 normalization policy regression test updated.")
