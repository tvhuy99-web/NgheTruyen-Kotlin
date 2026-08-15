#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
personal = (ROOT / "app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt").read_text(encoding="utf-8")
component = (ROOT / "app/src/main/java/vn/nghetruyen/app/ui/components/AudioDirectionLayerSwitches.kt").read_text(encoding="utf-8")
debug_strings = (ROOT / "app/src/debug/res/values/strings.xml").read_text(encoding="utf-8")
ui_test = (ROOT / "app/src/androidTest/java/vn/nghetruyen/app/AudioDirectorMusicSettingsUiTest.kt").read_text(encoding="utf-8")


def section(text: str, start: str, end: str) -> str:
    start_index = text.find(start)
    if start_index < 0:
        raise AssertionError(f"missing section start: {start}")
    end_index = text.find(end, start_index + len(start))
    if end_index < 0:
        raise AssertionError(f"missing section end: {end}")
    return text[start_index:end_index]


music_page = section(
    personal,
    '"settings_music" -> PersonalSubPage("NHẠC NỀN & NHẠC CẢNH")',
    '"settings_following" -> PersonalSubPage',
)
assert "BackgroundMusicCard(" in music_page, "music settings must keep local background music controls"
assert "AudioDirectionLayerSwitches(" in music_page, "AI sound controls must render on settings_music"
assert "SceneMusicLibraryCard(" not in music_page, "legacy scene-music manager must not duplicate the unified audio managers"

playback_card = section(
    personal,
    "private fun PlaybackAutomationCard(",
    "private fun ReferenceFloatSettingsSlider(",
)
assert "AudioDirectionLayerSwitches(" not in playback_card, "AI sound asset managers must not live in playback automation"

required_component_tokens = (
    'text = "AI SOUND DIRECTOR"',
    'title = "Nhạc cảnh AI"',
    'title = "Âm thanh môi trường AI"',
    'title = "Hiệu ứng âm thanh AI"',
    'label = "QUẢN LÝ NHẠC',
    'label = "QUẢN LÝ ÂM THANH MÔI TRƯỜNG',
    'label = "QUẢN LÝ HIỆU ỨNG ÂM THANH',
    "ActivityResultContracts.OpenMultipleDocuments()",
    "bringIntoViewRequester.bringIntoView()",
    "BuildConfig.DIAGNOSTIC_BUILD_ID",
)
for token in required_component_tokens:
    assert token in component, f"missing unified audio UI token: {token}"

assert "Nghe Truyện • AI Sound Director" in debug_strings, "debug APK must be visibly distinguishable from the normal app"
for token in (
    'onNodeWithText("CÁ NHÂN"',
    'onNodeWithText("Cài đặt"',
    'hasText("NHẠC NỀN & NHẠC CẢNH")',
    'onNodeWithText("AI SOUND DIRECTOR"',
    'onNodeWithText("Nhạc cảnh AI"',
    'onNodeWithText("Âm thanh môi trường AI"',
    'onNodeWithText("Hiệu ứng âm thanh AI"',
):
    assert token in ui_test, f"real navigation UI test missing token: {token}"

print("AUDIO_DIRECTION_UI_WIRING=PASS")
