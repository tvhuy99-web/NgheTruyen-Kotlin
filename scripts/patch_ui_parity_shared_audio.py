from pathlib import Path

path = Path('scripts/check_ui_control_parity.py')
text = path.read_text(encoding='utf-8')
start = text.index('    "AudioDirectionLayerSwitches.kt": [')
end = text.index('    "UnifiedAudioAssetManagerDialog.kt": [', start)
new_block = '''    "AudioDirectionLayerSwitches.kt": [
        "StoryAudioSourceModeSelector(",
        'Text("MODE 1 · PHÁT THỦ CÔNG TỪ THƯ VIỆN LOCAL")',
        'Text("MODE 2 · AI CHỌN TỪ THƯ VIỆN")',
        'Text("MODE 3 · AI TỰ ĐỘNG — THƯ VIỆN + FREESOUND")',
        'label = "QUẢN LÝ NHẠC ($musicCount)"',
        'label = "QUẢN LÝ MÔI TRƯỜNG ($ambienceCount)"',
        'label = "QUẢN LÝ SFX ($sfxCount)"',
        'label = "CHUẨN HÓA TOÀN BỘ THƯ VIỆN"',
        'title = "AI chọn nhạc cảnh từ thư viện"',
        'title = "AI chọn âm thanh môi trường từ thư viện"',
        'title = "AI chọn hiệu ứng âm thanh từ thư viện"',
        'title = "Tự chọn / tìm nhạc nền"',
        'title = "Tự chọn / tìm môi trường"',
        'title = "Tự chọn / tìm SFX"',
        'title = "Nhạc nền ($normalizationMusicCount tệp)"',
        'title = "Âm thanh môi trường ($normalizationAmbienceCount tệp)"',
        'title = "Hiệu ứng âm thanh ($normalizationSfxCount tệp)"',
        'Text("ĐO LẠI TỪ ĐẦU")',
        "startNormalization(forceRemeasure = false)",
        "startNormalization(forceRemeasure = true)",
        "UnifiedAudioAssetManagerDialog(",
        "managedFreesoundOnly = false",
    ],
'''
text = text[:start] + new_block + text[end:]
path.write_text(text, encoding='utf-8')
