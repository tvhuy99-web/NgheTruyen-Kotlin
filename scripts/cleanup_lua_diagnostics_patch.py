#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FILES = [
    "app/src/main/java/vn/nghetruyen/app/ui/components/ReferenceDiagnosticsChrome.kt",
    "app/src/main/java/vn/nghetruyen/app/ui/ReferenceNgheTruyenApp.kt",
    "app/src/main/java/vn/nghetruyen/app/sourceplatform/SourceDiagnosticRuntime.kt",
    "app/src/main/java/vn/nghetruyen/app/playback/ReaderPlaybackService.kt",
    "app/src/main/java/vn/nghetruyen/app/ai/XpkNarrationAiServices.kt",
]

for relative in FILES:
    path = ROOT / relative
    lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    seen = set()
    output = []
    for line in lines:
        if line.startswith("import "):
            key = line.strip()
            if key in seen:
                continue
            seen.add(key)
        output.append(line)
    path.write_text("".join(output), encoding="utf-8")

print("LUA_DIAGNOSTICS_IMPORT_CLEANUP=PASS")
