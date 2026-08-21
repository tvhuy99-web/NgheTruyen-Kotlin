from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def replace_once(path: Path, old: str, new: str, label: str):
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


retry_test = ROOT / "app/src/test/java/vn/nghetruyen/app/freesound/FreesoundRetryQueryTest.kt"
replace_once(
    retry_test,
    '''    @Test\n    fun secondAttemptKeepsThreeCoreTerms() {\n        assertEquals(\n            "chinese flute music",\n            FreesoundAutoAudioResolver.searchQueryForRetry("lighthearted comedic chinese flute music", 2),\n        )\n        assertEquals(\n            "landing thud wood",\n            FreesoundAutoAudioResolver.searchQueryForRetry("heavy landing thud on wood", 2),\n        )\n        assertEquals(\n            "coin drop wood",\n            FreesoundAutoAudioResolver.searchQueryForRetry("single gold coin drop on wood", 2),\n        )\n    }\n\n    @Test\n    fun thirdAttemptKeepsTwoBroadCoreTerms() {\n        assertEquals(\n            "flute music",\n            FreesoundAutoAudioResolver.searchQueryForRetry("lighthearted comedic chinese flute music", 3),\n        )\n        assertEquals(\n            "debris crash",\n            FreesoundAutoAudioResolver.searchQueryForRetry("wall breaking debris crash", 3),\n        )\n        assertEquals(\n            "thud wood",\n            FreesoundAutoAudioResolver.searchQueryForRetry("heavy landing thud on wood", 3),\n        )\n    }\n''',
    '''    @Test\n    fun secondAttemptKeepsTwoBroadCoreTerms() {\n        assertEquals(\n            "flute music",\n            FreesoundAutoAudioResolver.searchQueryForRetry("lighthearted comedic chinese flute music", 2),\n        )\n        assertEquals(\n            "thud wood",\n            FreesoundAutoAudioResolver.searchQueryForRetry("heavy landing thud on wood", 2),\n        )\n        assertEquals(\n            "drop wood",\n            FreesoundAutoAudioResolver.searchQueryForRetry("single gold coin drop on wood", 2),\n        )\n    }\n\n    @Test\n    fun thirdAttemptKeepsOneBroadestCoreTerm() {\n        assertEquals(\n            "music",\n            FreesoundAutoAudioResolver.searchQueryForRetry("lighthearted comedic chinese flute music", 3),\n        )\n        assertEquals(\n            "crash",\n            FreesoundAutoAudioResolver.searchQueryForRetry("wall breaking debris crash", 3),\n        )\n        assertEquals(\n            "wood",\n            FreesoundAutoAudioResolver.searchQueryForRetry("heavy landing thud on wood", 3),\n        )\n    }\n''',
    "retry query test expectations",
)

mode3_test = ROOT / "app/src/test/java/vn/nghetruyen/app/freesound/FreesoundMode3RegressionTest.kt"
replace_once(
    mode3_test,
    '''        val long = FreesoundAutoRequirement(\n            kind = AudioAssetKind.SFX,\n            query = "debris wall crash",\n            unitId = "U1",\n        )\n        val short = long.copy(query = "debris crash", unitId = "U2")\n        val need = FreesoundAutoRequirementAggregator.aggregate(listOf(long, short)).single()\n        assertEquals("debris crash", need.query)\n''',
    '''        val long = FreesoundAutoRequirement(\n            kind = AudioAssetKind.SFX,\n            query = "metal debris wall stone crash",\n            unitId = "U1",\n        )\n        val short = long.copy(query = "debris wall stone crash", unitId = "U2")\n        val need = FreesoundAutoRequirementAggregator.aggregate(listOf(long, short)).single()\n        assertEquals("debris wall stone crash", need.query)\n''',
    "aggregator equivalence test",
)

print("Mode 3 V11 regression expectations updated.")
