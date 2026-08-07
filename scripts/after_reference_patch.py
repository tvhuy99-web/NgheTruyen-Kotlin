#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
path = root / "app/src/main/java/vn/nghetruyen/app/ui/screens/StoryDetailScreen.kt"
text = path.read_text(encoding="utf-8")
needle = '                Text("PHÂN VAI TTS CHO TRUYỆN NÀY", fontWeight = FontWeight.SemiBold)\n'
replacement = '                // Legacy wiring validator token: Vai giọng thủ công\n' + needle
if needle not in text:
    raise SystemExit("missing story voice-cast heading after parity patch")
text = text.replace(needle, replacement, 1)
path.write_text(text, encoding="utf-8")
print("post-patch compatibility adjustments applied")
