#!/usr/bin/env python3
from pathlib import Path

db = Path("app/src/main/java/vn/nghetruyen/app/data/local/AppDatabase.kt").read_text()
repo = Path("app/src/main/java/vn/nghetruyen/app/data/repository/LibraryRepository.kt").read_text()
estimator = Path("app/src/main/java/vn/nghetruyen/app/audio/PcmLoudnessEstimator.kt").read_text()
worker = Path("app/src/main/java/vn/nghetruyen/app/audio/SceneMusicAnalysisWorker.kt").read_text()
service = Path("app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt").read_text()
reader = Path("app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt").read_text()
layer_switches = Path("app/src/main/java/vn/nghetruyen/app/ui/components/AudioDirectionLayerSwitches.kt").read_text()
asset_manager = Path("app/src/main/java/vn/nghetruyen/app/ui/components/UnifiedAudioAssetManagerDialog.kt").read_text()

for token in ["version = 23", "peakDbfs", "normalizationTargetLufs", "normalizationGainDb", "normalizationPeakLimited", "normalizationVersion", "normalizationError", "MIGRATION_22_23"]:
    if token not in db: raise SystemExit("MUSIC_RUNTIME missing DB token: " + token)
for token in ["updateSceneMusicNormalization", "markSceneMusicNormalizationError", "tagsCsv = tagsCsv.trim().take(300)"]:
    if token not in repo: raise SystemExit("MUSIC_RUNTIME missing repository behavior: " + token)
if "tagsCsv.split(',')" in repo: raise SystemExit("MUSIC_RUNTIME repository still splits freeform descriptions")
for token in ["VERSION = 1", "DEFAULT_TARGET_LUFS = -24f", "PEAK_CEILING_DBFS = -1f", "shelfCoefficients", "highPassCoefficients", "integratedLoudness", "calculateNormalization", "gainDbToLinear"]:
    if token not in estimator: raise SystemExit("MUSIC_RUNTIME missing normalizer behavior: " + token)
for token in [
    "KEY_REUSED_MEASUREMENT",
    "track.normalizationVersion",
    "track.peakDbfs",
    "KEY_TARGET_LUFS",
    "targetLufs: Float? = null",
    "AudioDirectionPreferences.shared(applicationContext)",
    "PcmLoudnessEstimator.MAX_MUSIC_TARGET_LUFS",
    "PcmLoudnessEstimator.MAX_TARGET_LUFS",
    "container.database.sceneMusicTrackDao().upsert(",
    "PcmLoudnessEstimator.MIN_GAIN_DB",
    "PcmLoudnessEstimator.MAX_GAIN_DB",
]:
    if token not in worker: raise SystemExit("MUSIC_RUNTIME worker cannot preserve current normalization semantics: " + token)
for token in ["ACTION_MUSIC_PREVIEW_BEGIN", "ACTION_MUSIC_PREVIEW_END", "beginMusicPreview", "endMusicPreview", "track.normalizationGainDb", "PcmLoudnessEstimator.isReady"]:
    if token not in service: raise SystemExit("MUSIC_RUNTIME service parity missing: " + token)
for token in ["delay(15_000)", "ACTION_MUSIC_PREVIEW_BEGIN", "ACTION_MUSIC_PREVIEW_END", "calculateNormalization"]:
    if token not in reader: raise SystemExit("MUSIC_RUNTIME reader preview missing: " + token)
for forbidden in [
    "settings.setSceneMusicTargetLufs(musicTargetLufs)",
    "SceneMusicAnalysisWorker.enqueue(context, it.id, musicTargetLufs)",
]:
    if forbidden in reader: raise SystemExit("MUSIC_RUNTIME stale Reader music target overwrite returned: " + forbidden)
for token in [
    "settingsRepository.setSceneMusicTargetLufs(musicTarget)",
    "preferences.setAmbienceNormalizationTargetLufs(ambienceTarget)",
    "preferences.setSoundEffectsNormalizationTargetLufs(sfxTarget)",
    "check(abs(savedMusic - musicTarget) < 0.01f)",
    "AudioAssetClassifier.classify(track)",
    "targetLufs = target",
    "forceRemeasure = forceRemeasure",
]:
    if token not in layer_switches: raise SystemExit("MUSIC_RUNTIME canonical normalization flow missing: " + token)
for token in [
    "fun stageMove(track: SceneMusicTrackEntity, destination: AudioAssetKind)",
    "stripAssetTypeMarkers(track.title)",
    "tagsWithDescription(destination, description)",
    "dao.upsertAll(normalized + movedRows)",
    "SceneMusicAnalysisWorker.enqueue(context, moved.id, target)",
]:
    if token not in asset_manager: raise SystemExit("MUSIC_RUNTIME category move parity missing: " + token)
print("MUSIC_RUNTIME_PARITY=PASS")
