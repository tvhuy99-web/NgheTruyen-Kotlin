from pathlib import Path

path = Path("app/src/main/java/vn/nghetruyen/app/ui/screens/PersonalScreen.kt")
text = path.read_text(encoding="utf-8")
needle = '            SettingSwitch("Tự lập nhạc cảnh", state.autoSceneMusicEnabled, onAutoSceneMusicChange)\n'
insert = needle + '            vn.nghetruyen.app.ui.components.AudioDirectionLayerSwitches(\n                modifier = Modifier.padding(top = 4.dp),\n            )\n'
if insert in text:
    raise SystemExit(0)
if text.count(needle) != 1:
    raise SystemExit(f"Expected exactly one scene-music switch anchor, found {text.count(needle)}")
path.write_text(text.replace(needle, insert), encoding="utf-8")
