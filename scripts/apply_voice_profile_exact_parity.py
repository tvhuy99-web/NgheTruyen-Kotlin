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
path.write_text(text.replace(old, "\n", 1), encoding="utf-8")
print("VOICE_PROFILE_EXACT_PARITY_APPLIED")
