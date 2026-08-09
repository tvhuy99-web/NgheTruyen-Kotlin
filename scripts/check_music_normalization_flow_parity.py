#!/usr/bin/env python3
from pathlib import Path
worker = Path("app/src/main/java/vn/nghetruyen/app/audio/SceneMusicAnalysisWorker.kt").read_text()
reader = Path("app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt").read_text()
for token in ["KEY_TARGET_LUFS", "targetLufs: Float? = null", "return request.id", "fun cancel(context: Context, workId: UUID)"]:
    if token not in worker: raise SystemExit("MUSIC_NORMALIZE_FLOW worker missing: " + token)
for token in ["SceneMusicAnalysisWorker.enqueue(context, track.id, musicTargetLufs)", "CHUẨN HÓA KHO NHẠC", "WorkInfo.State.SUCCEEDED", "WorkInfo.State.FAILED", "WorkInfo.State.CANCELLED", "getWorkInfoById(id).get()", "SceneMusicAnalysisWorker.cancel(context, it)", "Mục tiêu: %.0f LUFS", "không giải mã lại"]:
    if token not in reader: raise SystemExit("MUSIC_NORMALIZE_FLOW reader missing: " + token)
menu = reader[reader.find('    if (showReaderOptions) {'):]
music = menu[menu.find('    if (showMusicDialog) {'):]
progress_pos = music.find('    if (showMusicNormalizationProgress) {')
library_pos = music.find('    if (showMusicLibrary) {')
if progress_pos < 0 or library_pos < 0 or progress_pos > library_pos:
    raise SystemExit("MUSIC_NORMALIZE_FLOW progress dialog is not in the dialog UI region")
print("MUSIC_NORMALIZATION_FLOW_PARITY=PASS")
