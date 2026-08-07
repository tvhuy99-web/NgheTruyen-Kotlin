#!/usr/bin/env python3
from pathlib import Path


def read(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    Path(path).write_text(text, encoding="utf-8")


# Personal: finish the partially persisted two-level navigation cleanly.
personal = "app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt"
text = read(personal)
architecture = '        SettingsCard("Kiến trúc ứng dụng", "Kotlin, Compose, Room, DataStore, WorkManager và foreground TTS service. Lua Native Source API 2 chạy trong LuaJ sandbox; không AndroLua, không luajava và không nạp DEX động.")\n'
if text.count(architecture) > 1:
    last = text.rfind(architecture)
    text = text[:last] + text[last + len(architecture):]
old_tail = '''            onClearSession = onClearSourceSession,
        )
    }
}


private val MEDIA_ACTION_ORDER'''
new_tail = '''            onClearSession = onClearSourceSession,
        )
        }
    }
}


private val MEDIA_ACTION_ORDER'''
if old_tail in text:
    text = text.replace(old_tail, new_tail, 1)
elif new_tail not in text:
    raise SystemExit("PersonalScreen.kt: could not finalize page structure")
write(personal, text)

# Story detail: advanced configuration should contain configuration only. The compact
# TÙY CHỌN dialog already owns follow/download/export/profile actions.
story = "app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt"
text = read(story)
advanced_start = text.find('        if (showAdvancedOptions) {\n')
voice_card = text.find('        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {\n', advanced_start)
if advanced_start >= 0 and voice_card >= 0:
    close_button_end_marker = '''                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            )
'''
    close_end = text.find(close_button_end_marker, advanced_start)
    if close_end < 0:
        raise SystemExit("StoryDetailScreen.kt: advanced close button marker missing")
    close_end += len(close_button_end_marker)
    middle = text[close_end:voice_card]
    if 'TẢI CHƯA ĐỌC' in middle or 'XUẤT SÁCH NÓI' in middle:
        text = text[:close_end] + text[voice_card:]
write(story, text)

# Reader: reference order is playback controls first, then AI actions. Use anchor
# positions rather than brittle line fragments, so this stays idempotent.
reader = "app/src/main/java/vn/nghetruyen/app/ui/screens/ReaderScreen.kt"
text = read(reader)
status_sentinel = 'Đang chuẩn bị giọng đọc và nội dung tiếp theo…'
if status_sentinel not in text:
    ai_anchor = '''            Row(modifier = Modifier.fillMaxWidth()) {
                ReaderButton(
                    when {
                        state.aiBusy -> "AI ĐANG CHẠY…"
'''
    player_anchor = '''            Row(modifier = Modifier.fillMaxWidth()) {
                ReaderButton("TRƯỚC", onPreviousChapter'''
    actions_anchor = '            if (showReaderActions) {\n'
    ai_start = text.find(ai_anchor)
    player_start = text.find(player_anchor, ai_start + 1)
    if ai_start < 0 or player_start < 0:
        raise SystemExit("ReaderScreen.kt: AI/playback anchors missing")
    ai_block = text[ai_start:player_start]
    ai_block = ai_block.replace(
        'normalColor = ReferencePurple,\n                    accessibilityLabel = "Phân vai giọng đọc bằng AI",',
        'normalColor = Color(0xFFAF52DE),\n                    accessibilityLabel = "Phân vai giọng đọc bằng AI",',
        1,
    )
    text = text[:ai_start] + text[player_start:]
    actions_index = text.find(actions_anchor, ai_start)
    if actions_index < 0:
        raise SystemExit("ReaderScreen.kt: reader actions anchor missing")
    status = '''            if (state.playback.preparationState == PlaybackPreparationState.PREPARING) {
                Text(
                    "Đang chuẩn bị giọng đọc và nội dung tiếp theo…",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    color = palette.text,
                )
            }
'''
    text = text[:actions_index] + status + ai_block + text[actions_index:]
write(reader, text)

# Guardrails for the final UI structure.
checks = {
    "ExploreScreen.kt": (
        "app/src/main/java/vn/nghetruyen/app/ui/screens/ExploreScreen.kt",
        ['text = "TÌM KIẾM"', 'title = { Text("TÌM KIẾM") }'],
    ),
    "PersonalScreen.kt": (
        personal,
        ['text = "Cài đặt"', 'text = "Tiện ích mở rộng"', 'personalPage == "extensions"'],
    ),
    "StoryDetailScreen.kt": (
        story,
        ['title = { Text("TÙY CHỌN TRUYỆN") }', 'text = "CẤU HÌNH GIỌNG & AI"'],
    ),
    "ReaderScreen.kt": (
        reader,
        ['state.playback.isPlaying -> "TẠM DỪNG"', status_sentinel, '"PHÂN VAI AI"'],
    ),
}
for label, (path, needles) in checks.items():
    body = read(path)
    missing = [needle for needle in needles if needle not in body]
    if missing:
        raise SystemExit(f"{label}: missing final polish markers: {missing}")

print("REFERENCE_UI_POLISH_V2_FINALIZED")
