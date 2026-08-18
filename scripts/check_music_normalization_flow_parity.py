#!/usr/bin/env python3
from pathlib import Path

worker = Path("app/src/main/java/vn/nghetruyen/app/audio/SceneMusicAnalysisWorker.kt").read_text()
reader = Path("app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt").read_text()
component = Path("app/src/main/java/vn/nghetruyen/app/ui/components/AudioDirectionLayerSwitches.kt").read_text()
manager = Path("app/src/main/java/vn/nghetruyen/app/ui/components/UnifiedAudioAssetManagerDialog.kt").read_text()
preferences = Path("app/src/main/java/vn/nghetruyen/app/audio/AudioDirectionPreferences.kt").read_text()
personal = Path("app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt").read_text()

for token in [
    "KEY_TARGET_LUFS",
    "KEY_FORCE_REMEASURE",
    "targetLufs: Float? = null",
    "forceRemeasure: Boolean = false",
    "inputData.getBoolean(KEY_FORCE_REMEASURE, false)",
    "invalidateStoredNormalization(trackId, target)",
    "if (!forceRemeasure &&",
    "return request.id",
    "fun cancel(context: Context, workId: UUID)",
]:
    if token not in worker:
        raise SystemExit("MUSIC_NORMALIZE_FLOW worker missing: " + token)

for token in [
    'title = "Attack"',
    'title = "Release"',
    'label = "CHUẨN HÓA TOÀN BỘ ÂM THANH"',
    'title = { Text("CHUẨN HÓA TOÀN BỘ ÂM THANH") }',
    'title = "Nhạc nền ($musicCount tệp)"',
    'title = "Âm thanh môi trường ($ambienceCount tệp)"',
    'title = "Hiệu ứng âm thanh ($sfxCount tệp)"',
    "AudioAssetKind.MUSIC -> musicTarget",
    "AudioAssetKind.AMBIENCE -> ambienceTarget",
    "AudioAssetKind.SFX -> sfxTarget",
    "forceRemeasure = forceRemeasure",
    "startNormalization(forceRemeasure = false)",
    "startNormalization(forceRemeasure = true)",
    'Text("ĐO LẠI TỪ ĐẦU")',
    "WorkInfo.State.SUCCEEDED",
    "WorkInfo.State.FAILED",
    "WorkInfo.State.CANCELLED",
    "getWorkInfoById(id).get()",
    "SceneMusicAnalysisWorker.cancel(context, it)",
    'title = { Text("ĐANG CHUẨN HÓA ÂM THANH") }',
    'Text("Nhạc nền: %.0f LUFS"',
    'Text("Môi trường: %.0f LUFS"',
    'Text("Hiệu ứng: %.0f LUFS"',
    'Text("Hoàn tất: $finished / $total")',
]:
    if token not in component:
        raise SystemExit("MUSIC_NORMALIZE_FLOW component missing: " + token)

for token in [
    "settingsRepository.setSceneMusicTargetLufs(musicTarget)",
    "preferences.setAmbienceNormalizationTargetLufs(ambienceTarget)",
    "preferences.setSoundEffectsNormalizationTargetLufs(sfxTarget)",
    "settingsRepository.snapshot().sceneMusicTargetLufs",
    "preferences.snapshot()",
    "abs(savedMusic - musicTarget) < 0.01f",
    "settingsRepository.setBackgroundMusicAttackMillis(attack)",
    "settingsRepository.setBackgroundMusicReleaseMillis(release)",
    "latestDynamicsDirty",
    "normalizationTargetLufs = target",
]:
    if token not in component:
        raise SystemExit("MUSIC_NORMALIZE_FLOW setting persistence missing: " + token)

for token in [
    "ambienceNormalizationTargetLufs",
    "soundEffectsNormalizationTargetLufs",
    "KEY_AMBIENCE_NORMALIZATION_TARGET_LUFS",
    "KEY_SFX_NORMALIZATION_TARGET_LUFS",
    "DEFAULT_AMBIENCE_NORMALIZATION_TARGET_LUFS = -27f",
    "DEFAULT_SFX_NORMALIZATION_TARGET_LUFS = -20f",
    "fun setAmbienceNormalizationTargetLufs",
    "fun setSoundEffectsNormalizationTargetLufs",
    ").commit()",
]:
    if token not in preferences:
        raise SystemExit("MUSIC_NORMALIZE_FLOW preferences missing: " + token)

for token in [
    "normalizationTargetLufs: Float",
    "val normalizationTarget = normalizationTargetLufs",
    "SceneMusicAnalysisWorker.enqueue(context, trackId, normalizationTarget)",
    "SceneMusicAnalysisWorker.enqueue(context, track.id, normalizationTarget)",
    "track.peakDbfs,\n                                    normalizationTarget,",
]:
    if token not in manager:
        raise SystemExit("MUSIC_NORMALIZE_FLOW per-file manager missing: " + token)

for forbidden in [
    'label = "CHUẨN HÓA TOÀN BỘ KHO NHẠC"',
    'title = "Mức chuẩn hóa"',
    "normalizationTarget(kind)",
]:
    if forbidden in component + manager:
        raise SystemExit("MUSIC_NORMALIZE_FLOW obsolete single-target flow remains: " + forbidden)

# The normalize-all dialog is the only user-facing editor for the music LUFS target.
# Personal/settings screens may still receive legacy callback parameters for source compatibility,
# but they must not render a second target control that can race or overwrite the authoritative value.
for forbidden in [
    'label = "Mức chuẩn hóa nhạc"',
    'shown = { "%.1f LUFS".format(it) },\n                onChange = onSceneMusicTargetLufsChange',
]:
    if forbidden in personal:
        raise SystemExit("MUSIC_NORMALIZE_FLOW duplicate settings target control remains: " + forbidden)

music_start = reader.find('    if (showMusicDialog) {')
library_start = reader.find('    if (showMusicLibrary) {', music_start)
if music_start < 0 or library_start < 0:
    raise SystemExit("MUSIC_NORMALIZE_FLOW Reader music UI region missing")

# The old Reader progress dialog may remain as unreachable compatibility code, but the
# active normalize-all flow must be owned by AudioDirectionLayerSwitches. Reintroducing
# an activation path to the legacy dialog would restore the duplicate/verbose workflow.
if "showMusicNormalizationProgress = true" in reader:
    raise SystemExit("MUSIC_NORMALIZE_FLOW legacy Reader normalization flow is reachable again")

print("MUSIC_NORMALIZATION_FLOW_PARITY=PASS")
