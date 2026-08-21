from pathlib import Path

path = Path('.github/scripts/improve_audio_selection_continuity_20260821.py')
text = path.read_text(encoding='utf-8')
slash = chr(92)
needle = '.joinToString("' + slash + 'n") { "- $it" }'
replacement = '.joinToString("' + slash + slash + 'n") { "- $it" }'
if replacement in text:
    print('Continuity patch newline escaping already prepared.')
elif needle in text:
    path.write_text(text.replace(needle, replacement, 1), encoding='utf-8')
    print('Escaped Kotlin newline literal inside continuity patch.')
else:
    raise SystemExit('Không tìm thấy joinToString newline cần sửa trong continuity patch.')
