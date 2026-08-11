#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def patch(path: str, old: str, new: str, count: int = 1) -> None:
    file = ROOT / path
    source = file.read_text(encoding="utf-8")
    found = source.count(old)
    if found != count:
        raise SystemExit(f"{path}: expected {count}, found {found}: {old[:120]!r}")
    file.write_text(source.replace(old, new, count), encoding="utf-8")

patch(
    "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt",
    '''        if (autoVoiceCastEnabled && prefetchNarrationPlansEnabled) {''',
    '''        if (currentStoryAutoVoiceCastEnabled && prefetchNarrationPlansEnabled) {''',
    count=2,
)
patch(
    "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt",
    '''            } else if (autoVoiceCastEnabled && prefetchNarrationPlansEnabled &&''',
    '''            } else if (currentStoryAutoVoiceCastEnabled && prefetchNarrationPlansEnabled &&''',
)

patch(
    "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt",
    '''import vn.nghetruyen.app.NgheTruyenApplication\nimport vn.nghetruyen.app.ai.vietphrase.VietPhraseDiagnosticExport''',
    '''import vn.nghetruyen.app.NgheTruyenApplication\nimport vn.nghetruyen.app.ai.StoryVoiceCastMode\nimport vn.nghetruyen.app.ai.StoryVoiceCastReferenceCodec\nimport vn.nghetruyen.app.ai.vietphrase.VietPhraseDiagnosticExport''',
)
patch(
    "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt",
    '''    val storyId = content.chapter.storyId\n    val activeIndex = state.playback.paragraphIndex.coerceIn(0, content.paragraphs.lastIndex.coerceAtLeast(0))''',
    '''    val storyId = content.chapter.storyId\n    val storyAiProfile = state.storyAiProfiles[storyId]\n    val storyVoiceReference = storyAiProfile?.let { StoryVoiceCastReferenceCodec.decode(it.voiceCastNote) }\n    val effectiveAutoVoiceCastEnabled = state.autoVoiceCastEnabled && when {\n        storyAiProfile == null -> true\n        !StoryVoiceCastReferenceCodec.hasStoredSettings(storyAiProfile.voiceCastNote) -> true\n        storyVoiceReference?.mode == StoryVoiceCastMode.OFF -> false\n        else -> storyVoiceReference?.autoRunOnOpenTts == true\n    }\n    val activeIndex = state.playback.paragraphIndex.coerceIn(0, content.paragraphs.lastIndex.coerceAtLeast(0))''',
)
patch(
    "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt",
    '''            if (state.autoVoiceCastEnabled) {''',
    '''            if (effectiveAutoVoiceCastEnabled) {''',
)

patch(
    "scripts/check_xpk_strict_parity.py",
    '''    "if (state.autoVoiceCastEnabled)",''',
    '''    "if (effectiveAutoVoiceCastEnabled)",\n    "StoryVoiceCastReferenceCodec.hasStoredSettings",''',
)
patch(
    "scripts/check_xpk_strict_parity.py",
    '''    "Phân vai chưa thành công. Sẽ tự thử lại sau 5 giây.",\n)''',
    '''    "Phân vai chưa thành công. Sẽ tự thử lại sau 5 giây.",\n    "if (currentStoryAutoVoiceCastEnabled && prefetchNarrationPlansEnabled)",\n)''',
)
patch(
    "scripts/check_xpk_strict_parity.py",
    '''    "Phân vai tự động chưa thành công; đang đọc bằng cấu hình/phân vai hiện có.",\n)''',
    '''    "Phân vai tự động chưa thành công; đang đọc bằng cấu hình/phân vai hiện có.",\n    "if (autoVoiceCastEnabled && prefetchNarrationPlansEnabled)",\n)''',
)

Path(__file__).unlink()
print("EFFECTIVE_AUTO_UI_FIX=APPLIED")
