#!/usr/bin/env python3
from pathlib import Path

worker = Path("app/src/main/java/vn/nghetruyen/app/audio/SceneMusicAnalysisWorker.kt").read_text()
reader = Path("app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt").read_text()
component = Path("app/src/main/java/vn/nghetruyen/app/ui/components/AudioDirectionLayerSwitches.kt").read_text()

for token in [
    "KEY_TARGET_LUFS",
    "targetLufs: Float? = null",
    "return request.id",
    "fun cancel(context: Context, workId: UUID)",
]:
    if token not in worker:
        raise SystemExit("MUSIC_NORMALIZE_FLOW worker missing: " + token)

for token in [
    'title = "Mức chuẩn hóa"',
    'title = "Attack"',
    'title = "Release"',
    'label = "CHUẨN HÓA TOÀN BỘ KHO NHẠC"',
    "SceneMusicAnalysisWorker.enqueue(context, track.id, target)",
    "WorkInfo.State.SUCCEEDED",
    "WorkInfo.State.FAILED",
    "WorkInfo.State.CANCELLED",
    "getWorkInfoById(id).get()",
    "SceneMusicAnalysisWorker.cancel(context, it)",
    'title = { Text("CHUẨN HÓA KHO NHẠC") }',
    'Text("Mục tiêu: %.0f LUFS"',
    'Text("Hoàn tất: $finished / $total")',
]:
    if token not in component:
        raise SystemExit("MUSIC_NORMALIZE_FLOW component missing: " + token)

for token in [
    "settingsRepository.setSceneMusicTargetLufs(target)",
    "settingsRepository.setBackgroundMusicAttackMillis(attack)",
    "settingsRepository.setBackgroundMusicReleaseMillis(release)",
    "latestNormalizationDirty",
]:
    if token not in component:
        raise SystemExit("MUSIC_NORMALIZE_FLOW setting persistence missing: " + token)

music_start = reader.find('    if (showMusicDialog) {')
progress_start = reader.find('    if (showMusicNormalizationProgress) {', music_start)
library_start = reader.find('    if (showMusicLibrary) {', music_start)
if music_start < 0 or library_start < 0:
    raise SystemExit("MUSIC_NORMALIZE_FLOW Reader music UI region missing")
if progress_start >= 0 and progress_start > library_start:
    raise SystemExit("MUSIC_NORMALIZE_FLOW legacy progress dialog moved outside music UI region")

print("MUSIC_NORMALIZATION_FLOW_PARITY=PASS")
