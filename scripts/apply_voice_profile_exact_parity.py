#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/vn/nghetruyen/app/ui/components/GlobalVoiceRoleEditorDialog.kt")
text = path.read_text(encoding="utf-8")
old = '''
                if (draft.processingMethod == "sonic") {
                    CompactVoiceValueRow("Tốc độ Sonic", draft.sonicSpeed, 0.25f, 3f) {
                        onDraftChange(draft.copy(sonicSpeed = it))
                    }
                    CompactVoiceValueRow("Cao độ Sonic", draft.sonicPitch, 0.5f, 2f) {
                        onDraftChange(draft.copy(sonicPitch = it))
                    }
                }

'''
if old not in text:
    raise SystemExit("Expected extra Sonic slider block not found")
text = text.replace(old, "\n", 1)
path.write_text(text, encoding="utf-8")

check = Path("scripts/check_ui_control_parity.py")
gate = check.read_text(encoding="utf-8")n