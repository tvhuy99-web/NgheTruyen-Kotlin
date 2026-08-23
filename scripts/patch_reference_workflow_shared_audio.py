from pathlib import Path

path = Path('scripts/check_reference_workflow_parity.py')
text = path.read_text(encoding='utf-8')
old = '''for marker in [
    "StoryAudioSourceMode.LOCAL_MANUAL",
    "StoryAudioSourceMode.AI_LOCAL",
    "StoryAudioSourceMode.AI_FREESOUND",
    'label = "QUẢN LÝ NHẠC ($musicTrackCount)"',
    'label = "QUẢN LÝ NHẠC LOCAL',
    'label = "QUẢN LÝ MÔI TRƯỜNG LOCAL',
    'label = "QUẢN LÝ SFX LOCAL',
    'label = "QUẢN LÝ NHẠC ĐÃ TẢI',
    'label = "QUẢN LÝ MÔI TRƯỜNG ĐÃ TẢI',
    'label = "QUẢN LÝ SFX ĐÃ TẢI',
    "UnifiedAudioAssetManagerDialog(",
]:
'''
new = '''for marker in [
    "StoryAudioSourceMode.LOCAL_MANUAL",
    "StoryAudioSourceMode.AI_LOCAL",
    "StoryAudioSourceMode.AI_FREESOUND",
    'Text("MODE 1 · PHÁT THỦ CÔNG TỪ THƯ VIỆN LOCAL")',
    'Text("MODE 2 · AI CHỌN TỪ THƯ VIỆN")',
    'Text("MODE 3 · AI TỰ ĐỘNG — THƯ VIỆN + FREESOUND")',
    'label = "QUẢN LÝ NHẠC ($musicCount)"',
    'label = "QUẢN LÝ MÔI TRƯỜNG ($ambienceCount)"',
    'label = "QUẢN LÝ SFX ($sfxCount)"',
    'label = "CHUẨN HÓA TOÀN BỘ THƯ VIỆN"',
    "UnifiedAudioAssetManagerDialog(",
    "managedFreesoundOnly = false",
]:
'''
if text.count(old) != 1:
    raise SystemExit('stale audio marker block not found exactly once')
text = text.replace(old, new, 1)
old_positions = '''manager_positions = [
    component.find('label = "QUẢN LÝ NHẠC LOCAL'),
    component.find('label = "QUẢN LÝ MÔI TRƯỜNG LOCAL'),
    component.find('label = "QUẢN LÝ SFX LOCAL'),
]
'''
new_positions = '''manager_positions = [
    component.find('label = "QUẢN LÝ NHẠC ($musicCount)"'),
    component.find('label = "QUẢN LÝ MÔI TRƯỜNG ($ambienceCount)"'),
    component.find('label = "QUẢN LÝ SFX ($sfxCount)"'),
]
'''
if text.count(old_positions) != 1:
    raise SystemExit('stale manager position block not found exactly once')
text = text.replace(old_positions, new_positions, 1)
path.write_text(text, encoding='utf-8')
