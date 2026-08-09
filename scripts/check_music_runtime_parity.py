#!/usr/bin/env python3
from pathlib import Path

db = Path("app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt").read_text()
repo = Path("app/src/main/java/vn/nghetruyen/app/data/repository/LibraryRepository.kt").read_text()
estimator = Path("app/src/main/java/vn/nghetruyen/app/audio/PcmLoudnessEstimator.kt").read_text()
worker = Path("app/src/main/java/vn/nghetruyen/app/audio/SceneMusicAnalysisWorker.kt").read_text()
service = Path("app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt").read_text()
reader = Path("app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt").read_text()

for token in ["version = 23", "peakDbfs", "normalizationTargetLufs", "normalizationGainDb", "normalizationPeakLimited", "normalizationVersion", "normalizationError", "MIGRATION_22_23"]:
    if token not in db: raise SystemExit("MUSIC_RUNTIME missing DB token: " + token)
for token in ["updateSceneMusicNormalization", "markSceneMusicNormalizationError", "tagsCsv = tagsCsv.trim().take(300)"]:
    if token not in repo: raise SystemExit("MUSIC_RUNTIME missing repository behavior: " + token)
if "tagsCsv.split(',')" in repo: raise SystemExit("MUSIC_RUNTIME repository still splits freeform descriptions")
for token in ["VERSION = 1", "DEFAULT_TARGET_LUFS = -24f", "PEAK_CEILING_DBFS = -1f", "shelfCoefficients", "highPassCoefficients", "integratedLoudness", "calculateNormalization", "gainDbToLinear"]:
    if token not in estimator: raise SystemExit("MUSIC_RUNTIME missing normalizer behavior: " + token)
for token in ["KEY_REUSED_MEASUREMENT", "track.normalizationVersion", "track.peakDbfs", "updateSceneMusicNormalization", "KEY_TARGET_LUFS", "targetLufs: Float? = null"]:
    if token not in worker: raise SystemExit("MUSIC_RUNTIME worker cannot reuse measurement/draft target: " + token)
for token in ["ACTION_MUSIC_PREVIEW_BEGIN", "ACTION_MUSIC_PREVIEW_END", "beginMusicPreview", "endMusicPreview", "track.normalizationGainDb", "PcmLoudnessEstimator.isReady"]:
    if token not in service: raise SystemExit("MUSIC_RUNTIME service parity missing: " + token)
for token in ["delay(15_000)", "ACTION_MUSIC_PREVIEW_BEGIN", "ACTION_MUSIC_PREVIEW_END", "calculateNormalization", "SceneMusicAnalysisWorker.enqueue(context, it.id, musicTargetLufs)"]:
    if token not in reader: raise SystemExit("MUSIC_RUNTIME preview/recalc missing: " + token)
print("MUSIC_RUNTIME_PARITY=PASS")
